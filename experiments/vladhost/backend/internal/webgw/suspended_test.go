package webgw_test

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Приостановленный сайт: вместо содержимого — страница «сайт приостановлен», и без кеша (после снятия сайт откроется сразу).
func TestSuspendedSite(t *testing.T) {
	f := newSite(t, map[string]string{"index.html": "<h1>secret phishing</h1>"})
	marker := filepath.Join(f.base, host, "SUSPENDED")
	if err := os.WriteFile(marker, []byte("abuse\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	r := f.do("GET", "/")
	if r.Code != 403 || strings.Contains(r.body, "secret") || !strings.Contains(r.body, "Сайт приостановлен") || r.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("приостановлен: %d %q", r.Code, r.body)
	}
	if r := f.do("GET", "/index.html"); r.Code != 403 {
		t.Fatalf("файл по прямому адресу: %d", r.Code)
	}
	_ = os.Remove(marker)
	if r := f.do("GET", "/"); r.Code != 200 || !strings.Contains(r.body, "secret") {
		t.Fatalf("после снятия: %d", r.Code)
	}
}
