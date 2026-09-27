// Package auth: пользователи, инвайты, пароли и токены доступа.
package auth

import (
	"time"

	"gorm.io/gorm"
)

type Role string

const (
	RoleAdmin Role = "admin"
	RoleUser  Role = "user"
)

type User struct {
	ID           int64     `gorm:"primaryKey" json:"id"`
	Email        string    `json:"email"`
	Username     string    `json:"username"`
	PasswordHash string    `json:"-"`
	Role         Role      `json:"role"`
	CreatedAt    time.Time `json:"created_at"`
	// Почта: подтверждение адреса, язык писем (ru или it) и согласие на уведомления о проблемах.
	EmailVerifiedAt *time.Time `json:"email_verified_at"`
	Lang            string     `json:"lang"`
	NotifyEmail     bool       `json:"notify_email"`
	// Двухфакторный вход (TOTP): секреты зашифрованы и наружу не отдаются, видно только, включён ли он.
	TOTPSecret    *string    `gorm:"column:totp_secret" json:"-"`
	TOTPPending   *string    `gorm:"column:totp_pending" json:"-"`
	TOTPEnabledAt *time.Time `gorm:"column:totp_enabled_at" json:"two_factor_enabled_at"`
	TOTPLastStep  int64      `gorm:"column:totp_last_step" json:"-"`
	// Профиль: часовой пояс для дат ('' — как в браузере), тема оформления, аватар (ключ картинки; nil — инициал).
	Timezone  string  `json:"timezone"`
	Theme     string  `gorm:"default:system" json:"theme"`
	AvatarKey *string `json:"-"`
	AvatarURL string  `gorm:"-" json:"avatar_url"`
	// Администрирование: блокировка (вход, FTP, SSH и API-токены отключены) и личные лимиты (nil — общие).
	BlockedAt      *time.Time `json:"blocked_at"`
	BlockedReason  string     `json:"blocked_reason"`
	MaxSites       *int       `json:"max_sites"`
	DiskQuotaBytes *int64     `json:"disk_quota_bytes"`
}

// AfterFind заполняет адрес аватара: картинку отдаёт /api/avatars/{ключ}, ключ меняется при каждой загрузке (кеш браузера не мешает).
func (u *User) AfterFind(*gorm.DB) error {
	u.FillAvatarURL()
	return nil
}

// FillAvatarURL заполняет адрес аватара по ключу (нужно, когда строку читают через Scan: хуки модели там не срабатывают).
func (u *User) FillAvatarURL() {
	u.AvatarURL = ""
	if u.AvatarKey != nil && *u.AvatarKey != "" {
		u.AvatarURL = "/api/avatars/" + *u.AvatarKey
	}
}

type Invite struct {
	ID        int64      `gorm:"primaryKey" json:"id"`
	Code      string     `json:"code"`
	CreatedBy int64      `json:"created_by"`
	UsedBy    *int64     `json:"used_by"`
	UsedAt    *time.Time `json:"used_at"`
	ExpiresAt time.Time  `json:"expires_at"`
	CreatedAt time.Time  `json:"created_at"`
}

// InviteView — инвайт с именем того, кто его использовал (для списка в панели).
type InviteView struct {
	Invite
	UsedByUsername *string `json:"used_by_username"`
}

type RefreshToken struct {
	ID        int64 `gorm:"primaryKey"`
	UserID    int64
	SessionID *int64
	TokenHash string
	ExpiresAt time.Time
	RevokedAt *time.Time
	CreatedAt time.Time
}

// Session — результат входа: короткий access-токен и длинный refresh (уходит в httpOnly cookie).
type Session struct {
	AccessToken    string
	ExpiresIn      int
	RefreshToken   string
	RefreshExpires time.Time
	SessionID      int64
	User           User
}
