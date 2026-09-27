package uptime_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"vladhost/internal/auth"
	"vladhost/internal/sites"
	"vladhost/internal/testdb"
	"vladhost/internal/uptime"
)

type fixture struct {
	svc    *uptime.Service
	site   *sites.Site
	user   *auth.User
	status atomic.Int32
	path   atomic.Value
	mu     sync.Mutex
	events []uptime.Event
}

func setup(t *testing.T) *fixture {
	t.Helper()
	db := testdb.Open(t)
	siteSvc := sites.NewService(db, t.TempDir(), "vladinc.ru", "", sites.Limits{MaxSites: 3, DiskQuotaBytes: 1 << 20})
	u, err := auth.NewService(db, []byte(strings.Repeat("s", 32)), time.Minute, time.Hour).CreateAdmin(context.Background(), "john@example.com", "john", "password123")
	if err != nil {
		t.Fatal(err)
	}
	site, err := siteSvc.Create(context.Background(), *u, "blog")
	if err != nil {
		t.Fatal(err)
	}
	f := &fixture{site: site, user: u}
	f.status.Store(200)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.path.Store(r.Host + r.URL.Path)
		w.WriteHeader(int(f.status.Load()))
	}))
	t.Cleanup(srv.Close)
	f.svc = uptime.New(db, siteSvc, uptime.Config{
		Interval: time.Nanosecond, // каждая проверка «по сроку»
		URL:      func(_, path string) string { return srv.URL + path },
		Notify: func(_ context.Context, e uptime.Event) {
			f.mu.Lock()
			f.events = append(f.events, e)
			f.mu.Unlock()
		},
	})
	return f
}

func (f *fixture) tick(t *testing.T) *uptime.Monitor {
	t.Helper()
	if err := f.svc.CheckDue(context.Background()); err != nil {
		t.Fatal(err)
	}
	m, _, err := f.svc.Get(context.Background(), f.user.ID, f.site.ID)
	if err != nil {
		t.Fatal(err)
	}
	return m
}

func TestMonitorUpDownUp(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	if _, err := f.svc.Save(ctx, f.user.ID, f.site.ID, uptime.Settings{Enabled: true, Path: "/health", Notify: true}); err != nil {
		t.Fatal(err)
	}
	if m := f.tick(t); m.State != uptime.StateUp || m.LastCode != 200 || !strings.HasSuffix(f.path.Load().(string), "/health") {
		t.Fatalf("первая проверка: %+v", m)
	}

	// Одна неудача — ещё не сбой, вторая подряд — сбой и письмо.
	f.status.Store(503)
	if m := f.tick(t); m.State != uptime.StateUp || m.LastError != uptime.ErrHTTP {
		t.Fatalf("одна ошибка: %+v", m)
	}
	if m := f.tick(t); m.State != uptime.StateDown {
		t.Fatalf("две ошибки: %+v", m)
	}
	f.tick(t) // сбой продолжается: второго письма нет
	if len(f.events) != 1 || !f.events[0].Down || f.events[0].Code != 503 || f.events[0].SiteID != f.site.ID {
		t.Fatalf("письма о падении: %+v", f.events)
	}

	f.status.Store(301) // редирект — сайт отвечает
	if m := f.tick(t); m.State != uptime.StateUp {
		t.Fatalf("восстановление: %+v", m)
	}
	if len(f.events) != 2 || f.events[1].Down || f.events[1].IncidentID != f.events[0].IncidentID {
		t.Fatalf("письмо о восстановлении: %+v", f.events)
	}

	rep, err := f.svc.Report(ctx, f.site.ID)
	if err != nil {
		t.Fatal(err)
	}
	if rep.Uptime.Day == nil || *rep.Uptime.Day != 40 || len(rep.Days) != 30 || len(rep.Hours) != 24 || rep.Hours[23].Checks != 5 {
		t.Fatalf("сводка: %+v day=%v", rep, rep.Uptime.Day)
	}
	if len(rep.Incidents) != 1 || rep.Incidents[0].EndedAt == nil || rep.Incidents[0].Error != uptime.ErrHTTP {
		t.Fatalf("сбои: %+v", rep.Incidents)
	}
}

func TestMonitorSettings(t *testing.T) {
	f := setup(t)
	ctx := context.Background()
	for _, p := range []string{"health", "/a b", "//evil.com", "/" + strings.Repeat("a", 300)} {
		if _, err := f.svc.Save(ctx, f.user.ID, f.site.ID, uptime.Settings{Enabled: true, Path: p}); !errors.Is(err, uptime.ErrBadPath) {
			t.Fatalf("путь %q: %v", p, err)
		}
	}
	if _, err := f.svc.Save(ctx, f.user.ID+1, f.site.ID, uptime.Settings{Enabled: true}); !errors.Is(err, sites.ErrNotFound) {
		t.Fatalf("чужой сайт: %v", err)
	}

	// Без уведомлений сбой фиксируется, но письма нет; выключенный мониторинг не проверяется.
	if _, err := f.svc.Save(ctx, f.user.ID, f.site.ID, uptime.Settings{Enabled: true, Path: ""}); err != nil {
		t.Fatal(err)
	}
	f.status.Store(500)
	f.tick(t)
	if m := f.tick(t); m.State != uptime.StateDown || m.Path != "/" || len(f.events) != 0 {
		t.Fatalf("без писем: %+v %v", m, f.events)
	}
	off, err := f.svc.Save(ctx, f.user.ID, f.site.ID, uptime.Settings{Enabled: false})
	if err != nil {
		t.Fatal(err)
	}
	if m := f.tick(t); m.State != uptime.StateUnknown || !m.LastCheckedAt.Equal(*off.LastCheckedAt) {
		t.Fatalf("выключен: %+v", m)
	}

	// Статус-страница — только если её включили.
	if _, err := f.svc.PublicStatus(ctx, f.site.Host); !errors.Is(err, sites.ErrNotFound) {
		t.Fatalf("закрытая страница: %v", err)
	}
	if _, err := f.svc.Save(ctx, f.user.ID, f.site.ID, uptime.Settings{Enabled: true, Public: true}); err != nil {
		t.Fatal(err)
	}
	f.status.Store(200)
	f.tick(t)
	st, err := f.svc.PublicStatus(ctx, f.site.Host)
	if err != nil || st.State != uptime.StateUp || st.Host != f.site.Host {
		t.Fatalf("открытая страница: %+v %v", st, err)
	}
}
