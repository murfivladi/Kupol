-- +goose Up
-- Импорт сайта: скачивание архива по ссылке или перенос по FTP с другого хостинга. Задача идёт в фоне,
-- панель показывает последнюю. Пароль FTP не хранится: он нужен только на время переноса.
CREATE TABLE site_imports (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    site_id     BIGINT      NOT NULL REFERENCES sites (id) ON DELETE CASCADE,
    kind        TEXT        NOT NULL,            -- url | ftp
    source      TEXT        NOT NULL,            -- ссылка или ftp://логин@сервер:порт/папка (без пароля)
    status      TEXT        NOT NULL,            -- running | done | failed
    error_code  TEXT        NOT NULL DEFAULT '',
    error_args  JSONB       NOT NULL DEFAULT '[]',
    bytes       BIGINT      NOT NULL DEFAULT 0,  -- получено данных
    files       INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
CREATE INDEX site_imports_site_idx ON site_imports (site_id, id DESC);
-- Одновременно у сайта идёт не больше одного импорта.
CREATE UNIQUE INDEX site_imports_running_idx ON site_imports (site_id) WHERE status = 'running';

-- +goose Down
DROP TABLE site_imports;
