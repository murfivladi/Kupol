package httpapi

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"

	"vladhost/internal/uptime"
)

// WithUptime подключает мониторинг доступности сайтов и статус-страницы. Без него раздел выключен.
func WithUptime(svc *uptime.Service) Option { return func(s *Server) { s.uptime = svc } }

func (s *Server) requireUptime(c *gin.Context) {
	if s.uptime == nil {
		fail(c, http.StatusNotFound, "not_found")
		return
	}
	c.Next()
}

func (s *Server) getMonitor(c *gin.Context) {
	id, ok := siteID(c)
	if !ok {
		return
	}
	m, site, err := s.uptime.Get(c.Request.Context(), c.GetInt64("uid"), id)
	if err != nil {
		failErr(c, err)
		return
	}
	var rep *uptime.Report
	if m != nil {
		if rep, err = s.uptime.Report(c.Request.Context(), site.ID); err != nil {
			failErr(c, err)
			return
		}
	}
	c.JSON(http.StatusOK, gin.H{"monitor": m, "report": rep, "interval_sec": int(s.uptime.Interval().Seconds())})
}

func (s *Server) putMonitor(c *gin.Context) {
	id, ok := siteID(c)
	if !ok {
		return
	}
	var in struct {
		Enabled bool   `json:"enabled"`
		Path    string `json:"path"`
		Notify  bool   `json:"notify"`
		Public  bool   `json:"public"`
	}
	if c.ShouldBindJSON(&in) != nil {
		fail(c, http.StatusBadRequest, "bad_request")
		return
	}
	m, err := s.uptime.Save(c.Request.Context(), c.GetInt64("uid"), id, uptime.Settings{Enabled: in.Enabled, Path: in.Path, Notify: in.Notify, Public: in.Public})
	if err != nil {
		failErr(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"monitor": m})
}

// publicStatus — открытая статус-страница сайта (без входа). Отдаётся, только если владелец её включил.
func (s *Server) publicStatus(c *gin.Context) {
	st, err := s.uptime.PublicStatus(c.Request.Context(), strings.ToLower(c.Param("host")))
	if err != nil {
		failErr(c, err)
		return
	}
	c.Header("Cache-Control", "public, max-age=30")
	c.JSON(http.StatusOK, gin.H{"status": st})
}
