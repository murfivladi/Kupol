// Package uptime: мониторинг доступности сайтов. Панель периодически открывает сайт по https, пишет результат,
// считает аптайм и сбои и сообщает владельцу о падении и восстановлении.
//
// Проверка идёт с того же сервера, что и сайт: она ловит упавшее приложение, ошибки 5xx, истёкший сертификат
// и сломанную настройку, но не сбой сети дата-центра — об этом честно сказано в справке.
package uptime

import (
	"context"
	"crypto/tls"
	"errors"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"gorm.io/gorm"
	"gorm.io/gorm/clause"

	"vladhost/internal/apperr"
	"vladhost/internal/sites"
)

const (
	StateUnknown = "unknown"
	StateUp      = "up"
	StateDown    = "down"

	// Коды ошибок проверки (поле error). Текст подбирается по языку: ключ "uptime.reason."+код.
	ErrHTTP    = "http"
	ErrTimeout = "timeout"
	ErrTLS     = "tls"
	ErrConnect = "connect"
	ErrDNS     = "dns"

	defaultInterval = 2 * time.Minute
	defaultTimeout  = 15 * time.Second
	downAfter       = 2 // столько неудачных проверок подряд — сбой (одна может быть случайностью)
	parallel        = 8
	checksKeep      = 30 * 24 * time.Hour
	incidentsKeep   = 90 * 24 * time.Hour
	pathMax         = 300
)

var ErrBadPath = apperr.New(http.StatusUnprocessableEntity, "uptime.bad_path", "bad path").OnField("path")

// Monitor — настройки и текущее состояние мониторинга сайта.
type Monitor struct {
	SiteID        int64      `gorm:"primaryKey" json:"-"`
	UserID        int64      `json:"-"`
	Enabled       bool       `json:"enabled"`
	Path          string     `json:"path"`
	Notify        bool       `json:"notify"`
	Public        bool       `json:"public"`
	State         string     `json:"state"`
	StateSince    *time.Time `json:"state_since"`
	FailStreak    int        `json:"-"`
	LastCheckedAt *time.Time `json:"last_checked_at"`
	LastCode      int        `json:"last_code"`
	LastMs        int        `json:"last_ms"`
	LastError     string     `json:"last_error"`
	CreatedAt     time.Time  `json:"created_at"`
}

func (Monitor) TableName() string { return "site_monitors" }

type Check struct {
	ID        int64     `gorm:"primaryKey" json:"-"`
	SiteID    int64     `json:"-"`
	CheckedAt time.Time `json:"at"`
	OK        bool      `gorm:"column:ok" json:"ok"`
	Code      int       `json:"code"`
	Ms        int       `json:"ms"`
	Error     string    `json:"error"`
}

func (Check) TableName() string { return "site_checks" }

type Incident struct {
	ID        int64      `gorm:"primaryKey" json:"id"`
	SiteID    int64      `json:"-"`
	StartedAt time.Time  `json:"started_at"`
	EndedAt   *time.Time `json:"ended_at"`
	Error     string     `json:"error"`
}

func (Incident) TableName() string { return "site_incidents" }

// Event — смена состояния, о которой нужно сообщить владельцу.
type Event struct {
	UserID     int64
	SiteID     int64
	Host       string
	Down       bool // true — сайт упал, false — восстановился
	Error      string
	Code       int
	IncidentID int64
	Since      time.Time
}

type Config struct {
	Interval time.Duration // 0 — 2 минуты
	Timeout  time.Duration // 0 — 15 секунд
	// URL строит адрес проверки; nil — https://хост/путь. Тесты подставляют свой сервер.
	URL    func(host, path string) string
	Notify func(ctx context.Context, e Event) // nil — письма не отправляются
}

type Service struct {
	db     *gorm.DB
	sites  *sites.Service
	cfg    Config
	client *http.Client
	now    func() time.Time
}

func New(db *gorm.DB, siteSvc *sites.Service, cfg Config) *Service {
	if cfg.Interval == 0 {
		cfg.Interval = defaultInterval
	}
	if cfg.Timeout == 0 {
		cfg.Timeout = defaultTimeout
	}
	if cfg.URL == nil {
		cfg.URL = func(host, path string) string { return "https://" + host + path }
	}
	client := &http.Client{
		Timeout: cfg.Timeout,
		// Редирект — нормальный ответ сайта (например, на /ru/ или на www): считаем по первому ответу.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		Transport: &http.Transport{Proxy: nil, DisableKeepAlives: true, TLSHandshakeTimeout: cfg.Timeout,
			DialContext: (&net.Dialer{Timeout: 10 * time.Second}).DialContext},
	}
	return &Service{db: db, sites: siteSvc, cfg: cfg, client: client, now: time.Now}
}

