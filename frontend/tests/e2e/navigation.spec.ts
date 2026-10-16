import { test, expect } from '@playwright/test'

test.describe('nawigacja', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard')
    await expect(page.getByRole('link', { name: 'Dashboard', exact: true })).toBeVisible()
  })

  test('przejście Dashboard → Upload → Historia → Profil', async ({ page }) => {
    await page.getByRole('link', { name: 'Upload', exact: true }).click()
    await expect(page).toHaveURL(/\/upload$/)
    await expect(page.getByRole('heading', { name: 'Nowa analiza' })).toBeVisible()

    await page.getByRole('link', { name: 'Historia', exact: true }).click()
    await expect(page).toHaveURL(/\/history$/)
    await expect(page.getByRole('heading', { name: 'Historia analiz' })).toBeVisible()

    await page.getByRole('link', { name: 'Profil', exact: true }).click()
    await expect(page).toHaveURL(/\/profile$/)

    await page.getByRole('link', { name: 'DeepfakeDetector' }).click()
    await expect(page).toHaveURL(/\/(dashboard)?$/)
    await expect(page.getByRole('heading', { name: /Cześć/, level: 1 })).toBeVisible()
  })
})
