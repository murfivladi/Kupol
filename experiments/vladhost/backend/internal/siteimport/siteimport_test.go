package siteimport_test

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"gorm.io/gorm"

	"vladhost/internal/apperr"
	"vladhost/internal/auth"
	"vladhost/internal/config"
	"vladhost/internal/ftpd"
	"vladhost/internal/siteimport"
	"vladhost/internal/sites"
	"vladhost/internal/testdb"
)

type fixture struct {
	t     *testing.T
	sites *sites.Service
	imp   *siteimport.Service
	user  *auth.User
	site  *sites.Site
	root  string
	db    *gorm.DB
}

func setup(t *testing.T, quota int64) *fixture {
	t.Helper()
	db := testdb.Open(t)
	root := t.TempDir()
	svc := sites.NewService(db, root, "vladinc.ru", "", sites.Limits{MaxSites: 3, DiskQuotaBytes: quota})
	authSvc := auth.NewService(db, []byte(strings.Repeat("s", 32)), time.Minute, time.Hour)
	u, err := authSvc.CreateAdmin(context.Background(), "john@example.com", "john", "password123")
	if err != nil {
		t.Fatal(err)
	}
	site, err := svc.Create(context.Background(), *u, "blog")
	if err != nil {
		t.Fatal(err)
	}
	return &fixture{t: t, sites: svc, user: u, site: site, root: root, db: db,
		imp: siteimport.New(db, svc, siteimport.Config{AllowPrivate: true, TempDir: t.TempDir()})}
}

// run запускает импорт, ждёт окончания и возвращает задачу.
func (f *fixture) run(src siteimport.Source) *siteimport.Job {
	f.t.Helper()
	if _, err := f.imp.Start(context.Background(), f.user.ID, f.site.ID, src); err != nil {
		f.t.Fatalf("старт: %v", err)
	}
	f.imp.Wait()
	j, err := f.imp.Latest(context.Background(), f.user.ID, f.site.ID)
	if err != nil || j == nil {
		f.t.Fatalf("задача: %v %v", j, err)
	}
	return j
}

func (f *fixture) read(rel string) string {
	f.t.Helper()
	b, err := os.ReadFile(filepath.Join(f.root, f.site.Host, "public", rel))
	if err != nil {
		f.t.Fatal(err)
	}
	return string(b)
}

func serve(t *testing.T, status int, body []byte) string {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(status)
		_, _ = w.Write(body)
	}))
	t.Cleanup(srv.Close)
	return srv.URL + "/site.zip?sig=secret"
}

