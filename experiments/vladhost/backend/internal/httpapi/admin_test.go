package httpapi_test

import (
	"archive/zip"
	"bytes"
	"context"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"vladhost/internal/activity"
	"vladhost/internal/admin"
	"vladhost/internal/config"
	"vladhost/internal/httpapi"
	"vladhost/internal/sites"
)

func (e *env) withAdmin() {
	e.t.Helper()
	e.r = httpapi.New(e.svc, e.sites, config.Config{JWTSecret: []byte(strings.Repeat("s", 32)), AuthPerMinute: 10000, AuthBurst: 10000},
		httpapi.WithActivity(activity.New(e.db)), httpapi.WithAdmin(admin.New(e.db, e.svc, e.sites)))
}

type adminUsersBody struct {
	Users []struct {
		ID        int64   `json:"id"`
		Username  string  `json:"username"`
		Sites     int     `json:"sites"`
		BlockedAt *string `json:"blocked_at"`
	} `json:"users"`
	Total int `json:"total"`
}

func (e *env) marker(host string) bool {
	_, err := os.Stat(filepath.Join(e.root, host, sites.SuspendedMarker))
	return err == nil
}

func TestAdminUsersBlockAndLimits(t *testing.T) {
	e := newEnv(t)
	e.withAdmin()
	adm, _ := e.admin()
	john := e.user(adm, "john")
	_, host := e.createSite(john, "blog")
	tok := e.createToken(john, map[string]any{"name": "ci"})

	// Обычный пользователь в раздел администратора не попадает.
	if w := e.do("GET", "/api/admin/users", nil, john); w.Code != 403 {
		t.Fatalf("не админ: %d", w.Code)
	}
	list := decode[adminUsersBody](t, e.do("GET", "/api/admin/users?q=joh", nil, adm))
	if list.Total != 1 || list.Users[0].Username != "john" || list.Users[0].Sites != 1 {
		t.Fatalf("поиск: %+v", list)
	}
	uid := list.Users[0].ID
	path := "/api/admin/users/" + itoa(uid)

	// Личный лимит: второй сайт становится возможен (общий лимит в тестах — 1).
	if w := e.do("POST", "/api/sites", map[string]string{"slug": "shop"}, john); w.Code == 201 {
		t.Fatal("лимит не действует")
	}
	if w := e.do("PUT", path+"/limits", map[string]any{"max_sites": 2, "disk_quota_bytes": nil}, adm); w.Code != 200 {
		t.Fatalf("лимиты: %d %s", w.Code, w.Body)
	}
	if w := e.do("POST", "/api/sites", map[string]string{"slug": "shop"}, john); w.Code != 201 {
		t.Fatalf("второй сайт: %d %s", w.Code, w.Body)
	}
	if w := e.do("PUT", path+"/limits", map[string]any{"max_sites": 1000}, adm); w.Code != 422 {
		t.Fatalf("лимит вне границ: %d", w.Code)
	}

	// Блокировка: причина обязательна; админа и себя заблокировать нельзя.
	if w := e.do("POST", path+"/block", map[string]any{"reason": " "}, adm); w.Code != 422 {
		t.Fatalf("без причины: %d", w.Code)
	}
	admID := decode[adminUsersBody](t, e.do("GET", "/api/admin/users?status=admin", nil, adm)).Users[0].ID
	if w := e.do("POST", "/api/admin/users/"+itoa(admID)+"/block", map[string]any{"reason": "x"}, adm); w.Code != 409 {
		t.Fatalf("себя: %d", w.Code)
	}
	if w := e.do("POST", path+"/block", map[string]any{"reason": "фишинг"}, adm); w.Code != 200 {
		t.Fatalf("блокировка: %d %s", w.Code, w.Body)
	}
	// Уже выданный токен перестаёт работать сразу; вход и API-токен — отказ с причиной; сайты приостановлены.
	if w := e.do("GET", "/api/me", nil, john); w.Code != 401 {
		t.Fatalf("старый токен: %d", w.Code)
	}
	w := e.do("POST", "/api/auth/login", map[string]string{"login": "john", "password": "password123"}, "")
	if b := decode[struct {
		Error struct{ Code, Message string } `json:"error"`
	}](t, w); w.Code != 403 || b.Error.Code != "account_blocked" || !strings.Contains(b.Error.Message, "фишинг") {
		t.Fatalf("вход: %d %s", w.Code, w.Body)
	}
	if w := e.upload("/api/ci/deploy", tok.Secret, makeZip(t, zipFile{name: "index.html", body: "x"})); w.Code != 403 {
		t.Fatalf("API-токен: %d %s", w.Code, w.Body)
	}
	if !e.marker(host) {
		t.Fatal("сайт не приостановлен")
	}
	if b := decode[adminUsersBody](t, e.do("GET", "/api/admin/users?status=blocked", nil, adm)); b.Total != 1 {
		t.Fatalf("фильтр заблокированных: %+v", b)
	}

	// Разблокировка возвращает вход и сайты.
	if w := e.do("POST", path+"/unblock", nil, adm); w.Code != 200 {
		t.Fatalf("разблокировка: %d", w.Code)
	}
	if e.marker(host) {
		t.Fatal("сайт остался приостановленным")
	}
	w = e.do("POST", "/api/auth/login", map[string]string{"login": "john", "password": "password123"}, "")
	if w.Code != 200 {
		t.Fatalf("вход после разблокировки: %d", w.Code)
	}
	john = decode[sessionBody](t, w).AccessToken

	// Пользователь видит в своём журнале, что с аккаунтом делал администратор; администратор видит журнал пользователя.
	ev := e.do("GET", "/api/activity", nil, john).Body.String()
	for _, k := range []string{"account.blocked", "account.unblocked", "account.limits"} {
		if !strings.Contains(ev, k) {
			t.Fatalf("журнал пользователя без %s: %s", k, ev)
		}
	}
	if w := e.do("GET", path+"/activity", nil, adm); w.Code != 200 || !strings.Contains(w.Body.String(), "site.create") {
		t.Fatalf("журнал глазами админа: %d %s", w.Code, w.Body)
	}
	if !strings.Contains(e.do("GET", "/api/activity", nil, adm).Body.String(), "admin.user_block") {
		t.Fatal("журнал администратора без блокировки")
	}
}

