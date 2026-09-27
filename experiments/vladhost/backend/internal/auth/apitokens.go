package auth

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base32"
	"encoding/hex"
	"errors"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"gorm.io/gorm"

	"vladhost/internal/apperr"
)

// API-токены: вход для деплоя из CI без пароля и второго фактора. Токен умеет только выкладывать архив на сайт,
// поэтому его утечка не открывает аккаунт целиком. В базе лежит только SHA-256: токен случайный и длинный, соль не нужна.
const (
	APITokenPrefix   = "vht_"
	apiTokenLimit    = 20
	apiTokenNameMax  = 60
	apiTokenMaxDays  = 365
	apiTokenTouchGap = time.Minute // отметку «последнее использование» пишем не чаще раза в минуту
)

var (
	ErrAPITokenNotFound = apperr.New(http.StatusNotFound, "api_token_not_found", "API token not found")
	ErrAPITokenLimit    = apperr.New(http.StatusConflict, "api_token_limit", "too many API tokens").With(apiTokenLimit)
	ErrAPITokenName     = apperr.New(http.StatusUnprocessableEntity, "api_token_name", "token name is required").With(apiTokenNameMax).OnField("name")
	ErrAPITokenTTL      = apperr.New(http.StatusUnprocessableEntity, "api_token_ttl", "bad token lifetime").With(apiTokenMaxDays).OnField("expires_days")
	ErrAPITokenInvalid  = apperr.New(http.StatusUnauthorized, "api_token_invalid", "API token is invalid or expired")
)

// APIToken — токен в списке. Сам секрет не хранится и в список не попадает.
type APIToken struct {
	ID         int64      `gorm:"primaryKey" json:"id"`
	UserID     int64      `json:"-"`
	SiteID     *int64     `json:"site_id"`
	Name       string     `json:"name"`
	TokenHash  string     `json:"-"`
	Prefix     string     `json:"prefix"`
	CreatedAt  time.Time  `json:"created_at"`
	ExpiresAt  *time.Time `json:"expires_at"`
	LastUsedAt *time.Time `json:"last_used_at"`
	LastUsedIP string     `gorm:"column:last_used_ip" json:"last_used_ip"`
}

func (APIToken) TableName() string { return "api_tokens" }

type APITokenInput struct {
	Name        string
	SiteID      *int64 // сайт уже проверен вызывающим: он принадлежит пользователю
	ExpiresDays int    // 0 — бессрочный
}

func hashAPIToken(raw string) string {
	sum := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(sum[:])
}

// CreateAPIToken выпускает токен и возвращает его открытое значение: показать его можно только сейчас.
func (s *Service) CreateAPIToken(ctx context.Context, userID int64, in APITokenInput) (*APIToken, string, error) {
	name := strings.TrimSpace(in.Name)
	if name == "" || utf8.RuneCountInString(name) > apiTokenNameMax {
		return nil, "", ErrAPITokenName
	}
	if in.ExpiresDays < 0 || in.ExpiresDays > apiTokenMaxDays {
		return nil, "", ErrAPITokenTTL
	}
	buf := make([]byte, 25)
	if _, err := rand.Read(buf); err != nil {
		return nil, "", err
	}
	raw := APITokenPrefix + strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(buf))
	t := APIToken{UserID: userID, SiteID: in.SiteID, Name: name, TokenHash: hashAPIToken(raw), Prefix: raw[:len(APITokenPrefix)+6], CreatedAt: s.now()}
	if in.ExpiresDays > 0 {
		exp := s.now().AddDate(0, 0, in.ExpiresDays)
		t.ExpiresAt = &exp
	}
	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		// Блокировка строки пользователя: два одновременных запроса не превысят лимит.
		if err := tx.Exec("SELECT 1 FROM users WHERE id = ? FOR UPDATE", userID).Error; err != nil {
			return err
		}
		var n int64
		if err := tx.Model(&APIToken{}).Where("user_id = ?", userID).Count(&n).Error; err != nil {
			return err
		}
		if n >= apiTokenLimit {
			return ErrAPITokenLimit
		}
		return tx.Create(&t).Error
	})
	if err != nil {
		return nil, "", err
	}
	return &t, raw, nil
}

func (s *Service) ListAPITokens(ctx context.Context, userID int64) ([]APIToken, error) {
	list := []APIToken{}
	err := s.db.WithContext(ctx).Where("user_id = ?", userID).Order("id DESC").Find(&list).Error
	return list, err
}

// DeleteAPIToken отзывает токен: следующий запрос с ним получит отказ.
func (s *Service) DeleteAPIToken(ctx context.Context, userID, id int64) (*APIToken, error) {
	var t APIToken
	if err := s.db.WithContext(ctx).Where("id = ? AND user_id = ?", id, userID).First(&t).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrAPITokenNotFound
		}
		return nil, err
	}
	if err := s.db.WithContext(ctx).Delete(&t).Error; err != nil {
		return nil, err
	}
	return &t, nil
}

// AuthenticateAPIToken проверяет токен из заголовка и отмечает, когда и откуда им воспользовались.
func (s *Service) AuthenticateAPIToken(ctx context.Context, raw, ip string) (*APIToken, error) {
	if !strings.HasPrefix(raw, APITokenPrefix) || len(raw) > 100 {
		return nil, ErrAPITokenInvalid
	}
	var t APIToken
	if err := s.db.WithContext(ctx).Where("token_hash = ?", hashAPIToken(raw)).First(&t).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrAPITokenInvalid
		}
		return nil, err
	}
	now := s.now()
	if t.ExpiresAt != nil && !now.Before(*t.ExpiresAt) {
		return nil, ErrAPITokenInvalid
	}
	var owner User
	if err := s.db.WithContext(ctx).Select("blocked_at", "blocked_reason").First(&owner, t.UserID).Error; err != nil {
		return nil, err
	}
	if owner.BlockedAt != nil {
		return nil, ErrBlocked.With(owner.BlockedReason)
	}
	if len(ip) > 45 {
		ip = ip[:45]
	}
	if t.LastUsedAt == nil || now.Sub(*t.LastUsedAt) >= apiTokenTouchGap || t.LastUsedIP != ip {
		_ = s.db.WithContext(ctx).Model(&t).Updates(map[string]any{"last_used_at": now, "last_used_ip": ip}).Error
		t.LastUsedAt, t.LastUsedIP = &now, ip
	}
	return &t, nil
}
