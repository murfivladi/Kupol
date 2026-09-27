package httpapi_test

import (
	"strings"
	"testing"
)

type apiTokenBody struct {
	Token struct {
		ID       int64  `json:"id"`
		Name     string `json:"name"`
		Prefix   string `json:"prefix"`
		SiteHost string `json:"site_host"`
	} `json:"token"`
	Secret string `json:"secret"`
}

type apiTokensBody struct {
	Tokens []struct {
		ID         int64   `json:"id"`
		Prefix     string  `json:"prefix"`
		LastUsedAt *string `json:"last_used_at"`
		LastUsedIP string  `json:"last_used_ip"`
	} `json:"tokens"`
}

func (e *env) createToken(tok string, body map[string]any) apiTokenBody {
	e.t.Helper()
	w := e.do("POST", "/api/me/tokens", body, tok)
	if w.Code != 201 {
		e.t.Fatalf("создание токена: %d %s", w.Code, w.Body)
	}
	return decode[apiTokenBody](e.t, w)
}

func TestAPITokenDeploy(t *testing.T) {
	e := newEnv(t)
	adm, _ := e.admin()
	john := e.user(adm, "john")
	id, host := e.createSite(john, "blog")

	all := e.createToken(john, map[string]any{"name": "GitHub"})
	if !strings.HasPrefix(all.Secret, "vht_") || !strings.HasPrefix(all.Secret, all.Token.Prefix) || all.Token.SiteHost != "" {
		t.Fatalf("токен: %+v", all)
	}
	one := e.createToken(john, map[string]any{"name": "только блог", "site_id": id, "expires_days": 30})
	if one.Token.SiteHost != host {
		t.Fatalf("токен сайта: %+v", one.Token)
	}

	// Токен на любой сайт: имя сайта обязательно, подходит и имя, и адрес.
	if w := e.upload("/api/ci/deploy", all.Secret, makeZip(t, zipFile{name: "index.html", body: "v1"})); w.Code != 404 {
		t.Fatalf("без имени сайта: %d %s", w.Code, w.Body)
	}
	if w := e.upload("/api/ci/deploy?site=blog", all.Secret, makeZip(t, zipFile{name: "index.html", body: "v1"})); w.Code != 200 {
		t.Fatalf("деплой по имени: %d %s", w.Code, w.Body)
	}
	if e.read(host, "index.html") != "v1" {
		t.Fatal("архив не выложен")
	}
	// Токен одного сайта: имя можно не указывать, чужое имя — отказ.
	if w := e.upload("/api/ci/deploy", one.Secret, makeZip(t, zipFile{name: "index.html", body: "v2"})); w.Code != 200 || e.read(host, "index.html") != "v2" {
		t.Fatalf("деплой токеном сайта: %d %s", w.Code, w.Body)
	}
	if w := e.upload("/api/ci/deploy?site=other", one.Secret, makeZip(t, zipFile{name: "index.html", body: "x"})); w.Code != 404 {
		t.Fatalf("токен на другой сайт: %d %s", w.Code, w.Body)
	}

	// Токен не заменяет вход: остальной API его не принимает, и чужой сайт им не выложить.
	if w := e.do("GET", "/api/sites", nil, all.Secret); w.Code != 401 {
		t.Fatalf("токен в API панели: %d", w.Code)
	}
	mary := e.user(adm, "mary")
	e.createSite(mary, "shop")
	if w := e.upload("/api/ci/deploy?site=shop.mary.vladinc.ru", all.Secret, makeZip(t, zipFile{name: "index.html", body: "x"})); w.Code != 404 {
		t.Fatalf("чужой сайт: %d %s", w.Code, w.Body)
	}
	if w := e.do("DELETE", "/api/me/tokens/"+itoa(all.Token.ID), nil, mary); w.Code != 404 {
		t.Fatalf("отзыв чужого токена: %d", w.Code)
	}

	list := decode[apiTokensBody](t, e.do("GET", "/api/me/tokens", nil, john))
	if len(list.Tokens) != 2 || list.Tokens[1].LastUsedAt == nil || list.Tokens[1].LastUsedIP == "" {
		t.Fatalf("список: %+v", list)
	}

	// Отозванный и истёкший токены не работают.
	if w := e.do("DELETE", "/api/me/tokens/"+itoa(all.Token.ID), nil, john); w.Code != 204 {
		t.Fatalf("отзыв: %d %s", w.Code, w.Body)
	}
	if w := e.upload("/api/ci/deploy?site=blog", all.Secret, makeZip(t, zipFile{name: "index.html", body: "x"})); w.Code != 401 || decode[errBody](t, w).Error.Code != "api_token_invalid" {
		t.Fatalf("отозванный токен: %d %s", w.Code, w.Body)
	}
	if err := e.db.Exec("UPDATE api_tokens SET expires_at = now() - interval '1 minute' WHERE id = ?", one.Token.ID).Error; err != nil {
		t.Fatal(err)
	}
	if w := e.upload("/api/ci/deploy", one.Secret, makeZip(t, zipFile{name: "index.html", body: "x"})); w.Code != 401 {
		t.Fatalf("истёкший токен: %d %s", w.Code, w.Body)
	}
	if w := e.upload("/api/ci/deploy", "vht_nonsense", makeZip(t, zipFile{name: "index.html", body: "x"})); w.Code != 401 {
		t.Fatalf("выдуманный токен: %d", w.Code)
	}
}

func TestAPITokenValidation(t *testing.T) {
	e := newEnv(t)
	adm, _ := e.admin()
	john := e.user(adm, "john")
	mary := e.user(adm, "mary")
	maryID, _ := e.createSite(mary, "shop")
	cases := []struct {
		body  map[string]any
		code  string
		field string
	}{
		{map[string]any{"name": "  "}, "api_token_name", "name"},
		{map[string]any{"name": strings.Repeat("я", 61)}, "api_token_name", "name"},
		{map[string]any{"name": "ci", "expires_days": 366}, "api_token_ttl", "expires_days"},
		{map[string]any{"name": "ci", "expires_days": -1}, "api_token_ttl", "expires_days"},
		{map[string]any{"name": "ci", "site_id": maryID}, "not_found", ""},
	}
	for _, c := range cases {
		w := e.do("POST", "/api/me/tokens", c.body, john)
		b := decode[errBody](t, w)
		if w.Code < 400 || b.Error.Code != c.code || b.Error.Field != c.field {
			t.Fatalf("%v: %d %s", c.body, w.Code, w.Body)
		}
	}
	for range 20 {
		e.createToken(john, map[string]any{"name": "ci"})
	}
	if w := e.do("POST", "/api/me/tokens", map[string]any{"name": "ci"}, john); w.Code != 409 {
		t.Fatalf("лимит: %d %s", w.Code, w.Body)
	}
}
