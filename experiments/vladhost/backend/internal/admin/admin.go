// Package admin: раздел администратора — пользователи (сводка, лимиты, блокировка) и жалобы на содержимое сайтов.
// Здесь собирается то, что затрагивает сразу несколько частей панели: блокировка аккаунта закрывает сессии (auth)
// и приостанавливает сайты (sites).
package admin

import (
	"context"
	"net/http"
	"strings"
	"time"

	"gorm.io/gorm"

	"vladhost/internal/apperr"
	"vladhost/internal/auth"
	"vladhost/internal/sites"
)

const (
	pageSize     = 50
	maxSitesCap  = 100
	maxQuotaCap  = 100 << 30 // 100 ГБ
	suspendByAdm = "blocked"
)

var ErrLimits = apperr.New(http.StatusUnprocessableEntity, "admin_limits", "bad limits").With(maxSitesCap, maxQuotaCap>>30)

type Service struct {
	db    *gorm.DB
	auth  *auth.Service
	sites *sites.Service
	now   func() time.Time
}

func New(db *gorm.DB, authSvc *auth.Service, siteSvc *sites.Service) *Service {
	return &Service{db: db, auth: authSvc, sites: siteSvc, now: time.Now}
}

// UserRow — строка списка пользователей: аккаунт и сводка по его сайтам и входам.
type UserRow struct {
	auth.User
	Sites     int        `json:"sites"`
	DiskBytes int64      `json:"disk_bytes"`
	LastSeen  *time.Time `json:"last_seen_at"`
}

type Filter struct {
	Query  string // email или имя (подстрока)
	Status string // "" | blocked | admin
	Offset int
}

// Users — пользователи для администратора, новые сверху.
func (s *Service) Users(ctx context.Context, f Filter) ([]UserRow, int64, error) {
	q := s.db.WithContext(ctx).Table("users u")
	if t := strings.ToLower(strings.TrimSpace(f.Query)); t != "" {
		like := "%" + strings.NewReplacer("%", `\%`, "_", `\_`).Replace(t) + "%"
		q = q.Where("u.email LIKE ? OR u.username LIKE ?", like, like)
	}
	switch f.Status {
	case "blocked":
		q = q.Where("u.blocked_at IS NOT NULL")
	case "admin":
		q = q.Where("u.role = ?", auth.RoleAdmin)
	}
	var total int64
	if err := q.Count(&total).Error; err != nil {
		return nil, 0, err
	}
	rows := []UserRow{}
	err := q.Select(`u.*,
		(SELECT count(*) FROM sites s WHERE s.user_id = u.id) AS sites,
		(SELECT COALESCE(sum(s.disk_bytes), 0) FROM sites s WHERE s.user_id = u.id) AS disk_bytes,
		(SELECT max(se.last_seen_at) FROM sessions se WHERE se.user_id = u.id) AS last_seen`).
		Order("u.id DESC").Offset(max(f.Offset, 0)).Limit(pageSize).Scan(&rows).Error
	if err != nil {
		return nil, 0, err
	}
	for i := range rows {
		rows[i].FillAvatarURL() // Scan не вызывает хуки модели
	}
	return rows, total, nil
}

// Detail — пользователь, его сайты и действующие лимиты.
type Detail struct {
	User     UserRow      `json:"user"`
	Sites    []sites.Site `json:"sites"`
	Limits   sites.Limits `json:"limits"`
	Defaults sites.Limits `json:"defaults"`
}

func (s *Service) Detail(ctx context.Context, userID int64) (*Detail, error) {
	var row UserRow
	err := s.db.WithContext(ctx).Table("users u").Select(`u.*,
		(SELECT count(*) FROM sites s WHERE s.user_id = u.id) AS sites,
		(SELECT COALESCE(sum(s.disk_bytes), 0) FROM sites s WHERE s.user_id = u.id) AS disk_bytes,
		(SELECT max(se.last_seen_at) FROM sessions se WHERE se.user_id = u.id) AS last_seen`).
		Where("u.id = ?", userID).Scan(&row).Error
	if err != nil {
		return nil, err
	}
	if row.ID == 0 {
		return nil, auth.ErrUserNotFound
	}
	row.FillAvatarURL()
	list, err := s.sites.List(ctx, userID)
	if err != nil {
		return nil, err
	}
	if list == nil {
		list = []sites.Site{}
	}
	return &Detail{User: row, Sites: list, Limits: s.sites.LimitsFor(ctx, userID), Defaults: s.sites.Limits()}, nil
}

// Block блокирует аккаунт и приостанавливает его сайты.
func (s *Service) Block(ctx context.Context, userID int64, reason string) (*auth.User, error) {
	u, err := s.auth.Block(ctx, userID, reason)
	if err != nil {
		return nil, err
	}
	if err := s.sites.SuspendUser(ctx, userID, suspendByAdm); err != nil {
		return nil, err
	}
	return u, nil
}

// Unblock снимает блокировку и возвращает сайты, приостановленные вместе с ней.
func (s *Service) Unblock(ctx context.Context, userID int64) (*auth.User, error) {
	u, err := s.auth.Unblock(ctx, userID)
	if err != nil {
		return nil, err
	}
	if err := s.sites.ResumeUser(ctx, userID); err != nil {
		return nil, err
	}
	return u, nil
}

// SetLimits задаёт личные лимиты; nil — общий лимит панели.
func (s *Service) SetLimits(ctx context.Context, userID int64, maxSites *int, quota *int64) (*auth.User, error) {
	if (maxSites != nil && (*maxSites < 0 || *maxSites > maxSitesCap)) || (quota != nil && (*quota < 0 || *quota > maxQuotaCap)) {
		return nil, ErrLimits
	}
	res := s.db.WithContext(ctx).Model(&auth.User{}).Where("id = ?", userID).Updates(map[string]any{"max_sites": maxSites, "disk_quota_bytes": quota})
	if res.Error != nil {
		return nil, res.Error
	}
	if res.RowsAffected == 0 {
		return nil, auth.ErrUserNotFound
	}
	return s.auth.UserByID(ctx, userID)
}

// ResetTwoFactor снимает второй фактор (пользователь потерял телефон и коды).
func (s *Service) ResetTwoFactor(ctx context.Context, userID int64) (*auth.User, error) {
	u, err := s.auth.UserByID(ctx, userID)
	if err != nil {
		return nil, auth.ErrUserNotFound
	}
	return s.auth.ResetTwoFactor(ctx, u.Username)
}

// SiteOwner — владелец сайта (для журнала действий администратора).
func (s *Service) SiteOwner(ctx context.Context, siteID int64) (int64, string, error) {
	var site sites.Site
	if err := s.db.WithContext(ctx).First(&site, siteID).Error; err != nil {
		return 0, "", sites.ErrNotFound
	}
	return site.UserID, site.Host, nil
}
