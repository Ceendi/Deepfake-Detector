import { test, expect } from '@playwright/test'

test.describe('historia', () => {
  test('lista analiz wczytuje się bez błędu', async ({ page }) => {
    const response = page.waitForResponse(
      (r) => r.url().includes('/api/analysis?') && r.request().method() === 'GET',
    )
    await page.goto('/history')
    expect((await response).status()).toBe(200)
    await expect(page.getByRole('heading', { name: 'Historia analiz' })).toBeVisible()
    await expect(
      page.getByText('Brak analiz w historii').or(page.getByText(/Wyświetlasz/)),
    ).toBeVisible()
    await expect(page.getByText('Nie udało się wczytać historii')).toHaveCount(0)
  })

  test('shows a delayed history failure instead of accepting the spinner', async ({ page }) => {
    await page.route('**/api/analysis?*', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 300))
      await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' })
    })
    await page.goto('/history')
    await expect(page.getByText('Nie udało się wczytać historii')).toBeVisible()
    await expect(
      page.getByText('Brak analiz w historii').or(page.getByText(/Wyświetlasz/)),
    ).toHaveCount(0)
  })
})
