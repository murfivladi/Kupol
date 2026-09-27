package dnszones

import (
	"context"
	"net"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// fakeParent — «сервер родительской зоны»: на запрос NS отвечает делегацией в разделе полномочий, как настоящие серверы .ru.
func fakeParent(t *testing.T, truncateUDP bool) string {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = pc.Close() })
	go func() {
		buf := make([]byte, 512)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			var q dnsmessage.Message
			if q.Unpack(buf[:n]) != nil || len(q.Questions) != 1 {
				continue
			}
			resp := dnsmessage.Message{Header: dnsmessage.Header{ID: q.ID, Response: true, Truncated: truncateUDP}, Questions: q.Questions}
			if !truncateUDP && q.Questions[0].Name.String() == "vladislavb.ru." {
				for _, ns := range []string{"ns.vladinc.ru.", "NS2.vladinc.ru."} {
					resp.Authorities = append(resp.Authorities, dnsmessage.Resource{
						Header: dnsmessage.ResourceHeader{Name: q.Questions[0].Name, Type: dnsmessage.TypeNS, Class: dnsmessage.ClassINET, TTL: 3600},
						Body:   &dnsmessage.NSResource{NS: dnsmessage.MustNewName(ns)},
					})
				}
			}
			out, _ := resp.Pack()
			_, _ = pc.WriteTo(out, addr)
		}
	}()
	return pc.LocalAddr().String()
}

func TestQueryNSReadsTheDelegationFromAuthority(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	addr := fakeParent(t, false)
	got, err := queryNS(ctx, addr, "vladislavb.ru")
	if err != nil || len(got) != 2 || got[0] != "ns.vladinc.ru" || got[1] != "ns2.vladinc.ru" {
		t.Fatalf("%v %v", got, err)
	}
	// домен без своей делегации (внутри родительской зоны): пусто и без ошибки
	got, err = queryNS(ctx, addr, "inside.example.ru")
	if err != nil || len(got) != 0 {
		t.Fatalf("%v %v", got, err)
	}
}
