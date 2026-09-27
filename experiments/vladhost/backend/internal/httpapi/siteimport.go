package httpapi

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"vladhost/internal/i18n"
	"vladhost/internal/siteimport"
)

// WithImport подключает импорт сайта по ссылке и по FTP. Без него раздел выключен.
func WithImport(svc *siteimport.Service) Option { return func(s *Server) { s.imports = svc } }

func (s *Server) requireImport(c *gin.Context) {
	if s.imports == nil {
		fail(c, http.StatusNotFound, "not_found")
		return
	}
	c.Next()
}

type importJSON struct {
	*siteimport.Job
	Error string `json:"error"` // текст ошибки на языке запроса; пусто — ошибки нет
}

func importToJSON(c *gin.Context, j *siteimport.Job) *importJSON {
	if j == nil {
		return nil
	}
	out := &importJSON{Job: j}
	if e := j.Err(); e != nil {
		out.Error = i18n.T(lang(c), "err."+e.Code, e.Args...)
	}
	return out
}

func (s *Server) getImport(c *gin.Context) {
	id, ok := siteID(c)
	if !ok {
		return
	}
	j, err := s.imports.Latest(c.Request.Context(), c.GetInt64("uid"), id)
	if err != nil {
		failErr(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"job": importToJSON(c, j)})
}

func (s *Server) startImport(c *gin.Context) {
	id, ok := siteID(c)
	if !ok {
		return
	}
	var in struct {
		Kind     string `json:"kind"`
		URL      string `json:"url"`
		Host     string `json:"host"`
		Port     int    `json:"port"`
		Username string `json:"username"`
		Password string `json:"password"`
		Path     string `json:"path"`
		TLS      bool   `json:"tls"`
	}
	if c.ShouldBindJSON(&in) != nil {
		fail(c, http.StatusBadRequest, "bad_request")
		return
	}
	j, err := s.imports.Start(c.Request.Context(), c.GetInt64("uid"), id, siteimport.Source{
		Kind: in.Kind, URL: in.URL, Host: in.Host, Port: in.Port, User: in.Username, Password: in.Password, Path: in.Path, TLS: in.TLS,
	})
	if err != nil {
		failErr(c, err)
		return
	}
	c.JSON(http.StatusAccepted, gin.H{"job": importToJSON(c, j)})
}
