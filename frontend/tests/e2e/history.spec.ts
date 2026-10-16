import { test, expect } from '@playwright/test'

test.describe('historia', () => {
  test('lista analiz wczytuje się bez błędu', async ({ page }) => {
    await page.goto('/history')
    await expect(page).toHaveURL(/\/history$/)

    await expect(page.getByRole('heading', { name: 'Historia analiz' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Nowa analiza' })).toBeVisible()

    await expect(page.getByText('Nie udało się wczytać historii')).toHaveCount(0)
  })
})
