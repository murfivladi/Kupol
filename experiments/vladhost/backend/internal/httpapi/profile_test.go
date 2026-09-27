package httpapi_test

import (
	"bytes"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"mime/multipart"
	"net/http/httptest"
	"testing"
)

type profileBody struct {
	User struct {
		Timezone  string `json:"timezone"`
		Theme     string `json:"theme"`
		AvatarURL string `json:"avatar_url"`
	} `json:"user"`
}

func (e *env) uploadAvatar(token string, data []byte) *httptest.ResponseRecorder {
	e.t.Helper()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, _ := mw.CreateFormFile("file", "me.jpg")
	_, _ = fw.Write(data)
	_ = mw.Close()
	req := httptest.NewRequest("PUT", "/api/me/avatar", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	e.r.ServeHTTP(w, req)
	return w
}

func TestProfileTimezoneAndTheme(t *testing.T) {
	e := newEnv(t)
	adm, _ := e.admin()
	john := e.user(adm, "john")

	b := decode[profileBody](t, e.do("GET", "/api/me", nil, john))
	if b.User.Timezone != "" || b.User.Theme != "system" {
		t.Fatalf("по умолчанию: %+v", b.User)
	}
	w := e.do("PATCH", "/api/me", map[string]any{"timezone": "Europe/Rome", "theme": "light"}, john)
	if b = decode[profileBody](t, w); w.Code != 200 || b.User.Timezone != "Europe/Rome" || b.User.Theme != "light" {
		t.Fatalf("сохранение: %d %s", w.Code, w.Body)
	}
	for _, tz := range []string{"Mars/Olympus", "Local", "../../etc/passwd", "EST5EDT"} {
		w := e.do("PATCH", "/api/me", map[string]any{"timezone": tz}, john)
		if w.Code != 422 || decode[errBody](t, w).Error.Field != "timezone" {
			t.Fatalf("пояс %q: %d %s", tz, w.Code, w.Body)
		}
	}
	if w := e.do("PATCH", "/api/me", map[string]any{"theme": "neon"}, john); w.Code != 422 {
		t.Fatalf("тема: %d", w.Code)
	}
	// Пустой пояс — «как в браузере».
	if b := decode[profileBody](t, e.do("PATCH", "/api/me", map[string]any{"timezone": ""}, john)); b.User.Timezone != "" {
		t.Fatalf("сброс пояса: %+v", b.User)
	}
}

func TestProfileAvatar(t *testing.T) {
	e := newEnv(t)
	adm, _ := e.admin()
	john := e.user(adm, "john")

	src := image.NewRGBA(image.Rect(0, 0, 600, 400))
	for y := range 400 {
		for x := range 600 {
			src.Set(x, y, color.RGBA{R: uint8(x), G: 100, B: 200, A: 255})
		}
	}
	var jpg bytes.Buffer
	_ = jpeg.Encode(&jpg, src, nil)

	w := e.uploadAvatar(john, jpg.Bytes())
	b := decode[profileBody](t, w)
	if w.Code != 200 || b.User.AvatarURL == "" {
		t.Fatalf("загрузка: %d %s", w.Code, w.Body)
	}
	// Картинка отдаётся без входа, это наш PNG 256×256.
	pic := e.do("GET", b.User.AvatarURL, nil, "")
	img, err := png.Decode(pic.Body)
	if pic.Code != 200 || err != nil || img.Bounds().Dx() != 256 || img.Bounds().Dy() != 256 || pic.Header().Get("Content-Type") != "image/png" {
		t.Fatalf("картинка: %d %v %v", pic.Code, err, pic.Header())
	}
	if me := decode[profileBody](t, e.do("GET", "/api/me", nil, john)); me.User.AvatarURL != b.User.AvatarURL {
		t.Fatalf("адрес в профиле: %+v", me.User)
	}

	// Новая загрузка — новый адрес, старый больше не работает.
	w = e.uploadAvatar(john, jpg.Bytes())
	if nb := decode[profileBody](t, w); nb.User.AvatarURL == b.User.AvatarURL {
		t.Fatal("адрес не сменился")
	}
	if w := e.do("GET", b.User.AvatarURL, nil, ""); w.Code != 404 {
		t.Fatalf("старый адрес: %d", w.Code)
	}

	// Не картинка и слишком большая картинка отклоняются.
	if w := e.uploadAvatar(john, []byte("<svg onload=alert(1)>")); w.Code != 422 || decode[errBody](t, w).Error.Code != "avatar_format" {
		t.Fatalf("не картинка: %d %s", w.Code, w.Body)
	}
	var huge bytes.Buffer
	_ = png.Encode(&huge, image.NewGray(image.Rect(0, 0, 9000, 1)))
	if w := e.uploadAvatar(john, huge.Bytes()); w.Code != 422 {
		t.Fatalf("огромная картинка: %d %s", w.Code, w.Body)
	}

	if b := decode[profileBody](t, e.do("DELETE", "/api/me/avatar", nil, john)); b.User.AvatarURL != "" {
		t.Fatalf("удаление: %+v", b.User)
	}
}
