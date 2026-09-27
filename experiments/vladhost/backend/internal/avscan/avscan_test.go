package avscan_test

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"path/filepath"
	"strings"
	"testing"

	"vladhost/internal/avscan"
)

// fakeClamd отвечает по протоколу INSTREAM: FOUND, если в потоке есть строка EICAR.
func fakeClamd(t *testing.T) string {
	t.Helper()
	sock := filepath.Join(t.TempDir(), "clamd.ctl")
	l, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer func() { _ = c.Close() }()
				cmd := make([]byte, len("zINSTREAM\x00"))
				if _, err := io.ReadFull(c, cmd); err != nil || string(cmd) != "zINSTREAM\x00" {
					_, _ = c.Write([]byte("UNKNOWN COMMAND\x00"))
					return
				}
				var data bytes.Buffer
				for {
					var n uint32
					if err := binary.Read(c, binary.BigEndian, &n); err != nil {
						return
					}
					if n == 0 {
						break
					}
					if _, err := io.CopyN(&data, c, int64(n)); err != nil {
						return
					}
				}
				if strings.Contains(data.String(), "EICAR") {
					_, _ = c.Write([]byte("stream: Eicar-Test-Signature FOUND\x00"))
				} else {
					_, _ = c.Write([]byte("stream: OK\x00"))
				}
			}(c)
		}
	}()
	return sock
}

func TestScan(t *testing.T) {
	s := avscan.New(fakeClamd(t))
	ctx := context.Background()
	if sig, err := s.Scan(ctx, strings.NewReader("hello")); err != nil || sig != "" {
		t.Fatalf("чистый: %q %v", sig, err)
	}
	// Больше одного блока (64 КБ): сигнатура в конце потока.
	big := strings.Repeat("a", 200<<10) + "EICAR"
	if sig, err := s.Scan(ctx, strings.NewReader(big)); err != nil || sig != "Eicar-Test-Signature" {
		t.Fatalf("заражённый: %q %v", sig, err)
	}
	if avscan.New("") != nil {
		t.Fatal("пустой адрес — проверка выключена")
	}
	down := avscan.New(filepath.Join(t.TempDir(), "nope.ctl"))
	if _, err := down.Scan(ctx, strings.NewReader("x")); !errors.Is(err, avscan.ErrUnavailable) {
		t.Fatalf("clamd недоступен: %v", err)
	}
}
