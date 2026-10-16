import { test, expect } from '@playwright/test'

import { ALICE, BOB } from './fixtures/users'
import { ANONYMOUS_STATE } from './fixtures/storage'
import { login, logout, expectAuthenticated, expectOnLoginScreen } from './helpers/auth'

test.use({ storageState: ANONYMOUS_STATE })

test.describe('auth', () => {
  test('niezalogowany użytkownik jest przekierowany na /login', async ({ page }) => {
    await page.goto('/dashboard')

    await expectOnLoginScreen(page)
  })

  test.describe('flow logowania', () => {
    test.beforeEach(({ browserName }) => {
      test.skip(browserName !== 'chromium', 'flow logowania tylko na chromium (brute-force KC)')
    })

    test('logowanie zachowuje deep link z query i hash', async ({ page }) => {
      await page.goto('/history?view=all#top')
      await page.getByRole('button', { name: 'Zaloguj się', exact: true }).click()
      await page.waitForURL(/\/realms\/deepfake\//)
      const authorizationUrl = new URL(page.url())
      expect(new URL(authorizationUrl.searchParams.get('redirect_uri')!).hash).toBe('')
      await page.locator('#username').fill(ALICE.username)
      await page.locator('#password').fill(ALICE.password)
      await page.locator('#kc-form-login button[type="submit"]').click()

      await expectAuthenticated(page)

      await expect(page).toHaveURL(/\/history\?view=all#top$/)
      await expect(page.getByRole('heading', { name: 'Historia analiz', level: 1 })).toBeVisible()
    })

    test('wylogowanie wraca na ekran logowania', async ({ page }) => {
      await login(page, BOB)
      await logout(page)
      await expectOnLoginScreen(page)

      await page.goto('/history')
      await expectOnLoginScreen(page)
    })
  })
})
