package httpapi

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"vladhost/internal/activity"
	"vladhost/internal/admin"
	"vladhost/internal/auth"
	"vladhost/internal/sites"
)

// WithAdmin подключает раздел администратора (пользователи, жалобы). Без него — только инвайты.
func WithAdmin(svc *admin.Service) Option { return func(s *Server) { s.admin = svc } }

func (s *Server) requireAdminSvc(c *gin.Context) {
	if s.admin == nil {
		fail(c, http.StatusNotFound, "not_found")
		return
	}
	c.Next()
}

func paramID(c *gin.Context, name string, notFound error) (int64, bool) {
	id, err := strconv.ParseInt(c.Param(name), 10, 64)
	if err != nil || id <= 0 {
		failErr(c, notFound)
		return 0, false
	}
	return id, true
}

// recordFor пишет событие в журнал пользователя, над аккаунтом которого действовал администратор:
// человек должен видеть, что и когда с его аккаунтом сделали. Объект — имя администратора.
func (s *Server) recordFor(c *gin.Context, userID int64, kind, target string) {
	if s.activity == nil || userID == 0 {
		return
	}
	s.activity.Record(c.Request.Context(), activity.Input{UserID: userID, Kind: kind, Target: target})
}

func (s *Server) adminName(c *gin.Context) string {
	if u, err := s.svc.UserByID(c.Request.Context(), c.GetInt64("uid")); err == nil {
		return u.Username
	}
	return ""
}

