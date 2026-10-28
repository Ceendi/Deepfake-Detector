import { test, expect } from '@playwright/test'

import { wavPayload } from './fixtures/media'

// Without detectors analyses stay active, so the queue is checked up to running analyses and
// "cancel all"; the per-user limits are raised in CI (.github/workflows/e2e.yml).
test('kolejka wielu plików: wysyłka, start analiz i anulowanie wszystkiego', async ({ page }) => {
  await page.goto('/upload')
  await expect(page.getByRole('heading', { name: 'Nowa analiza' })).toBeVisible()

  const media = ['partia-1.wav', 'partia-2.wav', 'partia-3.wav'].map((name) => wavPayload(name))
  await page
    .locator('input[type="file"]')
    .setInputFiles([
      ...media,
      { name: 'notatka.txt', mimeType: 'text/plain', buffer: Buffer.from('nope') },
    ])

  await expect(page.getByRole('heading', { name: '4 pliki' })).toBeVisible()
  await expect(page.getByRole('listitem', { name: 'notatka.txt' })).toContainText('Błąd')

  const created: string[] = []
  page.on('response', (response) => {
    const { pathname } = new URL(response.url())
    if (
      pathname.endsWith('/api/analysis') &&
      response.request().method() === 'POST' &&
      response.status() === 201
    ) {
      created.push(pathname)
    }
  })
  await page.getByRole('button', { name: 'Analizuj wszystkie (3)' }).click()

  for (const { name } of media) {
    const row = page.getByRole('listitem', { name })
    await expect(row).toContainText('Analiza', { timeout: 60_000 })
    await expect(row.getByRole('link', { name: `Wynik: ${name}` })).toBeVisible()
  }
  expect(created).toHaveLength(3)

  await page.getByRole('button', { name: 'Anuluj wszystko' }).click()

  for (const { name } of media) {
    await expect(page.getByRole('listitem', { name })).toContainText('Anulowano')
  }
  await expect(page.getByText('0/4 gotowe · 1 z błędem')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Wybierz kolejne pliki' })).toBeVisible()
})
