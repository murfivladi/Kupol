package sites

import "context"

// LimitsFor — лимиты пользователя: личные, если их задал администратор, иначе общие для панели.
func (s *Service) LimitsFor(ctx context.Context, userID int64) Limits {
	lim := s.limits
	var row struct {
		MaxSites       *int
		DiskQuotaBytes *int64
	}
	if err := s.db.WithContext(ctx).Table("users").Select("max_sites, disk_quota_bytes").Where("id = ?", userID).Scan(&row).Error; err != nil {
		return lim // при сбое базы действует общий лимит: он безопасен
	}
	if row.MaxSites != nil {
		lim.MaxSites = *row.MaxSites
	}
	if row.DiskQuotaBytes != nil {
		lim.DiskQuotaBytes = *row.DiskQuotaBytes
	}
	return lim
}

func (s *Service) quota(ctx context.Context, userID int64) int64 {
	return s.LimitsFor(ctx, userID).DiskQuotaBytes
}
