import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { startFakeHelper } from './helpers'

test.use({ actionTimeout: 10_000 })

// Собственный DNS: зона своего домена, записи, проверка делегирования и подсказка в разделе «Почта». Исполнителя (root-скрипт) играет тест:
// он подтверждает заявки, а собранное панелью состояние читаем из файла.
test('DNS: зона, записи, делегирование и почта', async ({ page, request }) => {
  test.setTimeout(120_000)
  const timer = startFakeHelper([])
  let token = ''
  let siteId = 0
  const domain = `dn${Date.now().toString(36)}.example.net`
  try {
    const admin = process.env.E2E_ADMIN!
    token = (await (await request.post('/api/auth/login', { data: { login: admin, password: process.env.E2E_ADMIN_PASSWORD } })).json()).access_token as string
    const auth = { Authorization: `Bearer ${token}` }
    const slug = `dn${Date.now().toString(36)}`.slice(0, 20)
    siteId = (await (await request.post('/api/sites', { data: { slug }, headers: auth })).json()).site.id as number
    expect((await request.post(`/api/sites/${siteId}/domains`, { data: { host: domain }, headers: auth })).status()).toBe(201)

    await page.goto('/login')
    await page.getByLabel('Email или имя').fill(admin)
    await page.getByLabel('Пароль').fill(process.env.E2E_ADMIN_PASSWORD!)
    await page.getByRole('button', { name: 'Войти' }).click()
    await expect(page.getByText(`Здравствуйте, ${admin}`)).toBeVisible()

    await page.getByRole('navigation', { name: 'Основное меню' }).getByText('DNS', { exact: true }).click()
    await expect(page.getByRole('heading', { name: 'DNS', exact: true })).toBeVisible()
    await expect(page.getByTestId('dns-ns')).toContainText('ns.example.test')
    await expect(page.getByTestId('dns-ns')).toContainText('ns2.example.test')

    // Зона своего домена: сразу с записями для сайта
    await page.getByTestId('dns-zone-add').click()
    await page.getByTestId('dns-domain-input').fill(domain)
    await page.getByTestId('dns-zone-submit').click()
    const zone = page.getByTestId(`dns-zone-${domain}`)
    await expect(zone).toBeVisible({ timeout: 30_000 })
    await expect(zone.getByTestId('dns-record-@-A')).toContainText('203.0.113.10')
    await expect(zone.getByTestId('dns-record-www-CNAME')).toContainText(domain)

    // Запись: проверка ввода, добавление, изменение
    const dlg = page.getByRole('dialog')
    await zone.getByTestId(`dns-record-add-${domain}`).click()
    await dlg.getByRole('textbox', { name: 'Значение' }).fill('999.1.1.1')
    await dlg.getByTestId(`dns-save-${domain}`).click()
    await expect(dlg.getByText('Некорректное значение записи для этого типа')).toBeVisible()
    await dlg.getByRole('textbox', { name: 'Имя' }).fill('Blog')
    await dlg.getByRole('textbox', { name: 'Значение' }).fill('203.0.113.50')
    await dlg.getByTestId(`dns-save-${domain}`).click()
    await expect(zone.getByTestId('dns-record-blog-A')).toContainText('203.0.113.50')
    await zone.getByTestId('dns-record-blog-A').getByRole('button', { name: 'Изменить' }).click()
    await dlg.getByRole('textbox', { name: 'Значение' }).fill('203.0.113.51')
    await dlg.getByTestId(`dns-save-${domain}`).click()
    await expect(zone.getByTestId('dns-record-blog-A')).toContainText('203.0.113.51')
    // CNAME рядом с другими записями запрещён, а MX принимает приоритет
    await zone.getByTestId(`dns-record-add-${domain}`).click()
    await dlg.getByTestId(`dns-type-${domain}`).click()
    await page.locator('.n-base-select-option', { hasText: /^MX$/ }).click()
    await dlg.getByRole('textbox', { name: 'Имя' }).fill('')
    await dlg.getByRole('textbox', { name: 'Значение' }).fill('mail.vladinc.ru')
    await dlg.getByTestId(`dns-save-${domain}`).click()
    await expect(zone.getByTestId('dns-record--MX').or(zone.getByTestId('dns-record-@-MX'))).toContainText('10 mail.vladinc.ru')

    // Состояние панели попало на сервер файлом
    const state = readFileSync('/tmp/vh-e2e-runtime/dns/state.json', 'utf8')
    expect(state).toContain(`"name":"${domain}"`)
    expect(state).toContain('"value":"203.0.113.51"')
    expect(state).toContain('"nameservers":["ns.example.test","ns2.example.test"]')

    // Делегирование: домен пока не на наших серверах
    await zone.getByTestId(`dns-check-${domain}`).click()
    await expect(page.getByTestId(`dns-state-${domain}`)).toContainText('домен ещё не на наших серверах имён')

    // Почта: зона есть, а домен ещё не на наших серверах — кнопки нет, есть подсказка
    await page.goto('/mail')
    await expect(page.getByRole('heading', { name: 'Почта на своих доменах' })).toBeVisible()
    await page.getByTestId('mail-domain-add').click()
    await page.getByTestId('mail-domain-input').fill(domain)
    await page.getByTestId('mail-domain-submit').click()
    const auto = page.getByTestId(`dns-auto-${domain}`)
    await expect(auto).toContainText('Зона домена у нас уже есть', { timeout: 30_000 })
    await expect(auto).toContainText('ns.example.test, ns2.example.test')
    await expect(page.getByTestId(`dns-auto-button-${domain}`)).toHaveCount(0)

    // Удаление зоны
    await page.goto('/dns')
    const again = page.getByTestId(`dns-zone-${domain}`)
    await again.getByRole('button', { name: 'Удалить зону' }).click()
    await page.getByRole('button', { name: 'Подтвердить' }).click()
    await expect(again).toHaveCount(0)
    await expect(page.getByTestId('dns-zone-add')).toBeVisible()
  } finally {
    if (token) {
      const auth = { Authorization: `Bearer ${token}` }
      try {
        const mail = await request.get('/api/mail', { headers: auth, timeout: 10_000 })
        if (mail.ok()) for (const d of (await mail.json()).domains as { id: number }[]) await request.delete(`/api/mail/domains/${d.id}`, { headers: auth, timeout: 10_000 })
        const dns = await request.get('/api/dns', { headers: auth, timeout: 10_000 })
        if (dns.ok()) for (const z of (await dns.json()).zones as { id: number }[]) await request.delete(`/api/dns/zones/${z.id}`, { headers: auth, timeout: 10_000 })
        if (siteId) await request.delete(`/api/sites/${siteId}`, { headers: auth, timeout: 10_000 })
      } catch {
        // сервер стенда пересоздаётся на каждый прогон
      }
    }
    clearInterval(timer)
  }
})

