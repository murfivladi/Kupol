package main

import (
	"archive/zip"
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

func TestDeployPacksFolderAndUploads(t *testing.T) {
	dir := t.TempDir()
	_ = os.MkdirAll(filepath.Join(dir, "css"), 0o755)
	_ = os.WriteFile(filepath.Join(dir, "index.html"), []byte("<h1>hi</h1>"), 0o644)
	_ = os.WriteFile(filepath.Join(dir, "css", "a.css"), []byte("b{}"), 0o644)
	_ = os.Symlink("/etc/passwd", filepath.Join(dir, "leak"))

	var got []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/ci/deploy" || r.URL.Query().Get("site") != "blog" || r.Header.Get("Authorization") != "Bearer vht_test" {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":{"message":"нет доступа"}}`))
			return
		}
		f, _, err := r.FormFile("file")
		if err != nil {
			t.Error(err)
			return
		}
		data, _ := io.ReadAll(f)
		zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
		if err != nil {
			t.Error(err)
			return
		}
		for _, zf := range zr.File {
			got = append(got, zf.Name)
		}
		_, _ = w.Write([]byte(`{"site":{"host":"blog.john.vladinc.ru"}}`))
	}))
	defer srv.Close()

	t.Setenv("VLADHOST_TOKEN", "vht_test")
	if err := runDeploy([]string{"--api", srv.URL, "--site", "blog", dir}); err != nil {
		t.Fatal(err)
	}
	sort.Strings(got)
	if strings.Join(got, ",") != "css/,css/a.css,index.html" {
		t.Fatalf("архив: %v", got)
	}

	t.Setenv("VLADHOST_TOKEN", "vht_wrong")
	err := runDeploy([]string{"--api", srv.URL, "--site", "blog", dir})
	if err == nil || !strings.Contains(err.Error(), "нет доступа") {
		t.Fatalf("ошибка панели: %v", err)
	}
	t.Setenv("VLADHOST_TOKEN", "")
	if err := runDeploy([]string{dir}); err == nil || !strings.Contains(err.Error(), "VLADHOST_TOKEN") {
		t.Fatalf("без токена: %v", err)
	}
}
