package uptime

import (
	"time"

	"vladhost/internal/i18n"
)

// Reason — причина сбоя словами (для писем). Ключи перечислены явно: сторож каталога ищет их в коде.
func Reason(l i18n.Lang, code string, httpCode int) string {
	switch code {
	case ErrHTTP:
		return i18n.T(l, "uptime.reason.http", httpCode)
	case ErrTimeout:
		return i18n.T(l, "uptime.reason.timeout")
	case ErrTLS:
		return i18n.T(l, "uptime.reason.tls")
	case ErrDNS:
		return i18n.T(l, "uptime.reason.dns")
	}
	return i18n.T(l, "uptime.reason.connect")
}

// Duration — «3 ч 5 мин» для письма о восстановлении.
func Duration(l i18n.Lang, d time.Duration) string {
	m := max(int(d.Round(time.Minute).Minutes()), 1)
	if h := m / 60; h > 0 {
		return i18n.T(l, "uptime.duration_h", h, m%60)
	}
	return i18n.T(l, "uptime.duration_m", m)
}
