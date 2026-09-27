package dnszones

import (
	"context"
	"errors"
	"net"
	"strings"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// DelegationResolver — необязательное дополнение к Resolver: NS домена так, как их видит родительская зона (то, что указал регистратор).
// Обычный рекурсивный запрос для этого не годится: пока наши серверы ещё не знают зону, публичные резолверы получают от них отказ и отвечают
// SERVFAIL, хотя делегация уже правильная.
type DelegationResolver interface {
	LookupDelegation(ctx context.Context, name string) ([]string, error)
}

// LookupDelegation находит серверы родительской зоны и спрашивает у них NS домена без рекурсии. Пустой ответ без ошибки — домен не делегирован
// на отдельные серверы имён (он внутри родительской зоны или не существует).
func (netResolver) LookupDelegation(ctx context.Context, name string) ([]string, error) {
	labels := strings.Split(strings.ToLower(strings.TrimSuffix(name, ".")), ".")
	for i := 1; i < len(labels); i++ {
		parent := strings.Join(labels[i:], ".")
		servers, err := net.DefaultResolver.LookupNS(ctx, parent)
		if err != nil || len(servers) == 0 {
			continue // это не зона (например, «co.uk» без своих NS): поднимаемся выше
		}
		var lastErr error
		for k, sv := range servers {
			if k == 4 {
				break
			}
			ips, err := net.DefaultResolver.LookupHost(ctx, strings.TrimSuffix(sv.Host, "."))
			if err != nil {
				lastErr = err
				continue
			}
			for _, ip := range ips {
				if p := net.ParseIP(ip); p == nil || p.To4() == nil {
					continue
				}
				ns, err := queryNS(ctx, net.JoinHostPort(ip, "53"), name)
				if err == nil {
					return ns, nil
				}
				lastErr = err
				break
			}
		}
		if lastErr == nil {
			lastErr = errors.New("parent zone servers are not reachable")
		}
		return nil, lastErr
	}
	return nil, errors.New("no parent zone")
}

// queryNS — один запрос NS без рекурсии к addr (сначала UDP, при усечении ответа TCP). Возвращает NS с именем домена из ответа и раздела полномочий.
func queryNS(ctx context.Context, addr, name string) ([]string, error) {
	fqdn, err := dnsmessage.NewName(strings.TrimSuffix(name, ".") + ".")
	if err != nil {
		return nil, err
	}
	msg := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: uint16(time.Now().UnixNano())}, // идентификатор не секрет: ответ проверяется по имени и типу
		Questions: []dnsmessage.Question{{Name: fqdn, Type: dnsmessage.TypeNS, Class: dnsmessage.ClassINET}},
	}
	wire, err := msg.Pack()
	if err != nil {
		return nil, err
	}
	d := net.Dialer{Timeout: 3 * time.Second}
	resp, err := exchange(ctx, &d, "udp", addr, wire, msg.ID)
	if err == nil && resp.Truncated {
		resp, err = exchange(ctx, &d, "tcp", addr, wire, msg.ID)
	}
	if err != nil {
		return nil, err
	}
	if resp.RCode != dnsmessage.RCodeSuccess && resp.RCode != dnsmessage.RCodeNameError {
		return nil, errors.New("dns: " + resp.RCode.String())
	}
	out := []string{}
	for _, rr := range append(append([]dnsmessage.Resource{}, resp.Answers...), resp.Authorities...) {
		ns, ok := rr.Body.(*dnsmessage.NSResource)
		if !ok || !strings.EqualFold(strings.TrimSuffix(rr.Header.Name.String(), "."), strings.TrimSuffix(name, ".")) {
			continue
		}
		out = append(out, strings.ToLower(strings.TrimSuffix(ns.NS.String(), ".")))
	}
	return out, nil
}

func exchange(ctx context.Context, d *net.Dialer, network, addr string, wire []byte, id uint16) (*dnsmessage.Message, error) {
	conn, err := d.DialContext(ctx, network, addr)
	if err != nil {
		return nil, err
	}
	defer func() { _ = conn.Close() }()
	_ = conn.SetDeadline(time.Now().Add(4 * time.Second))
	var buf []byte
	if network == "tcp" {
		buf = append([]byte{byte(len(wire) >> 8), byte(len(wire))}, wire...)
	} else {
		buf = wire
	}
	if _, err := conn.Write(buf); err != nil {
		return nil, err
	}
	var data []byte
	if network == "tcp" {
		var hdr [2]byte
		if _, err := readFull(conn, hdr[:]); err != nil {
			return nil, err
		}
		data = make([]byte, int(hdr[0])<<8|int(hdr[1]))
		if _, err := readFull(conn, data); err != nil {
			return nil, err
		}
	} else {
		data = make([]byte, 4096)
		n, err := conn.Read(data)
		if err != nil {
			return nil, err
		}
		data = data[:n]
	}
	var resp dnsmessage.Message
	if err := resp.Unpack(data); err != nil {
		return nil, err
	}
	if resp.ID != id {
		return nil, errors.New("dns: unexpected response id")
	}
	return &resp, nil
}

func readFull(c net.Conn, b []byte) (int, error) {
	n := 0
	for n < len(b) {
		m, err := c.Read(b[n:])
		n += m
		if err != nil {
			return n, err
		}
	}
	return n, nil
}

// lookupNS — NS домена: сначала по данным родительской зоны (если резолвер это умеет), иначе обычным запросом.
func (s *Service) lookupNS(ctx context.Context, domain string) ([]string, error) {
	if dr, ok := s.cfg.Resolver.(DelegationResolver); ok {
		if ns, err := dr.LookupDelegation(ctx, domain); err == nil && len(ns) > 0 {
			return ns, nil
		}
	}
	return s.cfg.Resolver.LookupNS(ctx, domain)
}

// DelegatedToUs: у регистратора домен указан на наши серверы имён (и только на них). Такое делегирование мог задать только тот, кто управляет
// доменом, поэтому оно подтверждает владение без TXT-записи.
func (s *Service) DelegatedToUs(ctx context.Context, domain string) bool {
	c, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	found, _ := s.lookupNS(c, domain)
	ours := map[string]bool{}
	for _, n := range s.cfg.NS {
		ours[n] = true
	}
	mine := 0
	for _, n := range found {
		n = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(n), "."))
		if n == "" {
			continue
		}
		if !ours[n] {
			return false
		}
		mine++
	}
	return mine > 0
}
