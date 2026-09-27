package siteimport

import (
	"archive/tar"
	"archive/zip"
	"bufio"
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"path"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"vladhost/internal/netguard"
	"vladhost/internal/sites"
)

func isUniqueViolation(err error) bool {
	pe, ok := errors.AsType[*pgconn.PgError](err)
	return ok && pe.Code == "23505"
}

// checkURL: только http(s), без логина в адресе и без IP внутренней сети (имя проверяется при соединении).
func checkURL(raw string, allowPrivate bool) (*url.URL, error) {
	raw = strings.TrimSpace(raw)
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" || u.User != nil || len(raw) > 2000 {
		return nil, ErrBadURL
	}
	if a, err := netip.ParseAddr(u.Hostname()); err == nil && !allowPrivate && netguard.Private(a) {
		return nil, ErrPrivate
	}
	return u, nil
}

// netErr переводит сетевую ошибку в понятную пользователю.
func netErr(err error) error {
	if errors.Is(err, netguard.ErrPrivate) {
		return ErrPrivate
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return ErrTimeout
	}
	return ErrConnect
}

// fetchURL скачивает архив во временный файл (не больше квоты) и, если это tar, перепаковывает его в zip.
func (s *Service) fetchURL(ctx context.Context, raw string, limit int64) (fetched, error) {
	u, err := checkURL(raw, s.cfg.AllowPrivate)
	if err != nil {
		return fetched{}, err
	}
	client := &http.Client{
		Transport: &http.Transport{DialContext: netguard.Dialer(15*time.Second, s.cfg.AllowPrivate).DialContext, Proxy: nil,
			TLSHandshakeTimeout: 15 * time.Second, ResponseHeaderTimeout: time.Minute},
		CheckRedirect: func(_ *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return errors.New("too many redirects")
			}
			return nil
		},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return fetched{}, ErrBadURL
	}
	req.Header.Set("User-Agent", "Vladhost-Import/1.0")
	resp, err := client.Do(req)
	if err != nil {
		return fetched{}, netErr(err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return fetched{}, ErrHTTPStatus.With(resp.StatusCode)
	}
	if resp.ContentLength > limit {
		return fetched{}, sites.ErrQuota
	}

	dl, err := s.tempFile("vladhost-import-*.bin")
	if err != nil {
		return fetched{}, err
	}
	defer func() { _ = os.Remove(dl.Name()) }()
	defer func() { _ = dl.Close() }()
	n, err := io.Copy(dl, io.LimitReader(resp.Body, limit+1))
	if err != nil {
		if ctx.Err() != nil {
			return fetched{bytes: n}, ErrTimeout
		}
		return fetched{bytes: n}, ErrConnect
	}
	if n > limit {
		return fetched{bytes: n}, sites.ErrQuota
	}
	if _, err := dl.Seek(0, io.SeekStart); err != nil {
		return fetched{}, err
	}

	head := make([]byte, 512)
	m, _ := io.ReadFull(dl, head)
	head = head[:m]
	if _, err := dl.Seek(0, io.SeekStart); err != nil {
		return fetched{}, err
	}
	switch {
	case bytes.HasPrefix(head, []byte("PK\x03\x04")) || bytes.HasPrefix(head, []byte("PK\x05\x06")):
		// zip выкладывается как есть; файл переименовываем, чтобы он пережил defer-удаление выше.
		out := dl.Name() + ".zip"
		if err := os.Rename(dl.Name(), out); err != nil {
			return fetched{}, err
		}
		zr, err := zip.OpenReader(out)
		files := 0
		if err == nil {
			files = len(zr.File)
			_ = zr.Close()
		}
		return fetched{zip: out, bytes: n, files: files}, nil
	case bytes.HasPrefix(head, []byte{0x1f, 0x8b}):
		gz, err := gzip.NewReader(bufio.NewReader(dl))
		if err != nil {
			return fetched{bytes: n}, ErrFormat
		}
		st, err := s.tarToZip(gz, limit)
		st.bytes = n
		return st, err
	case len(head) >= 262 && string(head[257:262]) == "ustar":
		st, err := s.tarToZip(dl, limit)
		st.bytes = n
		return st, err
	}
	return fetched{bytes: n}, ErrFormat
}

