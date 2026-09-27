import { expect, test } from '@playwright/test'

// Тема и часовой пояс: выбор в настройках применяется сразу и сохраняется в профиле (переживает перезагрузку и новый вход).
test('светлая тема и часовой пояс сохраняются в профиле', async ({ page }) => {
  const admin = process.env.E2E_ADMIN!
  await page.goto('/login')
  await page.getByLabel('Email или имя').fill(admin)
  await page.getByLabel('Пароль').fill(process.env.E2E_ADMIN_PASSWORD!)
  await page.getByRole('button', { name: 'Войти' }).click()
  await expect(page.getByText(`Здравствуйте, ${admin}`)).toBeVisible()
  await page.goto('/settings')
  const html = page.locator('html')

  await page.getByTestId('theme-light').click()
  await expect(html).toHaveAttribute('data-theme', 'light')
  await expect(page.getByText('Настройки сохранены').first()).toBeVisible()

  await page.getByTestId('timezone').click()
  await page.getByTestId('timezone').locator('input').fill('Tokyo')
  await page.locator('.n-base-select-option', { hasText: 'Asia/Tokyo' }).click()
  await expect(page.getByText(/Сейчас:/)).toBeVisible()

  // Сохранено на сервере: без localStorage тема и пояс приходят из профиля.
  await page.evaluate(() => localStorage.removeItem('vh.theme'))
  await page.reload()
  await expect(html).toHaveAttribute('data-theme', 'light')
  await expect(page.getByTestId('timezone')).toContainText('Asia/Tokyo')

  await page.getByTestId('theme-dark').click()
  await expect(html).toHaveAttribute('data-theme', 'dark')
  await page.getByTestId('timezone').click()
  await page.getByTestId('timezone').locator('input').fill('браузере')
  await page.locator('.n-base-select-option', { hasText: 'Как в браузере' }).click()
})
