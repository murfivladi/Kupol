package notify

import (
	"context"
	"log"

	"vladhost/internal/auth"
)

// AbuseNew сообщает администраторам о жалобе на сайт. Жалобы на чужие адреса (не наш сайт) видны в очереди, но письма о них нет:
// открытую форму могут использовать для спама.
func (s *Service) AbuseNew(ctx context.Context, id int64, url, category, message string, ours bool) {
	if !s.Enabled() || !ours {
		return
	}
	var admins []auth.User
	if err := s.db.WithContext(ctx).Where("role = ?", auth.RoleAdmin).Find(&admins).Error; err != nil {
		log.Printf("уведомления: жалоба %d: %v", id, err)
		return
	}
	for _, a := range admins {
		if !wantsMail(a) {
			continue
		}
		d := Data{Ticket: id, Host: url, Author: category, Reason: shorten(message, 1500), Link: s.Link("/admin?tab=abuse")}
		if err := s.send(ctx, nil, a, KindAbuseNew, d); err != nil {
			log.Printf("уведомления: жалоба %d: %v", id, err)
		}
	}
}