// zipBuilder собирает zip из потока файлов с лимитом на распакованный размер и число файлов.
type zipBuilder struct {
	f     *os.File
	zw    *zip.Writer
	limit int64
	total int64
	files int
}

func (s *Service) newZip(limit int64) (*zipBuilder, error) {
	f, err := s.tempFile("vladhost-import-*.zip")
	if err != nil {
		return nil, err
	}
	return &zipBuilder{f: f, zw: zip.NewWriter(f), limit: limit}, nil
}

func (b *zipBuilder) dir(name string) error {
	_, err := b.zw.Create(strings.TrimSuffix(name, "/") + "/")
	return err
}

func (b *zipBuilder) file(name string, r io.Reader, modified time.Time) error {
	if b.files++; b.files > maxFiles {
		return ErrTooMany
	}
	w, err := b.zw.CreateHeader(&zip.FileHeader{Name: name, Method: zip.Deflate, Modified: modified})
	if err != nil {
		return err
	}
	n, err := io.Copy(w, io.LimitReader(r, b.limit-b.total+1))
	b.total += n
	if err != nil {
		return err
	}
	if b.total > b.limit {
		return sites.ErrQuota
	}
	return nil
}

// finish закрывает архив; при ошибке удаляет его.
func (b *zipBuilder) finish(err error) (string, error) {
	if cerr := b.zw.Close(); err == nil {
		err = cerr
	}
	if cerr := b.f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		_ = os.Remove(b.f.Name())
		return "", err
	}
	return b.f.Name(), nil
}

// tarToZip перепаковывает tar в zip. Симлинки и спецфайлы пропускаются (в дампах хостингов их много, а выложить их всё равно нельзя);
// проверку путей делает деплой.
func (s *Service) tarToZip(r io.Reader, limit int64) (fetched, error) {
	b, err := s.newZip(limit)
	if err != nil {
		return fetched{}, err
	}
	tr := tar.NewReader(r)
	err = func() error {
		for {
			h, err := tr.Next()
			if errors.Is(err, io.EOF) {
				return nil
			}
			if err != nil {
				return ErrFormat
			}
			name := strings.TrimPrefix(path.Clean("/"+h.Name), "/")
			if name == "" {
				continue
			}
			switch h.Typeflag {
			case tar.TypeDir:
				err = b.dir(name)
			case tar.TypeReg:
				err = b.file(name, tr, h.ModTime)
			}
			if err != nil {
				return err
			}
		}
	}()
	out, err := b.finish(err)
	return fetched{zip: out, files: b.files}, err
}

// checkFTP проверяет и дополняет параметры FTP: порт по умолчанию, папка по умолчанию.
func checkFTP(src *Source, allowPrivate bool) error {
	src.Host = strings.TrimSpace(strings.ToLower(src.Host))
	src.Host = strings.TrimPrefix(strings.TrimPrefix(src.Host, "ftp://"), "ftps://")
	src.Host = strings.TrimSuffix(src.Host, "/")
	if h, p, err := net.SplitHostPort(src.Host); err == nil && src.Port == 0 {
		var port int
		if _, err := fmt.Sscan(p, &port); err == nil {
			src.Host, src.Port = h, port
		}
	}
	if src.Host == "" || len(src.Host) > 253 || strings.ContainsAny(src.Host, "/@ \t") {
		return ErrBadHost
	}
	if a, err := netip.ParseAddr(src.Host); err == nil && !allowPrivate && netguard.Private(a) {
		return ErrPrivate
	}
	if src.Port == 0 {
		src.Port = 21
	}
	if src.Port < 1 || src.Port > 65535 {
		return ErrBadHost
	}
	src.User = strings.TrimSpace(src.User)
	if src.User == "" {
		src.User = "anonymous"
	}
	if len(src.User) > 200 || len(src.Password) > 200 || strings.ContainsAny(src.User+src.Password, "\r\n") {
		return ErrFTPLogin
	}
	src.Path = "/" + strings.Trim(strings.TrimSpace(src.Path), "/")
	if len(src.Path) > 1000 || strings.ContainsAny(src.Path, "\r\n") {
		return ErrFTPPath
	}
	return nil
}
