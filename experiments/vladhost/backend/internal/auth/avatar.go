package auth

import (
	"bytes"
	"context"
	"errors"
	"image"
	"image/color"
	_ "image/gif" // форматы для image.Decode
	_ "image/jpeg"
	"image/png"
	"io"
	"net/http"
	"time"

	"gorm.io/gorm"
	"gorm.io/gorm/clause"

	"vladhost/internal/apperr"
)

// Аватар: принимаем PNG, JPEG или GIF до 5 МБ, обрезаем по центру до квадрата и уменьшаем до 256×256.
// Сохраняется только наш PNG — исходный файл (с EXIF, геометкой и чем угодно внутри) не хранится и не отдаётся.
const (
	avatarSize     = 256
	avatarMaxBytes = 5 << 20
	avatarMaxSide  = 8000 // защита от «бомбы»: маленький файл, огромная картинка в памяти
)

var (
	ErrAvatarFormat   = apperr.New(http.StatusUnprocessableEntity, "avatar_format", "not a png, jpeg or gif image").OnField("file")
	ErrAvatarTooBig   = apperr.New(http.StatusRequestEntityTooLarge, "avatar_too_big", "avatar is too big").With(avatarMaxBytes >> 20).OnField("file")
	ErrAvatarNotFound = apperr.New(http.StatusNotFound, "not_found", "avatar not found")
)

type userAvatar struct {
	UserID    int64 `gorm:"primaryKey"`
	Key       string
	PNG       []byte `gorm:"column:png"`
	UpdatedAt time.Time
}

func (userAvatar) TableName() string { return "user_avatars" }

// SetAvatar обрабатывает картинку и делает её аватаром пользователя.
func (s *Service) SetAvatar(ctx context.Context, userID int64, r io.Reader) (*User, error) {
	data, err := io.ReadAll(io.LimitReader(r, avatarMaxBytes+1))
	if err != nil {
		return nil, err
	}
	if len(data) > avatarMaxBytes {
		return nil, ErrAvatarTooBig
	}
	out, err := renderAvatar(data)
	if err != nil {
		return nil, err
	}
	key, err := randomToken(24) // 32 символа base64url
	if err != nil {
		return nil, err
	}
	err = s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		row := userAvatar{UserID: userID, Key: key, PNG: out, UpdatedAt: s.now()}
		if err := tx.Clauses(clause.OnConflict{UpdateAll: true}).Create(&row).Error; err != nil {
			return err
		}
		return tx.Model(&User{}).Where("id = ?", userID).Update("avatar_key", key).Error
	})
	if err != nil {
		return nil, err
	}
	return s.UserByID(ctx, userID)
}

// DeleteAvatar возвращает аватар-инициал.
func (s *Service) DeleteAvatar(ctx context.Context, userID int64) (*User, error) {
	err := s.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Where("user_id = ?", userID).Delete(&userAvatar{}).Error; err != nil {
			return err
		}
		return tx.Model(&User{}).Where("id = ?", userID).Update("avatar_key", nil).Error
	})
	if err != nil {
		return nil, err
	}
	return s.UserByID(ctx, userID)
}

// Avatar — картинка по ключу (ключ случайный и меняется при загрузке, поэтому отдаётся без входа и кешируется навсегда).
func (s *Service) Avatar(ctx context.Context, key string) ([]byte, error) {
	if len(key) != 32 {
		return nil, ErrAvatarNotFound
	}
	var row userAvatar
	if err := s.db.WithContext(ctx).Where("key = ?", key).First(&row).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrAvatarNotFound
		}
		return nil, err
	}
	return row.PNG, nil
}

func renderAvatar(data []byte) ([]byte, error) {
	cfg, _, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil {
		return nil, ErrAvatarFormat
	}
	if cfg.Width < 1 || cfg.Height < 1 || cfg.Width > avatarMaxSide || cfg.Height > avatarMaxSide {
		return nil, ErrAvatarFormat
	}
	src, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return nil, ErrAvatarFormat
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, squareThumb(src, avatarSize)); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// squareThumb вырезает центральный квадрат и уменьшает его до size×size усреднением по площади
// (каждый пиксель результата — среднее всех исходных пикселей, которые на него попадают). Маленькие картинки растягиваются.
func squareThumb(src image.Image, size int) *image.NRGBA {
	b := src.Bounds()
	side := min(b.Dx(), b.Dy())
	x0, y0 := b.Min.X+(b.Dx()-side)/2, b.Min.Y+(b.Dy()-side)/2
	dst := image.NewNRGBA(image.Rect(0, 0, size, size))
	for y := range size {
		sy0, sy1 := y0+y*side/size, y0+max((y+1)*side/size, y*side/size+1)
		for x := range size {
			sx0, sx1 := x0+x*side/size, x0+max((x+1)*side/size, x*side/size+1)
			var r, g, bl, a, n uint64
			for sy := sy0; sy < sy1; sy++ {
				for sx := sx0; sx < sx1; sx++ {
					c := color.NRGBA64Model.Convert(src.At(sx, sy)).(color.NRGBA64)
					// усредняем с учётом прозрачности, иначе края прозрачных PNG темнеют
					r += uint64(c.R) * uint64(c.A)
					g += uint64(c.G) * uint64(c.A)
					bl += uint64(c.B) * uint64(c.A)
					a += uint64(c.A)
					n++
				}
			}
			var px color.NRGBA
			if a > 0 {
				px = color.NRGBA{R: uint8(r / a >> 8), G: uint8(g / a >> 8), B: uint8(bl / a >> 8), A: uint8(a / n >> 8)}
			}
			dst.SetNRGBA(x, y, px)
		}
	}
	return dst
}
