package admin

import (
	"context"
	"net/http"
	"net/mail"
	"net/url"
	"strings"
	"time"
	"unicode/utf8"

	"gorm.io/gorm"

	"vladhost/internal/apperr"
	"vladhost/internal/auth"
	"vladhost/internal/sites"
)

// Жалобы на содержимое сайтов: принимаются без входа (форма /abuse), разбираются администратором.
const (
	AbuseNew      = "new"
	AbuseResolved = "resolved"
	AbuseRejected = "rejected"

	abuseMessageMax = 5000
	abuseNoteMax    = 1000
	abusePerIPDay   = 10 // жалоб с одного адреса за сутки: форма открытая, без этого её завалят
)

var abuseCategories = map[string]bool{"phishing": true, "malware": true, "spam": true, "copyright": true, "illegal": true, "other": true}

var (
	ErrAbuseURL      = apperr.New(http.StatusUnprocessableEntity, "abuse_url", "bad url").OnField("url")
	ErrAbuseCategory = apperr.New(http.StatusUnprocessableEntity, "bad_request", "bad category").OnField("category")
	ErrAbuseMessage  = apperr.New(http.StatusUnprocessableEntity, "abuse_message", "describe the problem").With(abuseMessageMax).OnField("message")
	ErrAbuseEmail    = apperr.New(http.StatusUnprocessableEntity, "abuse_email", "bad email").OnField("email")
	ErrAbuseTooMany  = apperr.New(http.StatusTooManyRequests, "abuse_too_many", "too many reports")
	ErrAbuseNotFound = apperr.New(http.StatusNotFound, "not_found", "report not found")
	ErrAbuseStatus   = apperr.New(http.StatusBadRequest, "bad_request", "bad status")
)

type AbuseReport struct {
	ID         int64      `gorm:"primaryKey" json:"id"`
	URL        string     `gorm:"column:url" json:"url"`
	Host       string     `json:"host"`
	SiteID     *int64     `json:"site_id"`
	Category   string     `json:"category"`
	Message    string     `json:"message"`
	Email      string     `json:"email"`
	IP         string     `gorm:"column:ip" json:"ip"`
	Status     string     `json:"status"`
	Note       string     `json:"note"`
	ResolvedBy *int64     `json:"resolved_by"`
	ResolvedAt *time.Time `json:"resolved_at"`
	CreatedAt  time.Time  `json:"created_at"`
	// Для списка: сайт и владелец (если адрес наш).
	SiteHost        string     `gorm:"->" json:"site_host"`
	SiteSuspendedAt *time.Time `gorm:"->" json:"site_suspended_at"`
	OwnerID         *int64     `gorm:"->" json:"owner_id"`
	OwnerName       string     `gorm:"->" json:"owner_name"`
}

func (AbuseReport) TableName() string { return "abuse_reports" }

type AbuseInput struct {
	URL      string
	Category string
	Message  string
	Email    string
	IP       string
}

// normalizeReportURL: принимаем и «example.com/page», и полный адрес; храним с схемой, хост — в нижнем регистре.
func normalizeReportURL(raw string) (*url.URL, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" || len(raw) > 2000 {
		return nil, ErrAbuseURL
	}
	if !strings.Contains(raw, "://") {
		raw = "https://" + raw
	}
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" || !strings.Contains(u.Hostname(), ".") {
		return nil, ErrAbuseURL
	}
	u.Host = strings.ToLower(u.Host)
	u.User = nil
	return u, nil
}

// ReportAbuse принимает жалобу. Возвращает её и признак, что адрес — наш сайт (тогда стоит сообщить администраторам).
func (s *Service) ReportAbuse(ctx context.Context, in AbuseInput) (*AbuseReport, error) {
	u, err := normalizeReportURL(in.URL)
	if err != nil {
		return nil, err
	}
	if !abuseCategories[in.Category] {
		return nil, ErrAbuseCategory
	}
	msg := strings.TrimSpace(in.Message)
	if utf8.RuneCountInString(msg) < 10 || utf8.RuneCountInString(msg) > abuseMessageMax {
		return nil, ErrAbuseMessage
	}
	email := strings.TrimSpace(in.Email)
	if email != "" {
		if a, err := mail.ParseAddress(email); err != nil || a.Address != email || len(email) > 254 {
			return nil, ErrAbuseEmail
		}
	}
	ip := in.IP
	if len(ip) > 45 {
		ip = ip[:45]
	}
	var recent int64
	if err := s.db.WithContext(ctx).Model(&AbuseReport{}).Where("ip = ? AND created_at > ?", ip, s.now().Add(-24*time.Hour)).Count(&recent).Error; err != nil {
		return nil, err
	}
	if recent >= abusePerIPDay {
		return nil, ErrAbuseTooMany
	}
	host := u.Hostname()
	r := AbuseReport{URL: u.String(), Host: host, SiteID: s.siteForHost(ctx, host), Category: in.Category, Message: msg, Email: email, IP: ip, Status: AbuseNew, CreatedAt: s.now()}
	if err := s.db.WithContext(ctx).Omit("SiteHost", "SiteSuspendedAt", "OwnerID", "OwnerName").Create(&r).Error; err != nil {
		return nil, err
	}
	return &r, nil
}

