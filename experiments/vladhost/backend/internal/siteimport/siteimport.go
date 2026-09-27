// Package siteimport: импорт сайта с чужого адреса — архив по ссылке (zip, tar, tar.gz) или перенос по FTP со старого хостинга.
// Задача идёт в фоне. Всё полученное собирается в zip и выкладывается обычным деплоем (sites.Deploy), поэтому действуют
// те же проверки, что и при загрузке архива: пути, симлинки, квота, index в корне, атомарная подмена папки.
package siteimport

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	"gorm.io/gorm"

	"vladhost/internal/apperr"
	"vladhost/internal/sites"
)

const (
	KindURL = "url"
	KindFTP = "ftp"

	StatusRunning = "running"
	StatusDone    = "done"
	StatusFailed  = "failed"

	maxFiles       = 20000 // как у архива при загрузке
	defaultTimeout = 15 * time.Minute
	parallel       = 2 // одновременных импортов на всю панель: скачивание нагружает сеть и диск
)

var (
	ErrRunning     = apperr.New(http.StatusConflict, "import.running", "import is already running")
	ErrBadURL      = apperr.New(http.StatusUnprocessableEntity, "import.bad_url", "bad url").OnField("url")
	ErrBadHost     = apperr.New(http.StatusUnprocessableEntity, "import.bad_host", "bad ftp host").OnField("host")
	ErrBadKind     = apperr.New(http.StatusBadRequest, "bad_request", "unknown import kind")
	ErrPrivate     = apperr.New(http.StatusUnprocessableEntity, "import.private", "address is in an internal network")
	ErrHTTPStatus  = apperr.New(http.StatusBadGateway, "import.http_status", "remote server returned an error")
	ErrFormat      = apperr.New(http.StatusUnprocessableEntity, "import.format", "not a zip or tar archive")
	ErrConnect     = apperr.New(http.StatusBadGateway, "import.connect", "cannot connect")
	ErrFTPLogin    = apperr.New(http.StatusBadGateway, "import.ftp_login", "ftp login failed")
	ErrFTPPath     = apperr.New(http.StatusBadGateway, "import.ftp_path", "ftp folder not found")
	ErrTooMany     = apperr.New(http.StatusUnprocessableEntity, "archive.too_many", "too many files").With(maxFiles)
	ErrTimeout     = apperr.New(http.StatusGatewayTimeout, "import.timeout", "import took too long")
	ErrInterrupted = apperr.New(http.StatusInternalServerError, "import.interrupted", "panel restarted during import")
	errFailed      = apperr.New(http.StatusInternalServerError, "import.failed", "import failed")
)

// Job — задача импорта. Ошибка хранится кодом и подстановками: текст подбирается по языку того, кто смотрит.
type Job struct {
	ID         int64           `gorm:"primaryKey" json:"id"`
	UserID     int64           `json:"-"`
	SiteID     int64           `json:"-"`
	Kind       string          `json:"kind"`
	Source     string          `json:"source"`
	Status     string          `json:"status"`
	ErrorCode  string          `json:"-"`
	ErrorArgs  json.RawMessage `gorm:"type:jsonb" json:"-"`
	Bytes      int64           `json:"bytes"`
	Files      int             `json:"files"`
	CreatedAt  time.Time       `json:"created_at"`
	FinishedAt *time.Time      `json:"finished_at"`
}

func (Job) TableName() string { return "site_imports" }

// Err — ошибка задачи для перевода (nil, если её нет).
func (j *Job) Err() *apperr.Error {
	if j.ErrorCode == "" {
		return nil
	}
	var args []any
	_ = json.Unmarshal(j.ErrorArgs, &args)
	e := apperr.New(0, j.ErrorCode, j.ErrorCode)
	if len(args) > 0 {
		e = e.With(args...)
	}
	return e
}

// Source — откуда брать сайт. Для FTP пароль живёт только в памяти, пока идёт перенос.
type Source struct {
	Kind     string
	URL      string
	Host     string
	Port     int
	User     string
	Password string
	Path     string
	TLS      bool // FTPS (явный TLS, AUTH TLS)
}

type Config struct {
	AllowPrivate bool          // тесты: источник на 127.0.0.1
	Timeout      time.Duration // 0 — 15 минут
	TempDir      string        // "" — системный
}

type Service struct {
	db    *gorm.DB
	sites *sites.Service
	cfg   Config
	sem   chan struct{}
	wg    sync.WaitGroup
}

func New(db *gorm.DB, siteSvc *sites.Service, cfg Config) *Service {
	if cfg.Timeout == 0 {
		cfg.Timeout = defaultTimeout
	}
	return &Service{db: db, sites: siteSvc, cfg: cfg, sem: make(chan struct{}, parallel)}
}

// Enabled: nil-сервис значит «импорт выключен».
func (s *Service) Enabled() bool { return s != nil }

