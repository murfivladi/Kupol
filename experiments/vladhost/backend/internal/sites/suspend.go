package sites

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"time"
)

// SuspendedMarker — файл в папке сайта (рядом с public, вне досягаемости посетителей): шлюз видит его и не отдаёт сайт.
// Файл, а не база: шлюзу база не нужна, и он узнаёт о приостановке сразу, без кеша.
const SuspendedMarker = "SUSPENDED"

// ownerBlocked: аккаунт владельца заблокирован — FTP закрыт.
func (s *Service) ownerBlocked(ctx context.Context, userID int64) bool {
	var n int64
	if err := s.db.WithContext(ctx).Table("users").Where("id = ? AND blocked_at IS NOT NULL", userID).Count(&n).Error; err != nil {
		return true // не смогли проверить — не пускаем
	}
	return n > 0
}

// Suspend приостанавливает сайт (администратор). byBlock — вместе с блокировкой аккаунта: такие сайты разблокировка вернёт сама.
func (s *Service) Suspend(ctx context.Context, siteID int64, reason string, byBlock bool) (*Site, error) {
	var site Site
	if err := s.db.WithContext(ctx).First(&site, siteID).Error; err != nil {
		return nil, ErrNotFound
	}
	if site.SuspendedAt != nil {
		// Уже приостановлен. Отдельная приостановка (по жалобе) важнее блокировки: снимаем признак «вместе с блокировкой»,
		// чтобы разблокировка аккаунта не вернула сайт посетителям.
		if !byBlock && site.SuspendedByBlock {
			if err := s.db.WithContext(ctx).Model(&site).Updates(map[string]any{"suspended_reason": reason, "suspended_by_block": false}).Error; err != nil {
				return nil, err
			}
		}
		return &site, nil
	}
	now := time.Now()
	if err := os.WriteFile(filepath.Join(s.siteDir(site.Host), SuspendedMarker), []byte(reason+"\n"), 0o644); err != nil {
		return nil, err
	}
	if err := s.db.WithContext(ctx).Model(&site).Updates(map[string]any{"suspended_at": now, "suspended_reason": reason, "suspended_by_block": byBlock}).Error; err != nil {
		return nil, err
	}
	return &site, nil
}

// Unsuspend возвращает сайт посетителям.
func (s *Service) Unsuspend(ctx context.Context, siteID int64) (*Site, error) {
	var site Site
	if err := s.db.WithContext(ctx).First(&site, siteID).Error; err != nil {
		return nil, ErrNotFound
	}
	if err := os.Remove(filepath.Join(s.siteDir(site.Host), SuspendedMarker)); err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if err := s.db.WithContext(ctx).Model(&site).Updates(map[string]any{"suspended_at": nil, "suspended_reason": "", "suspended_by_block": false}).Error; err != nil {
		return nil, err
	}
	return &site, nil
}

// SuspendUser приостанавливает все сайты пользователя (блокировка аккаунта).
func (s *Service) SuspendUser(ctx context.Context, userID int64, reason string) error {
	list, err := s.List(ctx, userID)
	if err != nil {
		return err
	}
	for _, st := range list {
		if _, err := s.Suspend(ctx, st.ID, reason, true); err != nil {
			return err
		}
	}
	return nil
}

// ResumeUser возвращает сайты, приостановленные вместе с блокировкой; приостановленные отдельно (по жалобе) остаются.
func (s *Service) ResumeUser(ctx context.Context, userID int64) error {
	var ids []int64
	if err := s.db.WithContext(ctx).Model(&Site{}).Where("user_id = ? AND suspended_by_block", userID).Pluck("id", &ids).Error; err != nil {
		return err
	}
	for _, id := range ids {
		if _, err := s.Unsuspend(ctx, id); err != nil {
			return err
		}
	}
	return nil
}