// siteForHost находит наш сайт по адресу: основной адрес, его поддомен или подключённый домен (с www и без).
func (s *Service) siteForHost(ctx context.Context, host string) *int64 {
	var id int64
	s.db.WithContext(ctx).Table("sites").Select("id").Where("? = host OR ? LIKE '%.' || host", host, host).Order("length(host) DESC").Limit(1).Scan(&id)
	if id == 0 {
		s.db.WithContext(ctx).Table("domains").Select("site_id").Where("host IN ?", []string{host, strings.TrimPrefix(host, "www.")}).Limit(1).Scan(&id)
	}
	if id == 0 {
		return nil
	}
	return &id
}

func (s *Service) abuseQuery(ctx context.Context) *gorm.DB {
	return s.db.WithContext(ctx).Table("abuse_reports r").
		Select("r.*, s.host AS site_host, s.suspended_at AS site_suspended_at, u.id AS owner_id, COALESCE(u.username, '') AS owner_name").
		Joins("LEFT JOIN sites s ON s.id = r.site_id").Joins("LEFT JOIN users u ON u.id = s.user_id")
}

// AbuseReports — очередь жалоб; status "" — все.
func (s *Service) AbuseReports(ctx context.Context, status string, offset int) ([]AbuseReport, int64, error) {
	q := s.abuseQuery(ctx)
	if status != "" {
		q = q.Where("r.status = ?", status)
	}
	var total int64
	if err := q.Count(&total).Error; err != nil {
		return nil, 0, err
	}
	out := []AbuseReport{}
	err := q.Order("r.id DESC").Offset(max(offset, 0)).Limit(pageSize).Scan(&out).Error
	return out, total, err
}

// NewAbuseCount — сколько жалоб ждут разбора (значок в меню).
func (s *Service) NewAbuseCount(ctx context.Context) (int64, error) {
	var n int64
	err := s.db.WithContext(ctx).Model(&AbuseReport{}).Where("status = ?", AbuseNew).Count(&n).Error
	return n, err
}

func (s *Service) abuseReport(ctx context.Context, id int64) (*AbuseReport, error) {
	var r AbuseReport
	if err := s.abuseQuery(ctx).Where("r.id = ?", id).Scan(&r).Error; err != nil {
		return nil, err
	}
	if r.ID == 0 {
		return nil, ErrAbuseNotFound
	}
	return &r, nil
}

// SetAbuseStatus закрывает жалобу (resolved — меры приняты, rejected — нарушения нет) или возвращает её в работу (new).
func (s *Service) SetAbuseStatus(ctx context.Context, adminID, id int64, status, note string) (*AbuseReport, error) {
	if status != AbuseNew && status != AbuseResolved && status != AbuseRejected {
		return nil, ErrAbuseStatus
	}
	note = strings.TrimSpace(note)
	if utf8.RuneCountInString(note) > abuseNoteMax {
		note = string([]rune(note)[:abuseNoteMax])
	}
	upd := map[string]any{"status": status, "note": note, "resolved_by": adminID, "resolved_at": s.now()}
	if status == AbuseNew {
		upd["resolved_by"], upd["resolved_at"] = nil, nil
	}
	res := s.db.WithContext(ctx).Model(&AbuseReport{}).Where("id = ?", id).Updates(upd)
	if res.Error != nil {
		return nil, res.Error
	}
	if res.RowsAffected == 0 {
		return nil, ErrAbuseNotFound
	}
	return s.abuseReport(ctx, id)
}

// SuspendSite приостанавливает сайт (по жалобе или вручную). Причину видит владелец в панели.
func (s *Service) SuspendSite(ctx context.Context, siteID int64, reason string) (*sites.Site, error) {
	reason = strings.TrimSpace(reason)
	if reason == "" || utf8.RuneCountInString(reason) > 500 {
		return nil, auth.ErrBlockReason
	}
	return s.sites.Suspend(ctx, siteID, reason, false)
}

func (s *Service) UnsuspendSite(ctx context.Context, siteID int64) (*sites.Site, error) {
	return s.sites.Unsuspend(ctx, siteID)
}