type abuseListBody struct {
	Reports []struct {
		ID        int64   `json:"id"`
		Host      string  `json:"host"`
		SiteID    *int64  `json:"site_id"`
		SiteHost  string  `json:"site_host"`
		OwnerName string  `json:"owner_name"`
		Status    string  `json:"status"`
		Resolved  *string `json:"resolved_at"`
	} `json:"reports"`
	Total int `json:"total"`
}

func TestAbuseReports(t *testing.T) {
	e := newEnv(t)
	e.withAdmin()
	adm, _ := e.admin()
	john := e.user(adm, "john")
	sid, host := e.createSite(john, "blog")

	for _, c := range []struct {
		body map[string]any
		code string
	}{
		{map[string]any{"url": "not a url", "category": "phishing", "message": "поддельный банк"}, "abuse_url"},
		{map[string]any{"url": host, "category": "nope", "message": "поддельный банк"}, "bad_request"},
		{map[string]any{"url": host, "category": "phishing", "message": "коротко"}, "abuse_message"},
		{map[string]any{"url": host, "category": "phishing", "message": "поддельный банк просит пароль", "email": "bad"}, "abuse_email"},
	} {
		w := e.do("POST", "/api/abuse", c.body, "")
		if b := decode[errBody](t, w); w.Code < 400 || b.Error.Code != c.code {
			t.Fatalf("%v: %d %s", c.body, w.Code, w.Body)
		}
	}
	// Поддомен сайта тоже находится; жалоба без входа.
	if w := e.do("POST", "/api/abuse", map[string]any{"url": "https://www." + host + "/login.php", "category": "phishing", "message": "поддельная форма входа в банк", "email": "a@b.co"}, ""); w.Code != 201 {
		t.Fatalf("жалоба: %d %s", w.Code, w.Body)
	}
	if w := e.do("POST", "/api/abuse", map[string]any{"url": "example.org", "category": "spam", "message": "не наш сайт, но жалоба"}, ""); w.Code != 201 {
		t.Fatalf("чужой адрес: %d %s", w.Code, w.Body)
	}
	if w := e.do("GET", "/api/admin/abuse", nil, john); w.Code != 403 {
		t.Fatalf("очередь не админу: %d", w.Code)
	}
	list := decode[abuseListBody](t, e.do("GET", "/api/admin/abuse?status=new", nil, adm))
	if list.Total != 2 || list.Reports[1].SiteID == nil || *list.Reports[1].SiteID != sid || list.Reports[1].OwnerName != "john" || list.Reports[0].SiteID != nil {
		t.Fatalf("очередь: %+v", list)
	}
	if s := e.do("GET", "/api/admin/summary", nil, adm).Body.String(); !strings.Contains(s, `"abuse_new":2`) {
		t.Fatalf("счётчик: %s", s)
	}

	// Приостановка по жалобе переживает блокировку и разблокировку владельца.
	if w := e.do("POST", "/api/admin/sites/"+itoa(sid)+"/suspend", map[string]any{"reason": "фишинг по жалобе №1"}, adm); w.Code != 200 || !e.marker(host) {
		t.Fatalf("приостановка: %d %s", w.Code, w.Body)
	}
	b := decode[adminUsersBody](t, e.do("GET", "/api/admin/users?q=john", nil, adm))
	upath := "/api/admin/users/" + itoa(b.Users[0].ID)
	e.do("POST", upath+"/block", map[string]any{"reason": "повторно"}, adm)
	e.do("POST", upath+"/unblock", nil, adm)
	if !e.marker(host) {
		t.Fatal("разблокировка сняла приостановку по жалобе")
	}
	// Владелец видит причину на своём сайте.
	login := e.do("POST", "/api/auth/login", map[string]string{"login": "john", "password": "password123"}, "")
	john = decode[sessionBody](t, login).AccessToken
	if s := e.do("GET", "/api/sites", nil, john).Body.String(); !strings.Contains(s, "фишинг по жалобе №1") {
		t.Fatalf("причина у владельца: %s", s)
	}

	if w := e.do("PATCH", "/api/admin/abuse/"+itoa(list.Reports[1].ID), map[string]any{"status": "resolved", "note": "сайт приостановлен"}, adm); w.Code != 200 {
		t.Fatalf("закрыть жалобу: %d %s", w.Code, w.Body)
	}
	if w := e.do("POST", "/api/admin/sites/"+itoa(sid)+"/unsuspend", nil, adm); w.Code != 200 || e.marker(host) {
		t.Fatalf("снять приостановку: %d", w.Code)
	}
	if n := decode[abuseListBody](t, e.do("GET", "/api/admin/abuse?status=new", nil, adm)).Total; n != 1 {
		t.Fatalf("новых после разбора: %d", n)
	}
}

