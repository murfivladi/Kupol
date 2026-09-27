package httpapi_test

import (
	"archive/zip"
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"vladhost/internal/config"
	"vladhost/internal/httpapi"
	"vladhost/internal/siteimport"
)

type importBody struct {
	Job *struct {
		Status string `json:"status"`
		Source string `json:"source"`
		Error  string `json:"error"`
	} `json:"job"`
}

func TestSiteImportAPI(t *testing.T) {
	e := newEnv(t)
	imp := siteimport.New(e.db, e.sites, siteimport.Config{AllowPrivate: true})
	e.r = httpapi.New(e.svc, e.sites, config.Config{JWTSecret: []byte(strings.Repeat("s", 32)), AuthPerMinute: 10000, AuthBurst: 10000}, httpapi.WithImport(imp))
	adm, _ := e.admin()
	john := e.user(adm, "john")
	id, host := e.createSite(john, "blog")
	path := "/api/sites/" + itoa(id) + "/import"

	if b := decode[importBody](t, e.do("GET", path, nil, john)); b.Job != nil {
		t.Fatalf("до импорта: %+v", b.Job)
	}
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	w, _ := zw.Create("index.html")
	_, _ = w.Write([]byte("imported"))
	_ = zw.Close()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write(buf.Bytes()) }))
	defer srv.Close()

	if w := e.do("POST", path, map[string]any{"kind": "url", "url": srv.URL + "/a.zip"}, john); w.Code != 202 {
		t.Fatalf("старт: %d %s", w.Code, w.Body)
	}
	imp.Wait()
	b := decode[importBody](t, e.do("GET", path, nil, john))
	if b.Job == nil || b.Job.Status != "done" || e.read(host, "index.html") != "imported" {
		t.Fatalf("итог: %+v", b.Job)
	}

	// Неверная ссылка отклоняется сразу, с указанием поля формы.
	if w := e.do("POST", path, map[string]any{"kind": "url", "url": "ftp://x"}, john); w.Code != 422 || decode[errBody](t, w).Error.Field != "url" {
		t.Fatalf("плохая ссылка: %d %s", w.Code, w.Body)
	}
	// Чужой сайт недоступен.
	mary := e.user(adm, "mary")
	if w := e.do("GET", path, nil, mary); w.Code != 404 {
		t.Fatalf("чужой сайт: %d", w.Code)
	}
}
