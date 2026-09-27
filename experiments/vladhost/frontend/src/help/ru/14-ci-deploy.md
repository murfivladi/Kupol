---
title: Деплой из CI по API-токену
category: sites
description: Автоматическая выкладка сайта из GitHub Actions, GitLab CI и других систем.
---
## Что это

API-токен — ключ, с которым сборка в CI сама выкладывает сайт после каждого пуша. Токен умеет только загружать архив на сайт: войти в панель, увидеть файлы или сменить пароль с ним нельзя. Вход по коду (2FA) для него не нужен.

## Создание токена

1. Откройте [Настройки](/settings) → «API-токены» и нажмите «+».
2. Задайте название (например, «GitHub Actions»), выберите сайт и срок действия. Токен одного сайта безопаснее: при утечке пострадает только этот сайт.
3. Скопируйте токен: **он показывается один раз**. Сохраните его в секретах CI под именем `VLADHOST_TOKEN`.

В списке видно, когда и с какого адреса токеном пользовались в последний раз. Ненужный или засвеченный токен отзовите кнопкой «Отозвать» — он перестанет работать сразу. Каждый деплой по токену записывается в [Журнал действий](/activity).

## Запрос

Деплой — один POST-запрос с zip-архивом, как при загрузке в панели: папка сайта целиком заменяется содержимым архива.

```
cd dist && zip -qr ../site.zip . && cd ..
curl -fsS -H "Authorization: Bearer $VLADHOST_TOKEN" \
  -F file=@site.zip "https://app.vladinc.ru/api/ci/deploy?site=blog"
```

Параметр `site` — имя сайта (`blog`) или его адрес (`blog.ivan.vladinc.ru`). Для токена одного сайта его можно не указывать. В ответе — данные сайта; при ошибке код ответа не 200 и текст ошибки в поле `error.message`.

## GitHub Actions

Файл `.github/workflows/deploy.yml`:

```
name: deploy
on:
  push:
    branches: [main]
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: npm ci && npm run build
      - name: Выложить на Vladhost
        env:
          VLADHOST_TOKEN: ${{ secrets.VLADHOST_TOKEN }}
        run: |
          cd dist && zip -qr ../site.zip . && cd ..
          curl -fsS -H "Authorization: Bearer $VLADHOST_TOKEN" \
            -F file=@site.zip "https://app.vladinc.ru/api/ci/deploy"
```

Токен добавьте в репозитории: Settings → Secrets and variables → Actions → New repository secret.

## Команда vladhost deploy

Если в CI есть программа `vladhost`, архив собирать не нужно — она упакует папку сама:

```
VLADHOST_TOKEN=vht_… vladhost deploy --site blog ./dist
```

Токен берётся только из переменной `VLADHOST_TOKEN`, чтобы он не попал в журнал сборки.

## Ограничения

Действуют те же правила, что и при загрузке в панели: размер архива не больше квоты диска, симлинки и служебные файлы не выкладываются. На аккаунт — до 20 токенов, срок действия — до года или без срока. Число запросов с одного адреса ограничено.
