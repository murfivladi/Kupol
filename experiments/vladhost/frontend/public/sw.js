// Service worker панели: панель открывается и без сети (показывает сохранённую оболочку и сообщение «нет подключения»),
// а файлы сборки после первого визита грузятся из кеша.
// Правила: /api/* никогда не кешируется (данные аккаунта и токены); страницы — «сначала сеть», при обрыве — сохранённый
// index.html; /assets/* (имена с хешем, неизменны) — «сначала кеш». Обновление: новая версия ждёт, пока пользователь
// не согласится перезагрузить (сообщение SKIP_WAITING), чтобы не сломать открытую вкладку посреди работы.
const VERSION = 'v1'
const SHELL = `vh-shell-${VERSION}`
const ASSETS = `vh-assets-${VERSION}`
const ASSETS_MAX = 120 // после деплоев копятся старые файлы с другими хешами: держим только последние
const PRECACHE = ['/', '/manifest.webmanifest', '/favicon.svg', '/icons/icon-192.png']

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(SHELL).then((c) => c.addAll(PRECACHE)))
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      for (const key of await caches.keys()) {
        if (key !== SHELL && key !== ASSETS) await caches.delete(key)
      }
      await self.clients.claim()
    })(),
  )
})

self.addEventListener('message', (event) => {
  if (event.data === 'SKIP_WAITING') self.skipWaiting()
})

async function networkFirstPage(request) {
  const cache = await caches.open(SHELL)
  try {
    const res = await fetch(request)
    // Все страницы панели — один index.html (SPA): сохраняем его под «/», чтобы открыть любую страницу без сети.
    if (res.ok && res.type === 'basic') await cache.put('/', res.clone())
    return res
  } catch {
    return (await cache.match('/')) ?? Response.error()
  }
}

async function cacheFirstAsset(request) {
  const cache = await caches.open(ASSETS)
  const hit = await cache.match(request)
  if (hit) return hit
  const res = await fetch(request)
  if (res.ok && res.type === 'basic') {
    await cache.put(request, res.clone())
    const keys = await cache.keys() // в порядке добавления: самые старые — первыми
    for (const k of keys.slice(0, Math.max(0, keys.length - ASSETS_MAX))) await cache.delete(k)
  }
  return res
}

self.addEventListener('fetch', (event) => {
  const { request } = event
  if (request.method !== 'GET') return
  const url = new URL(request.url)
  if (url.origin !== self.location.origin || url.pathname.startsWith('/api/')) return
  if (request.mode === 'navigate') {
    event.respondWith(networkFirstPage(request))
  } else if (url.pathname.startsWith('/assets/')) {
    event.respondWith(cacheFirstAsset(request))
  }
})
