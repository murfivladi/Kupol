import { expect, test } from '@playwright/test'

// PWA на production-сборке: манифест с иконками, service worker берёт страницу под контроль,
// и без сети панель открывается из кеша с полоской «нет подключения». /api при этом не кешируется.
test('панель устанавливается как приложение и открывается без сети', async ({ page, context }) => {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'С возвращением' })).toBeVisible()

  const manifest = await page.evaluate(async () => {
    const href = document.querySelector<HTMLLinkElement>('link[rel="manifest"]')!.href
    const res = await fetch(href)
    return { ok: res.ok, body: await res.json() }
  })
  expect(manifest.ok).toBe(true)
  expect(manifest.body.display).toBe('standalone')
  for (const icon of manifest.body.icons as { src: string }[]) {
    expect((await page.request.get(icon.src)).ok(), icon.src).toBe(true)
  }

  // Первый визит ставит worker; после перезагрузки страница уже под его контролем.
  await page.evaluate(() => navigator.serviceWorker.ready)
  await page.reload()
  await expect.poll(() => page.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true)

  await context.setOffline(true)
  await page.goto('/sites/1')
  await expect(page.getByText('Нет подключения к интернету')).toBeVisible()
  const apiCached = await page.evaluate(async () => {
    for (const key of await caches.keys()) {
      for (const req of await (await caches.open(key)).keys()) if (new URL(req.url).pathname.startsWith('/api/')) return true
    }
    return false
  })
  expect(apiCached).toBe(false)
  await context.setOffline(false)
})
