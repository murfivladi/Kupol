package httpapi

import (
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"

	"vladhost/internal/activity"
	"vladhost/internal/auth"
	"vladhost/internal/sites"
)

type apiTokenJSON struct {
	auth.APIToken
	SiteHost string `json:"site_host"` // пусто — токен для любого сайта
}

func (s *Server) apiTokensJSON(c *gin.Context, list []auth.APIToken) []apiTokenJSON {
	hosts := map[int64]string{}
	if own, err := s.sites.List(c.Request.Context(), c.GetInt64("uid")); err == nil {
		for _, st := range own {
			hosts[st.ID] = st.Host
		}
	}
	out := make([]apiTokenJSON, 0, len(list))
	for _, t := range list {
		j := apiTokenJSON{APIToken: t}
		if t.SiteID != nil {
			j.SiteHost = hosts[*t.SiteID]
		}
		out = append(out, j)
	}
	return out
}

func (s *Server) listAPITokens(c *gin.Context) {
	list, err := s.svc.ListAPITokens(c.Request.Context(), c.GetInt64("uid"))
	if err != nil {
		failErr(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"tokens": s.apiTokensJSON(c, list)})
}

func (s *Server) createAPIToken(c *gin.Context) {
	var in struct {
		Name        string `json:"name"`
		SiteID      *int64 `json:"site_id"`
		ExpiresDays int    `json:"expires_days"`
	}
	if c.ShouldBindJSON(&in) != nil {
		fail(c, http.StatusBadRequest, "bad_request")
		return
	}
	uid := c.GetInt64("uid")
	if in.SiteID != nil {
		if _, err := s.sites.Get(c.Request.Context(), uid, *in.SiteID); err != nil {
			failErr(c, err)
			return
		}
	}
	t, raw, err := s.svc.CreateAPIToken(c.Request.Context(), uid, auth.APITokenInput{Name: in.Name, SiteID: in.SiteID, ExpiresDays: in.ExpiresDays})
	if err != nil {
		failErr(c, err)
		return
	}
	setAuditTarget(c, t.Name)
	c.Header("Cache-Control", "no-store")
	c.JSON(http.StatusCreated, gin.H{"token": s.apiTokensJSON(c, []auth.APIToken{*t})[0], "secret": raw})
}

func (s *Server) deleteAPIToken(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("tid"), 10, 64)
	if err != nil || id <= 0 {
		failErr(c, auth.ErrAPITokenNotFound)
		return
	}
	t, err := s.svc.DeleteAPIToken(c.Request.Context(), c.GetInt64("uid"), id)
	if err != nil {
		failErr(c, err)
		return
	}
	setAuditTarget(c, t.Name)
	c.Status(http.StatusNoContent)
}

// ciDeploy — деплой из CI по API-токену: POST /api/ci/deploy, заголовок Authorization: Bearer vht_…,
// multipart с полем file (zip) и полем site (имя сайта или адрес). Для токена одного сайта поле site можно не передавать.
func (s *Server) ciDeploy(c *gin.Context) {
	raw, _ := strings.CutPrefix(c.GetHeader("Authorization"), "Bearer ")
	tok, err := s.svc.AuthenticateAPIToken(c.Request.Context(), strings.TrimSpace(raw), c.ClientIP())
	if err != nil {
		failErr(c, err)
		return
	}
	site, err := s.ciSite(c, tok, c.Query("site"))
	if err != nil {
		failErr(c, err)
		return
	}
	deployed := s.receiveDeploy(c, tok.UserID, site.ID)
	if deployed == nil {
		return
	}
	s.record(c, tok.UserID, activity.KindDeployAPI, deployed.Host+" · "+tok.Name)
	c.JSON(http.StatusOK, gin.H{"site": s.toJSON(*deployed)})
}

// ciSite находит сайт для деплоя по токену. Имя сайта берётся из адреса запроса (?site=), а не из тела:
// так проверка идёт до того, как принят архив.
func (s *Server) ciSite(c *gin.Context, tok *auth.APIToken, name string) (*sites.Site, error) {
	name = strings.ToLower(strings.TrimSpace(name))
	if tok.SiteID != nil {
		site, err := s.sites.Get(c.Request.Context(), tok.UserID, *tok.SiteID)
		if err != nil {
			return nil, err
		}
		if name != "" && name != site.Slug && name != site.Host {
			return nil, sites.ErrNotFound // токен выдан для другого сайта
		}
		return site, nil
	}
	if name == "" {
		return nil, sites.ErrNotFound
	}
	own, err := s.sites.List(c.Request.Context(), tok.UserID)
	if err != nil {
		return nil, err
	}
	for i := range own {
		if own[i].Slug == name || own[i].Host == name {
			return &own[i], nil
		}
	}
	return nil, sites.ErrNotFound
}
