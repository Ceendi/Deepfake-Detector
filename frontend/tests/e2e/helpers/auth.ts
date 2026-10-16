import { expect, type Page } from '@playwright/test'

import type { TestUser } from '../fixtures/users'

export async function login(page: Page, user: TestUser): Promise<void> {
  await page.goto('/login')

  await page.getByRole('button', { name: 'Zaloguj się', exact: true }).click()

  await page.waitForURL(/\/realms\/deepfake\//)
  await page.locator('#username').fill(user.username)
  await page.locator('#password').fill(user.password)

  await page.locator('#kc-form-login button[type="submit"]').click()

  await expectAuthenticated(page)
}

export async function logout(page: Page): Promise<void> {
  await page.locator('button[aria-haspopup="menu"]').click()
  await page.getByRole('menuitem', { name: 'Wyloguj' }).click()
  await expectOnLoginScreen(page)
}

export async function expectAuthenticated(page: Page): Promise<void> {
  await expect(page.getByRole('link', { name: 'Dashboard', exact: true })).toBeVisible()
}

export async function expectOnLoginScreen(page: Page): Promise<void> {
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('button', { name: 'Zaloguj się', exact: true })).toBeVisible()
}
