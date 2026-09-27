package httpapi

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"

	"vladhost/internal/auth"
)

func (s *Server) putAvatar(c *gin.Context) {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 6<<20)
	fh, err := c.FormFile("file")
	if err != nil {
		if _, tooBig := errors.AsType[*http.MaxBytesError](err); tooBig {
			failErr(c, auth.ErrAvatarTooBig)
			return
		}
		fail(c, http.StatusBadRequest, "bad_request")
		return
	}
	f, err := fh.Open()
	if err != nil {
		failErr(c, err)
		return
	}
	defer func() { _ = f.Close() }()
	u, err := s.svc.SetAvatar(c.Request.Context(), c.GetInt64("uid"), f)
	if err != nil {
		failErr(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"user": u})
}

func (s *Server) deleteAvatar(c *gin.Context) {
	u, err := s.svc.DeleteAvatar(c.Request.Context(), c.GetInt64("uid"))
	if err != nil {
		failErr(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"user": u})
}

// avatar отдаёт картинку по случайному ключу. Ключ меняется при каждой загрузке, поэтому кеш — навсегда.
func (s *Server) avatar(c *gin.Context) {
	png, err := s.svc.Avatar(c.Request.Context(), c.Param("key"))
	if err != nil {
		failErr(c, err)
		return
	}
	c.Header("Cache-Control", "public, max-age=31536000, immutable")
	c.Header("X-Content-Type-Options", "nosniff")
	c.Header("Content-Security-Policy", "default-src 'none'")
	c.Data(http.StatusOK, "image/png", png)
}