// Антивирус: заражённый архив и файл не попадают на сайт.
type fakeScanner struct{}

// Как clamd, заглядывает внутрь zip.
func (fakeScanner) Scan(_ context.Context, r io.Reader) (string, error) {
	b, _ := io.ReadAll(r)
	text := string(b)
	if zr, err := zip.NewReader(bytes.NewReader(b), int64(len(b))); err == nil {
		for _, f := range zr.File {
			rc, _ := f.Open()
			inner, _ := io.ReadAll(rc)
			_ = rc.Close()
			text += string(inner)
		}
	}
	if strings.Contains(text, "EICAR") {
		return "Eicar-Test-Signature", nil
	}
	return "", nil
}

func TestUploadsAreScanned(t *testing.T) {
	e := newEnv(t)
	e.sites.SetScanner(fakeScanner{})
	adm, _ := e.admin()
	john := e.user(adm, "john")
	id, host := e.createSite(john, "blog")
	w := e.upload("/api/sites/"+itoa(id)+"/deploy", john, makeZip(t, zipFile{name: "index.html", body: "ok"}, zipFile{name: "x.txt", body: "EICAR"}))
	if b := decode[errBody](t, w); w.Code != 422 || b.Error.Code != "virus_found" {
		t.Fatalf("архив: %d %s", w.Code, w.Body)
	}
	if w := e.upload("/api/sites/"+itoa(id)+"/deploy", john, makeZip(t, zipFile{name: "index.html", body: "ok"})); w.Code != 200 || e.read(host, "index.html") != "ok" {
		t.Fatalf("чистый архив: %d %s", w.Code, w.Body)
	}
}
