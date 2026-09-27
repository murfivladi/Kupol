package main

import (
	"archive/zip"
	"cmp"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"mime/multipart"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// runDeploy — клиентская команда для CI: vladhost deploy [--site ИМЯ] [--api АДРЕС] ПАПКА|АРХИВ.zip.
// Токен берётся из VLADHOST_TOKEN (не из аргументов: так он не попадёт в список процессов и журнал CI).
// Папка упаковывается в zip на лету; панели и базы данных команде не нужно.
func runDeploy(args []string) error {
	fl := flag.NewFlagSet("deploy", flag.ContinueOnError)
	site := fl.String("site", os.Getenv("VLADHOST_SITE"), "имя сайта или его адрес (для токена одного сайта можно не указывать)")
	api := fl.String("api", cmp.Or(os.Getenv("VLADHOST_API"), "https://app.vladinc.ru"), "адрес панели")
	if err := fl.Parse(args); err != nil {
		return err
	}
	if fl.NArg() != 1 {
		return errors.New("использование: VLADHOST_TOKEN=vht_… vladhost deploy [--site ИМЯ] [--api АДРЕС] ПАПКА|АРХИВ.zip")
	}
	token := strings.TrimSpace(os.Getenv("VLADHOST_TOKEN"))
	if token == "" {
		return errors.New("не задан VLADHOST_TOKEN: создайте токен в панели (Настройки → API-токены)")
	}
	archive, cleanup, err := deployArchive(fl.Arg(0))
	if err != nil {
		return err
	}
	defer cleanup()

	endpoint := strings.TrimRight(*api, "/") + "/api/ci/deploy"
	if *site != "" {
		endpoint += "?site=" + url.QueryEscape(*site)
	}
	host, err := uploadDeploy(endpoint, token, archive)
	if err != nil {
		return err
	}
	fmt.Println("опубликовано: https://" + host)
	return nil
}

// deployArchive возвращает путь к zip: готовый архив как есть, папку — упакованной во временный файл.
func deployArchive(path string) (string, func(), error) {
	st, err := os.Stat(path)
	if err != nil {
		return "", nil, err
	}
	if !st.IsDir() {
		return path, func() {}, nil
	}
	f, err := os.CreateTemp("", "vladhost-deploy-*.zip")
	if err != nil {
		return "", nil, err
	}
	cleanup := func() { _ = os.Remove(f.Name()) }
	if err := zipDir(path, f); err != nil {
		_ = f.Close()
		cleanup()
		return "", nil, err
	}
	if err := f.Close(); err != nil {
		cleanup()
		return "", nil, err
	}
	return f.Name(), cleanup, nil
}

// zipDir пакует содержимое папки (без самой папки в путях). Симлинки пропускаются: шлюз их всё равно не отдаёт.
func zipDir(root string, w io.Writer) error {
	zw := zip.NewWriter(w)
	err := filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if p == root || d.Type()&fs.ModeSymlink != 0 {
			return nil
		}
		rel, err := filepath.Rel(root, p)
		if err != nil {
			return err
		}
		name := filepath.ToSlash(rel)
		if d.IsDir() {
			_, err := zw.Create(name + "/")
			return err
		}
		if !d.Type().IsRegular() {
			return nil
		}
		dst, err := zw.CreateHeader(&zip.FileHeader{Name: name, Method: zip.Deflate, Modified: time.Now()})
		if err != nil {
			return err
		}
		src, err := os.Open(p)
		if err != nil {
			return err
		}
		defer func() { _ = src.Close() }()
		_, err = io.Copy(dst, src)
		return err
	})
	if err != nil {
		return err
	}
	return zw.Close()
}

// uploadDeploy отправляет архив потоком (без чтения целиком в память) и возвращает адрес сайта.
func uploadDeploy(endpoint, token, archive string) (string, error) {
	f, err := os.Open(archive)
	if err != nil {
		return "", err
	}
	defer func() { _ = f.Close() }()
	pr, pw := io.Pipe()
	mw := multipart.NewWriter(pw)
	go func() {
		part, err := mw.CreateFormFile("file", "site.zip")
		if err == nil {
			_, err = io.Copy(part, f)
		}
		if err == nil {
			err = mw.Close()
		}
		_ = pw.CloseWithError(err)
	}()
	req, err := http.NewRequest(http.MethodPost, endpoint, pr)
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Accept-Language", "ru")
	resp, err := (&http.Client{Timeout: 10 * time.Minute}).Do(req)
	if err != nil {
		return "", err
	}
	defer func() { _ = resp.Body.Close() }()
	var body struct {
		Site struct {
			Host string `json:"host"`
		} `json:"site"`
		Error struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	_ = json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&body)
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("панель ответила %d: %s", resp.StatusCode, cmp.Or(body.Error.Message, resp.Status))
	}
	return body.Site.Host, nil
}
