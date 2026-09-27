-- +goose Up
-- Настройки профиля: часовой пояс для дат в панели ('' — как в браузере), тема оформления, аватар.
ALTER TABLE users
    ADD COLUMN timezone   TEXT NOT NULL DEFAULT '',
    ADD COLUMN theme      TEXT NOT NULL DEFAULT 'system', -- system | light | dark
    ADD COLUMN avatar_key TEXT;                           -- ключ картинки в user_avatars; меняется при каждой загрузке

-- Картинка отдельно от users: её не нужно читать при каждом запросе пользователя. Хранится уже обрезанной до квадрата 256×256 PNG.
CREATE TABLE user_avatars (
    user_id    BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    key        TEXT        NOT NULL UNIQUE,
    png        BYTEA       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- +goose Down
DROP TABLE user_avatars;
ALTER TABLE users DROP COLUMN timezone, DROP COLUMN theme, DROP COLUMN avatar_key;
