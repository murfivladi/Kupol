package uptime

import (
	"context"
	"time"

	"gorm.io/gorm"

	"vladhost/internal/sites"
)

// Uptime — доля успешных проверок за период в процентах; nil — проверок не было.
type Uptime struct {
	Day   *float64 `json:"day"`
	Week  *float64 `json:"week"`
	Month *float64 `json:"month"`
}

// Bucket — отрезок времени (сутки или час): сколько проверок, сколько удачных, среднее время ответа удачных.
type Bucket struct {
	At     time.Time `json:"at"`
	Checks int       `json:"checks"`
	OK     int       `json:"ok"`
	AvgMs  int       `json:"avg_ms"`
}

type Report struct {
	Uptime    Uptime     `json:"uptime"`
	AvgMs     int        `json:"avg_ms"` // за сутки
	Days      []Bucket   `json:"days"`   // 30 суток, старые первыми (UTC)
	Hours     []Bucket   `json:"hours"`  // 24 часа
	Incidents []Incident `json:"incidents"`
}

// Report — сводка по сайту за 30 дней.
func (s *Service) Report(ctx context.Context, siteID int64) (*Report, error) {
	now := s.now().UTC()
	db := s.db.WithContext(ctx)
	r := &Report{Days: []Bucket{}, Hours: []Bucket{}, Incidents: []Incident{}}

	var win struct {
		DayOK, DayAll, WeekOK, WeekAll, MonthOK, MonthAll int
		AvgMs                                             float64
	}
	err := db.Raw(`SELECT
		count(*) FILTER (WHERE ok AND checked_at >= @day) AS day_ok, count(*) FILTER (WHERE checked_at >= @day) AS day_all,
		count(*) FILTER (WHERE ok AND checked_at >= @week) AS week_ok, count(*) FILTER (WHERE checked_at >= @week) AS week_all,
		count(*) FILTER (WHERE ok) AS month_ok, count(*) AS month_all,
		COALESCE(avg(ms) FILTER (WHERE ok AND checked_at >= @day), 0) AS avg_ms
		FROM site_checks WHERE site_id = @site AND checked_at >= @month`,
		map[string]any{"site": siteID, "day": now.Add(-24 * time.Hour), "week": now.Add(-7 * 24 * time.Hour), "month": now.Add(-30 * 24 * time.Hour)}).
		Scan(&win).Error
	if err != nil {
		return nil, err
	}
	r.Uptime = Uptime{Day: pct(win.DayOK, win.DayAll), Week: pct(win.WeekOK, win.WeekAll), Month: pct(win.MonthOK, win.MonthAll)}
	r.AvgMs = int(win.AvgMs)

	if r.Days, err = s.buckets(db, siteID, "day", now.Truncate(24*time.Hour).AddDate(0, 0, -29), 30, 24*time.Hour); err != nil {
		return nil, err
	}
	if r.Hours, err = s.buckets(db, siteID, "hour", now.Truncate(time.Hour).Add(-23*time.Hour), 24, time.Hour); err != nil {
		return nil, err
	}
	if err := db.Where("site_id = ?", siteID).Order("started_at DESC").Limit(20).Find(&r.Incidents).Error; err != nil {
		return nil, err
	}
	return r, nil
}

// buckets возвращает n отрезков подряд начиная с from; пустые отрезки тоже есть (checks = 0), чтобы график был ровным.
func (s *Service) buckets(db *gorm.DB, siteID int64, unit string, from time.Time, n int, step time.Duration) ([]Bucket, error) {
	var rows []Bucket
	err := db.Raw(`SELECT date_trunc(?, checked_at AT TIME ZONE 'UTC') AS at, count(*) AS checks, count(*) FILTER (WHERE ok) AS ok,
		COALESCE(avg(ms) FILTER (WHERE ok), 0)::int AS avg_ms
		FROM site_checks WHERE site_id = ? AND checked_at >= ? GROUP BY 1`, unit, siteID, from).Scan(&rows).Error
	if err != nil {
		return nil, err
	}
	byAt := map[int64]Bucket{}
	for _, b := range rows {
		byAt[b.At.Unix()] = b
	}
	out := make([]Bucket, n)
	for i := range n {
		at := from.Add(time.Duration(i) * step)
		b := byAt[time.Date(at.Year(), at.Month(), at.Day(), at.Hour(), 0, 0, 0, time.UTC).Unix()]
		b.At = at
		out[i] = b
	}
	return out, nil
}

func pct(ok, all int) *float64 {
	if all == 0 {
		return nil
	}
	v := float64(ok) * 100 / float64(all)
	return &v
}

// Public — открытая статус-страница сайта. Найти её можно только по адресу сайта, и только если владелец её включил.
type Public struct {
	Host       string     `json:"host"`
	State      string     `json:"state"`
	StateSince *time.Time `json:"state_since"`
	Report
}

func (s *Service) PublicStatus(ctx context.Context, host string) (*Public, error) {
	var row struct {
		Monitor
		Host string
	}
	err := s.db.WithContext(ctx).Table("site_monitors m").Select("m.*, s.host").Joins("JOIN sites s ON s.id = m.site_id").
		Where("s.host = ? AND m.public AND m.enabled", host).Scan(&row).Error
	if err != nil {
		return nil, err
	}
	if row.Host == "" {
		return nil, sites.ErrNotFound
	}
	rep, err := s.Report(ctx, row.SiteID)
	if err != nil {
		return nil, err
	}
	return &Public{Host: row.Host, State: row.State, StateSince: row.StateSince, Report: *rep}, nil
}