func (s *Service) Enabled() bool { return s != nil }

// Interval — как часто проверяется сайт (для подписи в интерфейсе).
func (s *Service) Interval() time.Duration { return s.cfg.Interval }

// Get — мониторинг сайта (nil — не настроен).
func (s *Service) Get(ctx context.Context, userID, siteID int64) (*Monitor, *sites.Site, error) {
	site, err := s.sites.Get(ctx, userID, siteID)
	if err != nil {
		return nil, nil, err
	}
	var m Monitor
	err = s.db.WithContext(ctx).Where("site_id = ?", site.ID).First(&m).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, site, nil
	}
	return &m, site, err
}

type Settings struct {
	Enabled bool
	Path    string
	Notify  bool
	Public  bool
}

func cleanPath(p string) (string, error) {
	p = strings.TrimSpace(p)
	if p == "" {
		return "/", nil
	}
	if !strings.HasPrefix(p, "/") || len(p) > pathMax || strings.ContainsAny(p, " \t\r\n#") || strings.HasPrefix(p, "//") {
		return "", ErrBadPath
	}
	return p, nil
}

// Save создаёт или меняет мониторинг. Включение и смена адреса проверки сбрасывают состояние: следующая проверка — сразу.
func (s *Service) Save(ctx context.Context, userID, siteID int64, in Settings) (*Monitor, error) {
	path, err := cleanPath(in.Path)
	if err != nil {
		return nil, err
	}
	old, site, err := s.Get(ctx, userID, siteID)
	if err != nil {
		return nil, err
	}
	m := Monitor{SiteID: site.ID, UserID: userID, State: StateUnknown, CreatedAt: s.now()}
	if old != nil {
		m = *old
	}
	reset := old == nil || (in.Enabled && !old.Enabled) || path != old.Path
	m.Enabled, m.Path, m.Notify, m.Public = in.Enabled, path, in.Notify, in.Public
	err = s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if reset {
			// незакрытый сбой по старому адресу закрываем: дальше считаем заново
			if err := tx.Model(&Incident{}).Where("site_id = ? AND ended_at IS NULL", site.ID).Update("ended_at", s.now()).Error; err != nil {
				return err
			}
			m.State, m.StateSince, m.FailStreak, m.LastCheckedAt = StateUnknown, nil, 0, nil
		}
		if !m.Enabled {
			m.State, m.StateSince, m.FailStreak = StateUnknown, nil, 0
			if err := tx.Model(&Incident{}).Where("site_id = ? AND ended_at IS NULL", site.ID).Update("ended_at", s.now()).Error; err != nil {
				return err
			}
		}
		return tx.Clauses(clause.OnConflict{UpdateAll: true}).Create(&m).Error
	})
	if err != nil {
		return nil, err
	}
	return &m, nil
}