func makeZip(t *testing.T, files map[string]string) []byte {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, body := range files {
		w, _ := zw.Create(name)
		_, _ = w.Write([]byte(body))
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func makeTarGz(t *testing.T) []byte {
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	add := func(h *tar.Header, body string) {
		h.Size = int64(len(body))
		if err := tw.WriteHeader(h); err != nil {
			t.Fatal(err)
		}
		_, _ = tw.Write([]byte(body))
	}
	add(&tar.Header{Name: "./public_html/", Typeflag: tar.TypeDir, Mode: 0o755}, "")
	add(&tar.Header{Name: "./public_html/index.php", Typeflag: tar.TypeReg, Mode: 0o644}, "<?php echo 1;")
	add(&tar.Header{Name: "./public_html/img/a.png", Typeflag: tar.TypeReg, Mode: 0o644}, "png")
	add(&tar.Header{Name: "./public_html/link", Typeflag: tar.TypeSymlink, Linkname: "/etc/passwd"}, "")
	_ = tw.Close()
	_ = gz.Close()
	return buf.Bytes()
}

func TestImportFromURL(t *testing.T) {
	f := setup(t, 1<<20)

	j := f.run(siteimport.Source{Kind: siteimport.KindURL, URL: serve(t, 200, makeZip(t, map[string]string{"site/index.html": "zip"}))})
	if j.Status != siteimport.StatusDone || f.read("index.html") != "zip" || strings.Contains(j.Source, "secret") {
		t.Fatalf("zip: %+v %v", j, j.Err())
	}

	// tar.gz: одна корневая папка снимается, симлинк пропускается.
	j = f.run(siteimport.Source{Kind: siteimport.KindURL, URL: serve(t, 200, makeTarGz(t))})
	if j.Status != siteimport.StatusDone || f.read("index.php") != "<?php echo 1;" || f.read("img/a.png") != "png" || j.Files != 2 {
		t.Fatalf("tar.gz: %+v %v", j, j.Err())
	}
	if _, err := os.Lstat(filepath.Join(f.root, f.site.Host, "public", "link")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("симлинк выложен")
	}

	cases := []struct {
		url  string
		code string
	}{
		{serve(t, 404, nil), "import.http_status"},
		{serve(t, 200, []byte("<html>not an archive</html>")), "import.format"},
		{serve(t, 200, bytes.Repeat([]byte("x"), 2<<20)), "quota_exceeded"},
		{serve(t, 200, makeZip(t, map[string]string{"readme.txt": "no index"})), "archive.no_index"},
	}
	for _, c := range cases {
		j := f.run(siteimport.Source{Kind: siteimport.KindURL, URL: c.url})
		if j.Status != siteimport.StatusFailed || j.Err() == nil || j.Err().Code != c.code {
			t.Fatalf("%s: %+v %v", c.code, j, j.Err())
		}
	}
	if f.read("index.php") != "<?php echo 1;" {
		t.Fatal("неудачный импорт испортил сайт")
	}
}

func TestImportRejectsBadSources(t *testing.T) {
	f := setup(t, 1<<20)
	strict := siteimport.New(f.db, f.sites, siteimport.Config{})
	ctx := context.Background()
	cases := []struct {
		src  siteimport.Source
		code string
	}{
		{siteimport.Source{Kind: siteimport.KindURL, URL: "file:///etc/passwd"}, "import.bad_url"},
		{siteimport.Source{Kind: siteimport.KindURL, URL: "https://user:pw@example.com/a.zip"}, "import.bad_url"},
		{siteimport.Source{Kind: siteimport.KindURL, URL: "http://127.0.0.1/a.zip"}, "import.private"},
		{siteimport.Source{Kind: siteimport.KindURL, URL: "http://[::ffff:10.0.0.1]/a.zip"}, "import.private"},
		{siteimport.Source{Kind: siteimport.KindFTP, Host: "169.254.169.254"}, "import.private"},
		{siteimport.Source{Kind: siteimport.KindFTP, Host: ""}, "import.bad_host"},
		{siteimport.Source{Kind: siteimport.KindFTP, Host: "ftp.example.com", Port: 70000}, "import.bad_host"},
		{siteimport.Source{Kind: siteimport.KindFTP, Host: "ftp.example.com", User: "a\r\nDELE x"}, "import.ftp_login"},
	}
	for _, c := range cases {
		_, err := strict.Start(ctx, f.user.ID, f.site.ID, c.src)
		if err == nil || !errors.Is(err, apperr.New(0, c.code, "")) {
			t.Fatalf("%+v: %v", c.src, err)
		}
	}
	// Имя, которое указывает на внутренний адрес, отсекается при соединении.
	if _, err := strict.Start(ctx, f.user.ID, f.site.ID, siteimport.Source{Kind: siteimport.KindURL, URL: "http://localhost:1/a.zip"}); err != nil {
		t.Fatal(err)
	}
	strict.Wait()
	j, _ := strict.Latest(ctx, f.user.ID, f.site.ID)
	if j.Err() == nil || j.Err().Code != "import.private" {
		t.Fatalf("localhost: %+v %v", j, j.Err())
	}
	// Чужой сайт не найти.
	if _, err := f.imp.Start(ctx, f.user.ID+100, f.site.ID, siteimport.Source{Kind: siteimport.KindURL, URL: "https://example.com/a.zip"}); !errors.Is(err, sites.ErrNotFound) {
		t.Fatalf("чужой сайт: %v", err)
	}
}

func TestImportFromFTP(t *testing.T) {
	f := setup(t, 1<<20)
	// Источник — FTP другого сайта этой же панели (обычный FTP без TLS разрешён только в тесте).
	src, err := f.sites.Create(context.Background(), *f.user, "old")
	if err != nil {
		t.Fatal(err)
	}
	pub := filepath.Join(f.root, src.Host, "public")
	for name, body := range map[string]string{"public_html/index.html": "ftp", "public_html/css/a.css": "b{}", "notes.txt": "x"} {
		_ = os.MkdirAll(filepath.Dir(filepath.Join(pub, name)), 0o755)
		_ = os.WriteFile(filepath.Join(pub, name), []byte(body), 0o644)
	}
	src, pw, err := f.sites.EnableFTP(context.Background(), f.user.ID, src.ID)
	if err != nil {
		t.Fatal(err)
	}
	srv, err := ftpd.New(f.sites, config.FTPConfig{Addr: "127.0.0.1:0", Host: "ftp.vladinc.ru", PublicIP: "127.0.0.1", PassiveStart: 42300, PassiveEnd: 42400, AllowPlain: true})
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.Listen(); err != nil {
		t.Fatal(err)
	}
	go func() { _ = srv.Serve() }()
	t.Cleanup(func() { _ = srv.Stop() })

	j := f.run(siteimport.Source{Kind: siteimport.KindFTP, Host: srv.Addr(), User: f.sites.FTPUsername(src.Host), Password: pw, Path: "/public_html"})
	if j.Status != siteimport.StatusDone || f.read("index.html") != "ftp" || f.read("css/a.css") != "b{}" || j.Files != 2 {
		t.Fatalf("ftp: %+v %v", j, j.Err())
	}
	if strings.Contains(j.Source, pw) {
		t.Fatal("пароль в источнике")
	}

	j = f.run(siteimport.Source{Kind: siteimport.KindFTP, Host: srv.Addr(), User: f.sites.FTPUsername(src.Host), Password: "wrong"})
	if j.Err() == nil || j.Err().Code != "import.ftp_login" {
		t.Fatalf("неверный пароль: %+v %v", j, j.Err())
	}
	j = f.run(siteimport.Source{Kind: siteimport.KindFTP, Host: srv.Addr(), User: f.sites.FTPUsername(src.Host), Password: pw, Path: "/nope"})
	if j.Err() == nil || j.Err().Code != "import.ftp_path" {
		t.Fatalf("нет папки: %+v %v", j, j.Err())
	}
}