// Recover помечает задачи, прерванные перезапуском панели: фоновая работа не переживает процесс.
func (s *Service) Recover(ctx context.Context) error {
	args, _ := json.Marshal([]any{})
	return s.db.WithContext(ctx).Model(&Job{}).Where("status = ?", StatusRunning).
		Updates(map[string]any{"status": StatusFailed, "error_code": ErrInterrupted.Code, "error_args": args, "finished_at": time.Now()}).Error
}

// Latest — последняя задача сайта (nil, если импорта не было).
func (s *Service) Latest(ctx context.Context, userID, siteID int64) (*Job, error) {
	if _, err := s.sites.Get(ctx, userID, siteID); err != nil {
		return nil, err
	}
	var j Job
	err := s.db.WithContext(ctx).Where("site_id = ?", siteID).Order("id DESC").First(&j).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	return &j, err
}

// Start проверяет источник и запускает импорт в фоне.
func (s *Service) Start(ctx context.Context, userID, siteID int64, src Source) (*Job, error) {
	site, err := s.sites.Get(ctx, userID, siteID)
	if err != nil {
		return nil, err
	}
	var label string
	switch src.Kind {
	case KindURL:
		u, err := checkURL(src.URL, s.cfg.AllowPrivate)
		if err != nil {
			return nil, err
		}
		u.RawQuery, u.Fragment = "", "" // в ссылке может быть подписанный ключ доступа: в журнале и списке он не нужен
		label = u.String()
	case KindFTP:
		if err := checkFTP(&src, s.cfg.AllowPrivate); err != nil {
			return nil, err
		}
		label = fmt.Sprintf("ftp://%s@%s:%d%s", src.User, src.Host, src.Port, src.Path)
		if src.TLS {
			label = "ftps" + label[3:]
		}
	default:
		return nil, ErrBadKind
	}
	args, _ := json.Marshal([]any{})
	j := Job{UserID: userID, SiteID: site.ID, Kind: src.Kind, Source: label, Status: StatusRunning, ErrorArgs: args, CreatedAt: time.Now()}
	if err := s.db.WithContext(ctx).Create(&j).Error; err != nil {
		if errors.Is(err, gorm.ErrDuplicatedKey) || isUniqueViolation(err) {
			return nil, ErrRunning
		}
		return nil, err
	}
	s.wg.Go(func() { s.run(j, src) })
	return &j, nil
}

// Wait дожидается фоновых задач (тесты и остановка панели).
func (s *Service) Wait() { s.wg.Wait() }

func (s *Service) run(j Job, src Source) {
	s.sem <- struct{}{}
	defer func() { <-s.sem }()
	ctx, cancel := context.WithTimeout(context.Background(), s.cfg.Timeout)
	defer cancel()

	st, err := s.fetch(ctx, j.UserID, src)
	if err == nil {
		err = s.deploy(ctx, j, st.zip)
	}
	if st.zip != "" {
		_ = os.Remove(st.zip)
	}
	j.Bytes, j.Files = st.bytes, st.files
	now := time.Now()
	upd := map[string]any{"status": StatusDone, "bytes": j.Bytes, "files": j.Files, "finished_at": now}
	if err != nil {
		if ctx.Err() != nil {
			err = ErrTimeout
		}
		ae, ok := errors.AsType[*apperr.Error](err)
		if !ok {
			log.Printf("импорт %d (%s): %v", j.ID, j.Source, err)
			ae = errFailed
		}
		args, _ := json.Marshal(ae.Args)
		if ae.Args == nil {
			args = []byte("[]")
		}
		upd["status"], upd["error_code"], upd["error_args"] = StatusFailed, ae.Code, args
	}
	if err := s.db.Model(&Job{ID: j.ID}).Updates(upd).Error; err != nil {
		log.Printf("импорт %d: запись результата: %v", j.ID, err)
	}
}

// fetched — собранный zip и сколько получено.
type fetched struct {
	zip   string
	bytes int64
	files int
}

func (s *Service) fetch(ctx context.Context, userID int64, src Source) (fetched, error) {
	limit := s.sites.LimitsFor(ctx, userID).DiskQuotaBytes
	if src.Kind == KindURL {
		return s.fetchURL(ctx, src.URL, limit)
	}
	return s.fetchFTP(ctx, src, limit)
}

func (s *Service) deploy(ctx context.Context, j Job, zipPath string) error {
	f, err := os.Open(zipPath)
	if err != nil {
		return err
	}
	defer func() { _ = f.Close() }()
	fi, err := f.Stat()
	if err != nil {
		return err
	}
	_, err = s.sites.Deploy(ctx, j.UserID, j.SiteID, f, fi.Size())
	return err
}

func (s *Service) tempFile(pattern string) (*os.File, error) {
	return os.CreateTemp(s.cfg.TempDir, pattern)
}
