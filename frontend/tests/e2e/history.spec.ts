import { test, expect } from '@playwright/test'

// Strona historii — chroniona trasa realnie pobiera listę z Orchestratora (sesja alice z setup).
test.describe('historia', () => {
  test('lista analiz wczytuje się bez błędu', async ({ page }) => {
    await page.goto('/history')
    await expect(page).toHaveURL(/\/history$/)

    // Nagłówek + CTA renderują się niezależnie od tego, czy lista jest pusta, czy pełna.
    await expect(page.getByRole('heading', { name: 'Historia analiz' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Nowa analiza' })).toBeVisible()

    // Fetch listy nie wpadł w stan błędu (Alert „Nie udało się wczytać historii").
    await expect(page.getByText('Nie udało się wczytać historii')).toHaveCount(0)
  })
})
