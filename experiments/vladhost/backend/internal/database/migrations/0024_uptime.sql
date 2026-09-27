-- +goose Up
-- Мониторинг доступности сайта: панель периодически открывает сайт и при сбое пишет владельцу.
-- state: unknown (ещё нет вывода) | up | down; сбоем считается несколько неудачных проверок подряд.
CREATE TABLE site_monitors (
    site_id         BIGINT PRIMARY KEY REFERENCES sites (id) ON DELETE CASCADE,
    user_id         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    enabled         BOOLEAN     NOT NULL DEFAULT true,
    path            TEXT        NOT NULL DEFAULT '/',
    notify          BOOLEAN     NOT NULL DEFAULT true,
    public          BOOLEAN     NOT NULL DEFAULT false, -- открытая статус-страница /status/адрес
    state           TEXT        NOT NULL DEFAULT 'unknown',
    state_since     TIMESTAMPTZ,
    fail_streak     INT         NOT NULL DEFAULT 0,
    last_checked_at TIMESTAMPTZ,
    last_code       INT         NOT NULL DEFAULT 0,
    last_ms         INT         NOT NULL DEFAULT 0,
    last_error      TEXT        NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX site_monitors_due_idx ON site_monitors (last_checked_at) WHERE enabled;

-- Результаты проверок (хранятся 30 дней): из них считаются аптайм и время ответа.
CREATE TABLE site_checks (
    id         BIGSERIAL PRIMARY KEY,
    site_id    BIGINT      NOT NULL REFERENCES sites (id) ON DELETE CASCADE,
    checked_at TIMESTAMPTZ NOT NULL,
    ok         BOOLEAN     NOT NULL,
    code       INT         NOT NULL DEFAULT 0,
    ms         INT         NOT NULL DEFAULT 0,
    error      TEXT        NOT NULL DEFAULT ''
);
CREATE INDEX site_checks_site_idx ON site_checks (site_id, checked_at);

-- Сбои: от перехода в down до восстановления (ended_at NULL — сбой продолжается). Хранятся 90 дней.
CREATE TABLE site_incidents (
    id         BIGSERIAL PRIMARY KEY,
    site_id    BIGINT      NOT NULL REFERENCES sites (id) ON DELETE CASCADE,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at   TIMESTAMPTZ,
    error      TEXT        NOT NULL DEFAULT ''
);
CREATE INDEX site_incidents_site_idx ON site_incidents (site_id, started_at DESC);

-- +goose Down
DROP TABLE site_incidents;
DROP TABLE site_checks;
DROP TABLE site_monitors;
