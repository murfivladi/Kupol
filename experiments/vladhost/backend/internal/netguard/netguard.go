// Package netguard: защита исходящих соединений панели от обращений во внутреннюю сеть (SSRF).
// Проверка стоит на самом соединении (Control получает уже разрешённый адрес), поэтому её не обойти ни именем,
// которое указывает на 127.0.0.1, ни подменой DNS, ни редиректом.
package netguard

import (
	"errors"
	"net"
	"net/netip"
	"syscall"
	"time"
)

// ErrPrivate — адрес во внутренней сети.
var ErrPrivate = errors.New("address is in an internal network")

// cgnat — 100.64.0.0/10: общий адрес провайдеров и облаков, для наших целей то же, что внутренняя сеть.
var cgnat = netip.MustParsePrefix("100.64.0.0/10")

// Private сообщает, что адрес внутренний: loopback, частные сети, link-local, multicast, 0.0.0.0, CGNAT.
func Private(a netip.Addr) bool {
	a = a.Unmap()
	return a.IsLoopback() || a.IsPrivate() || a.IsLinkLocalUnicast() || a.IsLinkLocalMulticast() || a.IsMulticast() ||
		a.IsUnspecified() || cgnat.Contains(a)
}

// Dialer возвращает соединитель, который отказывается подключаться к внутренним адресам (allowPrivate — только для тестов).
func Dialer(timeout time.Duration, allowPrivate bool) *net.Dialer {
	d := &net.Dialer{Timeout: timeout}
	if !allowPrivate {
		d.Control = func(_, address string, _ syscall.RawConn) error {
			ap, err := netip.ParseAddrPort(address)
			if err != nil || Private(ap.Addr()) {
				return ErrPrivate
			}
			return nil
		}
	}
	return d
}