// Домен, не подключённый ни к одному сайту, добавляется прямо в разделе: зона ждёт подтверждения владения TXT-записью.
test('DNS и почта: произвольный домен ждёт подтверждения владения', async ({ page, request }) => {
  test.setTimeout(90_000)
  const admin = process.env.E2E_ADMIN!
  const domain = `free${Date.now().toString(36)}.example.org`
  await page.goto('/login')
  await page.getByLabel('Email или имя').fill(admin)
  await page.getByLabel('Пароль').fill(process.env.E2E_ADMIN_PASSWORD!)
  await page.getByRole('button', { name: 'Войти' }).click()
  await expect(page.getByText(`Здравствуйте, ${admin}`)).toBeVisible()

  await page.goto('/dns')
  await page.getByTestId('dns-zone-add').click()
  await page.getByTestId('dns-domain-input').fill(domain)
  await page.getByTestId('dns-zone-submit').click()
  const zone = page.getByTestId(`dns-zone-${domain}`)
  await expect(zone).toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId(`dns-pending-${domain}`)).toBeVisible()
  await expect(page.getByTestId(`dns-verify-${domain}`)).toContainText('_vladhost-verify')
  await page.getByTestId(`dns-verify-btn-${domain}`).click()
  await expect(page.getByText('Владение доменом ещё не подтверждено').first()).toBeVisible()

  await page.goto('/mail')
  await page.getByTestId('mail-domain-add').click()
  await page.getByTestId('mail-domain-input').fill(domain)
  await page.getByTestId('mail-domain-submit').click()
  await expect(page.getByTestId(`mail-pending-${domain}`)).toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId(`mail-verify-${domain}`)).toContainText('_vladhost-verify')
  await page.getByTestId(`mail-verify-btn-${domain}`).click()
  await expect(page.getByText('Владение доменом ещё не подтверждено').first()).toBeVisible()

  // уборка
  const token = (await (await request.post('/api/auth/login', { data: { login: admin, password: process.env.E2E_ADMIN_PASSWORD } })).json()).access_token as string
  const auth = { Authorization: `Bearer ${token}` }
  for (const d of (await (await request.get('/api/mail', { headers: auth })).json()).domains as { id: number }[]) await request.delete(`/api/mail/domains/${d.id}`, { headers: auth })
  for (const z of (await (await request.get('/api/dns', { headers: auth })).json()).zones as { id: number }[]) await request.delete(`/api/dns/zones/${z.id}`, { headers: auth })
})
