import { expect, test } from '@playwright/test'

// Администрирование: жалоба через открытую форму → приостановка сайта из очереди → блокировка пользователя (он видит причину при входе) → разблокировка.
test('жалоба, приостановка сайта и блокировка пользователя', async ({ page, browser, request }) => {
  test.setTimeout(120_000)
  const admin = process.env.E2E_ADMIN!
  const adminAuth = { Authorization: `Bearer ${(await (await request.post('/api/auth/login', { data: { login: admin, password: process.env.E2E_ADMIN_PASSWORD } })).json()).access_token}` }
  const invite = (await (await request.post('/api/invites', { data: {}, headers: adminAuth })).json()).invite.code as string
  const user = `ab${Date.now().toString(36)}`.slice(0, 20)
  const reg = await (await request.post('/api/auth/register', { data: { invite, email: `${user}@example.com`, username: user, password: 'password123' } })).json()
  const host = (await (await request.post('/api/sites', { data: { slug: 'shop' }, headers: { Authorization: `Bearer ${reg.access_token}` } })).json()).site.host as string

  // Открытая форма жалобы — без входа.
  await page.goto(`/abuse?url=${encodeURIComponent(`https://${host}/login`)}`)
  await page.getByTestId('abuse-category').click()
  await page.getByText('Фишинг (подделка сайта, кража паролей)').click()
  await page.getByLabel('Подробности').fill('Страница притворяется банком и просит пароль')
  await page.getByTestId('abuse-submit').click()
  await expect(page.getByTestId('abuse-sent')).toContainText('принята')

  // Администратор: значок жалоб, очередь, приостановка сайта.
  await page.goto('/login')
  await page.getByLabel('Email или имя').fill(admin)
  await page.getByLabel('Пароль').fill(process.env.E2E_ADMIN_PASSWORD!)
  await page.getByRole('button', { name: 'Войти' }).click()
  const nav = page.getByRole('navigation', { name: 'Основное меню' })
  await expect(nav.getByTestId('abuse-badge')).toBeVisible()
  await nav.getByText('Админка').click()
  await page.locator('.n-tabs-tab', { hasText: 'Жалобы' }).click()
  const report = page.getByTestId('abuse-report').filter({ hasText: host })
  await expect(report).toContainText('Страница притворяется банком')
  await expect(report).toContainText(user)
  await report.getByRole('button', { name: 'Приостановить' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Подтвердить' }).click()
  await expect(report.getByText('приостановлен')).toBeVisible()
  await report.getByRole('button', { name: 'Меры приняты' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Подтвердить' }).click()
  await expect(page.getByTestId('abuse-report').filter({ hasText: host })).toHaveCount(0) // ушла из «Новых»

  // Пользователь: поиск, карточка, блокировка с причиной.
  await page.locator('.n-tabs-tab', { hasText: 'Пользователи' }).click()
  await page.getByLabel('Поиск по имени или почте').fill(user)
  await page.getByTestId('admin-users').getByText(user, { exact: true }).click()
  await expect(page.getByRole('heading', { name: user })).toBeVisible()
  await expect(page.locator('.sites').getByText(host)).toBeVisible()
  await page.getByTestId('admin-block').click()
  await page.getByRole('dialog').getByRole('textbox').fill('рассылка фишинга')
  await page.getByTestId('admin-block-confirm').click()
  await expect(page.getByText(/Заблокирован .*Причина: рассылка фишинга/)).toBeVisible()
  await expect(page.getByTestId('event-site.create')).toBeVisible() // журнал пользователя в карточке

  // Заблокированный видит причину при входе.
  const other = await browser.newPage()
  await other.goto('/login')
  await other.getByLabel('Email или имя').fill(user)
  await other.getByLabel('Пароль').fill('password123')
  await other.getByRole('button', { name: 'Войти' }).click()
  await expect(other.getByText('Аккаунт заблокирован администратором. Причина: рассылка фишинга')).toBeVisible()

  await page.getByTestId('admin-unblock').click()
  await expect(page.getByText(/Причина: рассылка фишинга/)).toHaveCount(0)
  await other.getByRole('button', { name: 'Войти' }).click()
  await expect(other.getByText(`Здравствуйте, ${user}`)).toBeVisible()
  // Сайт остался приостановленным по жалобе — владелец видит причину.
  await other.goto('/sites')
  await other.getByText(host).click()
  await expect(other.getByTestId('site-suspended')).toContainText('Жалоба №')
  await other.close()
})
