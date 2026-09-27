package siteimport

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"path"
	"strconv"
	"strings"
	"time"

	"github.com/jlaffaye/ftp"

	"vladhost/internal/netguard"
	"vladhost/internal/sites"
)

// fetchFTP скачивает папку со старого хостинга и собирает из неё zip. Соединение данных идёт через тот же соединитель,
// так что ответ PASV с внутренним адресом тоже отсекается (а библиотека и так берёт адрес сервера, а не из PASV).
func (s *Service) fetchFTP(ctx context.Context, src Source, limit int64) (fetched, error) {
	opts := []ftp.DialOption{
		ftp.DialWithDialer(*netguard.Dialer(20*time.Second, s.cfg.AllowPrivate)),
		ftp.DialWithContext(ctx),
		ftp.DialWithTimeout(30 * time.Second),
	}
	if src.TLS {
		opts = append(opts, ftp.DialWithExplicitTLS(&tls.Config{ServerName: src.Host, MinVersion: tls.VersionTLS12}))
	}
	c, err := ftp.Dial(net.JoinHostPort(src.Host, strconv.Itoa(src.Port)), opts...)
	if err != nil {
		return fetched{}, netErr(err)
	}
	defer func() { _ = c.Quit() }()
	// Соединение не знает о ctx после установки: по истечении времени закрываем его, чтобы оборвать зависшую передачу.
	stop := context.AfterFunc(ctx, func() { _ = c.Quit() })
	defer stop()

	if err := c.Login(src.User, src.Password); err != nil {
		return fetched{}, ErrFTPLogin
	}
	if err := c.ChangeDir(src.Path); err != nil {
		return fetched{}, ErrFTPPath
	}

	b, err := s.newZip(limit)
	if err != nil {
		return fetched{}, err
	}
	w := c.Walk(src.Path)
	err = func() error {
		for w.Next() {
			if err := ctx.Err(); err != nil {
				return ErrTimeout
			}
			e := w.Stat()
			rel := strings.TrimPrefix(strings.TrimPrefix(w.Path(), src.Path), "/")
			if rel == "" || e == nil {
				continue
			}
			switch e.Type {
			case ftp.EntryTypeFolder:
				if err := b.dir(rel); err != nil {
					return err
				}
			case ftp.EntryTypeFile:
				if err := s.ftpFile(c, b, w.Path(), rel, e.Time); err != nil {
					return err
				}
			}
			// ссылки пропускаются: выложить их всё равно нельзя
		}
		if err := w.Err(); err != nil {
			if ctx.Err() != nil {
				return ErrTimeout
			}
			return ErrConnect
		}
		return nil
	}()
	out, err := b.finish(err)
	return fetched{zip: out, bytes: b.total, files: b.files}, err
}

func (s *Service) ftpFile(c *ftp.ServerConn, b *zipBuilder, full, rel string, mod time.Time) error {
	r, err := c.Retr(full)
	if err != nil {
		return ErrConnect
	}
	err = b.file(path.Clean(rel), r, mod)
	if cerr := r.Close(); err == nil && cerr != nil {
		err = ErrConnect
	}
	if err != nil && !isOwnErr(err) {
		return ErrConnect
	}
	return err
}

// isOwnErr: ошибка уже понятная (квота, слишком много файлов) — её не надо заменять на «сбой соединения».
func isOwnErr(err error) bool {
	return errors.Is(err, ErrTooMany) || errors.Is(err, sites.ErrQuota)
}
