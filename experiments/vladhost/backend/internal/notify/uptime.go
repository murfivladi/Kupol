package notify

import (
	"context"
	"fmt"
	"log"
	"strconv"
	"time"

	"vladhost/internal/auth"
	"vladhost/internal/i18n"
	"vladhost/internal/uptime"
)

// SiteStatus сообщает владельцу о падении или восстановлении сайта. Одно письмо на событие сбоя (ключ — номер сбоя):
// повторный вызов после перезапуска панели дубля не даст. Письмо получают, только если согласие на уведомления включено.
func (s *Service) SiteStatus(ctx context.Context, userID, siteID int64, host string, down bool, errCode string, httpCode int, since time.Time, incidentID int64) {
	if !s.Enabled() {
		return
	}
	var u auth.User
	if err := s.db.WithContext(ctx).First(&u, userID).Error; err != nil || !wantsMail(u) {
		return
	}
	kind, text := KindSiteUp, uptime.Duration(i18n.Lang(u.Lang), s.now().Sub(since))
	if down {
		kind, text = KindSiteDown, uptime.Reason(i18n.Lang(u.Lang), errCode, httpCode)
	}
	d := Data{Host: host, Reason: text, Link: s.Link("/sites/" + strconv.FormatInt(siteID, 10) + "/monitor")}
	if err := s.once(ctx, u, kind, fmt.Sprintf("uptime|%d", incidentID), d); err != nil {
		log.Printf("уведомления: мониторинг %s: %v", host, err)
	}
}