// Run проверяет сайты, у которых подошёл срок, пока не отменён ctx. Раз в час чистит старые проверки.
func (s *Service) Run(ctx context.Context, tick time.Duration) {
	t := time.NewTicker(tick)
	defer t.Stop()
	lastClean := time.Time{}
	for {
		if err := s.CheckDue(ctx); err != nil && ctx.Err() == nil {
			log.Printf("мониторинг: %v", err)
		}
		if time.Since(lastClean) > time.Hour {
			s.cleanup(ctx)
			lastClean = time.Now()
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

type dueRow struct {
	Monitor
	Host string
}

// CheckDue проверяет все включённые мониторинги, у которых подошёл срок.
func (s *Service) CheckDue(ctx context.Context) error {
	var due []dueRow
	err := s.db.WithContext(ctx).Table("site_monitors m").Select("m.*, s.host").Joins("JOIN sites s ON s.id = m.site_id").
		Where("m.enabled AND (m.last_checked_at IS NULL OR m.last_checked_at <= ?)", s.now().Add(-s.cfg.Interval+time.Second)).
		Order("m.last_checked_at NULLS FIRST").Limit(500).Scan(&due).Error
	if err != nil {
		return err
	}
	sem := make(chan struct{}, parallel)
	var wg sync.WaitGroup
	for _, d := range due {
		sem <- struct{}{}
		wg.Go(func() {
			defer func() { <-sem }()
			if err := s.checkOne(ctx, d.Monitor, d.Host); err != nil && ctx.Err() == nil {
				log.Printf("мониторинг %s: %v", d.Host, err)
			}
		})
	}
	wg.Wait()
	return nil
}

// probe открывает страницу и классифицирует результат.
func (s *Service) probe(ctx context.Context, host, path string) Check {
	start := s.now()
	c := Check{CheckedAt: start}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.cfg.URL(host, path), nil)
	if err != nil {
		c.Error = ErrConnect
		return c
	}
	req.Header.Set("User-Agent", "Vladhost-Uptime/1.0")
	resp, err := s.client.Do(req)
	c.Ms = int(time.Since(start).Milliseconds())
	if err != nil {
		c.Error = classify(err)
		return c
	}
	_ = resp.Body.Close()
	c.Code = resp.StatusCode
	c.OK = resp.StatusCode < 400
	if !c.OK {
		c.Error = ErrHTTP
	}
	return c
}

func classify(err error) string {
	var dnsErr *net.DNSError
	var certErr *tls.CertificateVerificationError
	var netErr net.Error
	switch {
	case errors.As(err, &dnsErr):
		return ErrDNS
	case errors.As(err, &certErr) || strings.Contains(err.Error(), "tls:") || strings.Contains(err.Error(), "x509:"):
		return ErrTLS
	case errors.As(err, &netErr) && netErr.Timeout():
		return ErrTimeout
	}
	return ErrConnect
}

// checkOne проверяет сайт и переводит состояние: up ↔ down с открытием и закрытием сбоя.
func (s *Service) checkOne(ctx context.Context, m Monitor, host string) error {
	c := s.probe(ctx, host, m.Path)
	c.SiteID = m.SiteID
	now := c.CheckedAt
	var ev *Event
	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		// Настройки могли поменяться, пока шла проверка: берём свежую строку под блокировкой.
		var cur Monitor
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("site_id = ?", m.SiteID).First(&cur).Error; err != nil {
			return err
		}
		if !cur.Enabled || cur.Path != m.Path {
			return nil
		}
		if err := tx.Create(&c).Error; err != nil {
			return err
		}
		upd := map[string]any{"last_checked_at": now, "last_code": c.Code, "last_ms": c.Ms, "last_error": c.Error}
		if c.OK {
			upd["fail_streak"] = 0
			if cur.State != StateUp {
				upd["state"], upd["state_since"] = StateUp, now
				if cur.State == StateDown {
					var inc Incident
					if err := tx.Where("site_id = ? AND ended_at IS NULL", m.SiteID).Order("id DESC").First(&inc).Error; err == nil {
						if err := tx.Model(&inc).Update("ended_at", now).Error; err != nil {
							return err
						}
						ev = &Event{UserID: cur.UserID, SiteID: m.SiteID, Host: host, Down: false, IncidentID: inc.ID, Since: inc.StartedAt}
					}
				}
			}
		} else {
			streak := cur.FailStreak + 1
			upd["fail_streak"] = streak
			if streak >= downAfter && cur.State != StateDown {
				inc := Incident{SiteID: m.SiteID, StartedAt: now, Error: c.Error}
				if err := tx.Create(&inc).Error; err != nil {
					return err
				}
				upd["state"], upd["state_since"] = StateDown, now
				ev = &Event{UserID: cur.UserID, SiteID: m.SiteID, Host: host, Down: true, Error: c.Error, Code: c.Code, IncidentID: inc.ID, Since: now}
			}
		}
		if ev != nil && !cur.Notify {
			ev = nil
		}
		return tx.Model(&Monitor{}).Where("site_id = ?", m.SiteID).Updates(upd).Error
	})
	if err == nil && ev != nil && s.cfg.Notify != nil {
		s.cfg.Notify(ctx, *ev)
	}
	return err
}

func (s *Service) cleanup(ctx context.Context) {
	now := s.now()
	if err := s.db.WithContext(ctx).Where("checked_at < ?", now.Add(-checksKeep)).Delete(&Check{}).Error; err != nil {
		log.Printf("мониторинг: чистка проверок: %v", err)
	}
	if err := s.db.WithContext(ctx).Where("ended_at IS NOT NULL AND ended_at < ?", now.Add(-incidentsKeep)).Delete(&Incident{}).Error; err != nil {
		log.Printf("мониторинг: чистка сбоев: %v", err)
	}
}
