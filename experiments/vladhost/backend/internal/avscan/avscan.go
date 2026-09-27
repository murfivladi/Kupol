// Package avscan: проверка загрузок антивирусом ClamAV через clamd (протокол INSTREAM).
// clamd уже стоит на сервере для почты. Если он не отвечает, загрузка проходит без проверки (как и письма):
// антивирус — дополнительная защита, и его сбой не должен останавливать работу сайтов.
package avscan

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"time"
)

const chunk = 64 << 10

// Scanner — клиент clamd. Адрес: путь к unix-сокету (/run/clamav/clamd.ctl) или host:port.
type Scanner struct {
	addr    string
	timeout time.Duration
}

func New(addr string) *Scanner {
	if addr == "" {
		return nil
	}
	return &Scanner{addr: addr, timeout: 2 * time.Minute}
}

// Enabled: nil-сканер значит «проверка выключена».
func (s *Scanner) Enabled() bool { return s != nil }

// ErrUnavailable — clamd не ответил или отказался проверять (например, файл больше его лимита StreamMaxLength).
var ErrUnavailable = errors.New("avscan: clamd unavailable")

// Scan отправляет данные на проверку. Возвращает имя найденной сигнатуры ("" — чисто).
// Архивы (zip, tar) clamd проверяет изнутри сам.
func (s *Scanner) Scan(ctx context.Context, r io.Reader) (string, error) {
	network := "tcp"
	if strings.HasPrefix(s.addr, "/") {
		network = "unix"
	}
	var d net.Dialer
	dctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	conn, err := d.DialContext(dctx, network, s.addr)
	cancel()
	if err != nil {
		return "", fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	defer func() { _ = conn.Close() }()
	deadline := time.Now().Add(s.timeout)
	if dl, ok := ctx.Deadline(); ok && dl.Before(deadline) {
		deadline = dl
	}
	_ = conn.SetDeadline(deadline)

	if _, err := conn.Write([]byte("zINSTREAM\x00")); err != nil {
		return "", fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	buf := make([]byte, chunk)
	var size [4]byte
	for {
		n, rerr := r.Read(buf)
		if n > 0 {
			binary.BigEndian.PutUint32(size[:], uint32(n))
			if _, err := conn.Write(size[:]); err != nil {
				return "", s.reply(conn, err) // clamd мог оборвать поток (лимит размера) — прочитаем его объяснение
			}
			if _, err := conn.Write(buf[:n]); err != nil {
				return "", s.reply(conn, err)
			}
		}
		if errors.Is(rerr, io.EOF) {
			break
		}
		if rerr != nil {
			return "", rerr
		}
	}
	binary.BigEndian.PutUint32(size[:], 0)
	if _, err := conn.Write(size[:]); err != nil {
		return "", fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	line, err := bufio.NewReader(conn).ReadString(0)
	if err != nil && line == "" {
		return "", fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	return parse(strings.TrimRight(line, "\x00\n"))
}

func (s *Scanner) reply(conn net.Conn, werr error) error {
	line, _ := bufio.NewReader(conn).ReadString(0)
	if line != "" {
		return fmt.Errorf("%w: %s", ErrUnavailable, strings.TrimRight(line, "\x00\n"))
	}
	return fmt.Errorf("%w: %v", ErrUnavailable, werr)
}

// parse разбирает ответ clamd: "stream: OK", "stream: Eicar-Signature FOUND" или "... ERROR".
func parse(line string) (string, error) {
	switch {
	case strings.HasSuffix(line, " OK"):
		return "", nil
	case strings.HasSuffix(line, " FOUND"):
		sig := strings.TrimSuffix(line, " FOUND")
		if i := strings.Index(sig, ": "); i >= 0 {
			sig = sig[i+2:]
		}
		return sig, nil
	}
	return "", fmt.Errorf("%w: %s", ErrUnavailable, line)
}
