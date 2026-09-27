-- +goose Up
-- API-токены для деплоя из CI: хранится только хеш, сам токен показывается один раз при создании.
-- site_id — токен действует только для этого сайта (NULL — для любого сайта владельца); с удалением сайта токен удаляется.
CREATE TABLE api_tokens (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    site_id      BIGINT      REFERENCES sites (id) ON DELETE CASCADE,
    name         TEXT        NOT NULL,
    token_hash   TEXT        NOT NULL UNIQUE,
    prefix       TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    last_used_ip TEXT        NOT NULL DEFAULT ''
);
CREATE INDEX api_tokens_user_idx ON api_tokens (user_id);

-- +goose Down
DROP TABLE api_tokens;