func (s *Server) adminUsers(c *gin.Context) {
	offset, _ := strconv.Atoi(c.Query("offset"))
	rows, total, err := s.admin.Users(c.Request.Context(), admin.Filter{Query: c.Query("q"), Status: c.Query("status"), Offset: offset})
	if err != nil {
		failErr(c, err)
		return
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(http.StatusOK, gin.H{"users": rows, "total": total})
}

func (s *Server) adminUser(c *gin.Context) {
	id, ok := paramID(c, "uid", auth.ErrUserNotFound)
	if !ok {
		return
	}
	d, err := s.admin.Detail(c.Request.Context(), id)
	if err != nil {
		failErr(c, err)
		return
	}
	sitesJSON := make([]siteJSON, 0, len(d.Sites))
	for _, st := range d.Sites {
		sitesJSON = append(sitesJSON, s.toJSON(st))
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(http.StatusOK, gin.H{"user": d.User, "sites": sitesJSON,
		"limits":   gin.H{"max_sites": d.Limits.MaxSites, "disk_quota_bytes": d.Limits.DiskQuotaBytes},
		"defaults": gin.H{"max_sites": d.Defaults.MaxSites, "disk_quota_bytes": d.Defaults.DiskQuotaBytes}})
}

// adminUserAction — общее для действий над аккаунтом: выполнить, записать в оба журнала, вернуть пользователя.
func (s *Server) adminUserAction(c *gin.Context, userKind string, do func(id int64) (*auth.User, error)) {
	id, ok := paramID(c, "uid", auth.ErrUserNotFound)
	if !ok {
		return
	}
	if id == c.GetInt64("uid") {
		fail(c, http.StatusConflict, "admin_self")
		return
	}
	u, err := do(id)
	if err != nil {
		failErr(c, err)
		return
	}
	setAuditTarget(c, u.Username)
	s.recordFor(c, u.ID, userKind, s.adminName(c))
	c.JSON(http.StatusOK, gin.H{"user": u})
}

func (s *Server) adminBlock(c *gin.Context) {
	var in struct {
		Reason string `json:"reason"`
	}
	if c.ShouldBindJSON(&in) != nil {
		fail(c, http.StatusBadRequest, "bad_request")
		return
	}
	s.adminUserAction(c, activity.KindAccountBlocked, func(id int64) (*auth.User, error) { return s.admin.Block(c.Request.Context(), id, in.Reason) })
}

func (s *Server) adminUnblock(c *gin.Context) {
	s.adminUserAction(c, activity.KindAccountUnblocked, func(id int64) (*auth.User, error) { return s.admin.Unblock(c.Request.Context(), id) })
}

func (s *Server) adminLimits(c *gin.Context) {
	var in struct {
		MaxSites       *int   `json:"max_sites"`
		DiskQuotaBytes *int64 `json:"disk_quota_bytes"`
	}
	if c.ShouldBindJSON(&in) != nil {
		fail(c, http.StatusBadRequest, "bad_request")
		return
	}
	id, ok := paramID(c, "uid", auth.ErrUserNotFound)
	if !ok {
		return
	}
	// Лимиты можно менять и себе: это не опасное действие (в отличие от блокировки).
	u, err := s.admin.SetLimits(c.Request.Context(), id, in.MaxSites, in.DiskQuotaBytes)
	if err != nil {
		failErr(c, err)
		return
	}
	setAuditTarget(c, u.Username)
	s.recordFor(c, u.ID, activity.KindAccountLimits, s.adminName(c))
	c.JSON(http.StatusOK, gin.H{"user": u})
}

func (s *Server) adminReset2FA(c *gin.Context) {
	s.adminUserAction(c, activity.KindAccount2FAReset, func(id int64) (*auth.User, error) { return s.admin.ResetTwoFactor(c.Request.Context(), id) })
}

// adminUserActivity — журнал действий пользователя глазами администратора (разбор жалоб и взломов).
func (s *Server) adminUserActivity(c *gin.Context) {
	if s.activity == nil {
		fail(c, http.StatusNotFound, "not_found")
		return
	}
	id, ok := paramID(c, "uid", auth.ErrUserNotFound)
	if !ok {
		return
	}
	before, _ := strconv.ParseInt(c.Query("before"), 10, 64)
	events, next, err := s.activity.List(c.Request.Context(), id, c.Query("category"), before, 50)
	if err != nil {
		failErr(c, err)
		return
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(http.StatusOK, gin.H{"events": events, "next": next})
}

func (s *Server) adminSuspendSite(c *gin.Context) {
	var in struct {
		Reason string `json:"reason"`
	}
	if c.ShouldBindJSON(&in) != nil {
		fail(c, http.StatusBadRequest, "bad_request")
		return
	}
	s.adminSiteAction(c, activity.KindSiteSuspended, func(id int64) (*sites.Site, error) { return s.admin.SuspendSite(c.Request.Context(), id, in.Reason) })
}

func (s *Server) adminUnsuspendSite(c *gin.Context) {
	s.adminSiteAction(c, activity.KindSiteUnsuspended, func(id int64) (*sites.Site, error) { return s.admin.UnsuspendSite(c.Request.Context(), id) })
}

func (s *Server) adminSiteAction(c *gin.Context, userKind string, do func(id int64) (*sites.Site, error)) {
	id, ok := paramID(c, "sid", sites.ErrNotFound)
	if !ok {
		return
	}
	site, err := do(id)
	if err != nil {
		failErr(c, err)
		return
	}
	setAuditTarget(c, site.Host)
	s.recordFor(c, site.UserID, userKind, site.Host)
	c.JSON(http.StatusOK, gin.H{"site": s.toJSON(*site)})
}

func (s *Server) adminAbuse(c *gin.Context) {
	offset, _ := strconv.Atoi(c.Query("offset"))
	list, total, err := s.admin.AbuseReports(c.Request.Context(), c.Query("status"), offset)
	if err != nil {
		failErr(c, err)
		return
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(http.StatusOK, gin.H{"reports": list, "total": total})
}

func (s *Server) adminAbuseStatus(c *gin.Context) {
	var in struct {
		Status string `json:"status"`
		Note   string `json:"note"`
	}
	if c.ShouldBindJSON(&in) != nil {
		fail(c, http.StatusBadRequest, "bad_request")
		return
	}
	id, ok := paramID(c, "aid", admin.ErrAbuseNotFound)
	if !ok {
		return
	}
	r, err := s.admin.SetAbuseStatus(c.Request.Context(), c.GetInt64("uid"), id, in.Status, in.Note)
	if err != nil {
		failErr(c, err)
		return
	}
	setAuditTarget(c, r.Host)
	c.JSON(http.StatusOK, gin.H{"report": r})
}

// adminSummary — счётчики для меню администратора.
func (s *Server) adminSummary(c *gin.Context) {
	n, err := s.admin.NewAbuseCount(c.Request.Context())
	if err != nil {
		failErr(c, err)
		return
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(http.StatusOK, gin.H{"abuse_new": n})
}

// reportAbuse — открытая форма жалобы (без входа). Администраторам уходит письмо, если адрес — наш сайт.
func (s *Server) reportAbuse(c *gin.Context) {
	var in struct {
		URL      string `json:"url"`
		Category string `json:"category"`
		Message  string `json:"message"`
		Email    string `json:"email"`
	}
	if c.ShouldBindJSON(&in) != nil {
		fail(c, http.StatusBadRequest, "bad_request")
		return
	}
	r, err := s.admin.ReportAbuse(c.Request.Context(), admin.AbuseInput{URL: in.URL, Category: in.Category, Message: in.Message, Email: in.Email, IP: c.ClientIP()})
	if err != nil {
		failErr(c, err)
		return
	}
	s.mail.AbuseNew(c.Request.Context(), r.ID, r.URL, r.Category, r.Message, r.SiteID != nil)
	c.JSON(http.StatusCreated, gin.H{"id": r.ID})
}
