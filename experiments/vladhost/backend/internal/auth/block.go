package auth

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"unicode/utf8"

	"gorm.io/gorm"

	"vladhost/internal/apperr"
)

var (
	ErrBlocked      = apperr.New(http.StatusForbidden, "account_blocked", "account is blocked")
	ErrBlockAdmin   = apperr.New(http.StatusConflict, "block_admin", "administrators cannot be blocked")
	ErrBlockReason  = apperr.New(http.StatusUnprocessableEntity, "block_reason", "reason is required").With(blockReasonMax).OnField("reason")
	ErrUserNotFound = apperr.New(http.StatusNotFound, "user_not_found", "user not found")
)

const blockReasonMax = 500

// Block блокирует аккаунт: все сессии закрываются сразу (и уже выданные access-токены перестают работать),
// вход, API-токены, FTP и SSH отключаются. Причину видит пользователь при попытке войти.
func (s *Service) Block(ctx context.Context, userID int64, reason string) (*User, error) {
	reason = strings.TrimSpace(reason)
	if reason == "" || utf8.RuneCountInString(reason) > blockReasonMax {
		return nil, ErrBlockReason
	}
	u, err := s.lookup(ctx, userID)
	if err != nil {
		return nil, err
	}
	if u.Role == RoleAdmin {
		return nil, ErrBlockAdmin
	}
	err = s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&User{}).Where("id = ?", userID).Updates(map[string]any{"blocked_at": s.now(), "blocked_reason": reason}).Error; err != nil {
			return err
		}
		return s.revokeSessions(tx, userID, 0, -1)
	})
	if err != nil {
		return nil, err
	}
	return s.lookup(ctx, userID)
}

// Unblock снимает блокировку. Сессии не возвращаются: пользователь входит заново.
func (s *Service) Unblock(ctx context.Context, userID int64) (*User, error) {
	if _, err := s.lookup(ctx, userID); err != nil {
		return nil, err
	}
	if err := s.db.WithContext(ctx).Model(&User{}).Where("id = ?", userID).Updates(map[string]any{"blocked_at": nil, "blocked_reason": ""}).Error; err != nil {
		return nil, err
	}
	return s.lookup(ctx, userID)
}

// BlockedReason — причина блокировки для экрана входа ("" — не заблокирован или нет такого пользователя).
func (s *Service) BlockedReason(ctx context.Context, login string) string {
	var u User
	if err := s.db.WithContext(ctx).Select("blocked_at", "blocked_reason").Where("email = ? OR username = ?", lower(login), lower(login)).First(&u).Error; err != nil || u.BlockedAt == nil {
		return ""
	}
	return u.BlockedReason
}

func (s *Service) lookup(ctx context.Context, userID int64) (*User, error) {
	var u User
	if err := s.db.WithContext(ctx).First(&u, userID).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrUserNotFound
		}
		return nil, err
	}
	return &u, nil
}
