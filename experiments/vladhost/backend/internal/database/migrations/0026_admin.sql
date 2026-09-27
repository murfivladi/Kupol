-- +goose Up
-- Администрирование: блокировка аккаунта, личные лимиты, приостановка сайтов и жалобы на контент.
ALTER TABLE users
    ADD COLUMN blocked_at       TIMESTAMPTZ,
    ADD COLUMN blocked_reason   TEXT NOT NULL DEFAULT '',
    ADD COLUMN max_sites        INT,    -- NULL — общий лимит панели
    ADD COLUMN disk_quota_bytes BIGINT; -- NULL — общий лимит панели

-- Приостановленный сайт шлюз не показывает (страница «сайт приостановлен»). by_block — приостановлен вместе с блокировкой
-- аккаунта: при разблокировке такие сайты включаются сами, а приостановленные отдельно (по жалобе) — нет.
ALTER TABLE sites
    ADD COLUMN suspended_at       TIMESTAMPTZ,
    ADD COLUMN suspended_reason   TEXT    NOT NULL DEFAULT '',
    ADD COLUMN suspended_by_block BOOLEAN NOT NULL DEFAULT false;

-- Жалобы на содержимое сайтов: пишет кто угодно (без входа), разбирает администратор.
CREATE TABLE abuse_reports (
    id          BIGSERIAL PRIMARY KEY,
    url         TEXT        NOT NULL,
    host        TEXT        NOT NULL,
    site_id     BIGINT      REFERENCES sites (id) ON DELETE SET NULL, -- NULL — адрес не наш или сайт уже удалён
    category    TEXT        NOT NULL, -- phishing | malware | spam | copyright | illegal | other
    message     TEXT        NOT NULL,
    email       TEXT        NOT NULL DEFAULT '',
    ip          TEXT        NOT NULL DEFAULT '',
    status      TEXT        NOT NULL DEFAULT 'new', -- new | resolved | rejected
    note        TEXT        NOT NULL DEFAULT '',     -- заметка администратора
    resolved_by BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    resolved_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX abuse_reports_status_idx ON abuse_reports (status, id DESC);
CREATE INDEX abuse_reports_ip_idx ON abuse_reports (ip, created_at);

-- +goose Down
DROP TABLE abuse_reports;
ALTER TABLE sites DROP COLUMN suspended_at, DROP COLUMN suspended_reason, DROP COLUMN suspended_by_block;
ALTER TABLE users DROP COLUMN blocked_at, DROP COLUMN blocked_reason, DROP COLUMN max_sites, DROP COLUMN disk_quota_bytes;
