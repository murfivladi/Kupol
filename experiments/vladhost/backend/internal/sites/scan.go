package sites

import (
	"context"
	"io"
	"log"
	"net/http"
	"os"

	"vladhost/internal/apperr"
)

// ErrInfected — антивирус нашёл вредоносный код; файл не сохранён.
var ErrInfected = apperr.New(http.StatusUnprocessableEntity, "virus_found", "upload is infected")

// scan проверяет данные антивирусом. Недоступный антивирус не мешает загрузке: это пишется в журнал панели.
func (s *Service) scan(ctx context.Context, r io.Reader) error {
	if s.scanner == nil {
		return nil
	}
	sig, err := s.scanner.Scan(ctx, r)
	if err != nil {
		log.Printf("антивирус: проверка не выполнена, загрузка принята без неё: %v", err)
		return nil
	}
	if sig != "" {
		return ErrInfected.With(sig)
	}
	return nil
}

func (s *Service) scanFile(ctx context.Context, root *os.Root, name string) error {
	if s.scanner == nil {
		return nil
	}
	f, err := root.Open(name)
	if err != nil {
		return err
	}
	defer func() { _ = f.Close() }()
	return s.scan(ctx, f)
}
