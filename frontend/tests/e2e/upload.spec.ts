import { test, expect } from '@playwright/test'

import { wavPayload } from './fixtures/media'

const FILE_INPUT = 'input[type="file"]'

test.describe('upload', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/upload')
    await expect(page.getByRole('heading', { name: 'Nowa analiza' })).toBeVisible()
  })

  test('odrzuca nieobsługiwany format pliku (walidacja kliencka)', async ({ page }) => {
    await page
      .locator(FILE_INPUT)
      .setInputFiles({ name: 'notatka.txt', mimeType: 'text/plain', buffer: Buffer.from('nope') })

    await expect(page.getByRole('heading', { name: 'Nie udało się dodać pliku' })).toBeVisible()
    await expect(page.getByText(/Nieobsługiwany format pliku/)).toBeVisible()

    await expect(page.getByRole('button', { name: 'Analizuj plik' })).toBeHidden()
  })

  test('poprawny plik startuje realną analizę (upload + POST /analysis = 201)', async ({
    page,
  }) => {
    const file = wavPayload('oryginalna-nazwa-nagrania.wav')
    await page.locator(FILE_INPUT).setInputFiles(file)

    await expect(page.getByText('Plik gotowy do analizy')).toBeVisible()

    const [createResp] = await Promise.all([
      page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname.endsWith('/api/analysis') && r.request().method() === 'POST',
        { timeout: 30_000 },
      ),
      page.getByRole('button', { name: 'Analizuj plik' }).click(),
    ])
    expect(createResp.status()).toBe(201)

    await expect(page.getByRole('button', { name: 'Analizuj plik' })).toBeHidden()

    const { id } = (await createResp.json()) as { id: string }
    await page.goto(`/analysis-result/${id}`)
    await expect(page.getByRole('heading', { name: file.name, exact: true })).toHaveAttribute(
      'title',
      file.name,
    )
  })

  test('można anulować trwającą analizę', async ({ page }) => {
    await page.locator(FILE_INPUT).setInputFiles(wavPayload())
    await page.getByRole('button', { name: 'Analizuj plik' }).click()

    const cancel = page.getByRole('button', { name: 'Anuluj' })
    await expect(cancel).toBeVisible({ timeout: 20_000 })
    await cancel.click()

    await expect(page.getByRole('button', { name: 'Analizuj plik' })).toBeVisible({
      timeout: 20_000,
    })
  })

  test('upload realnego pliku kończy się werdyktem', async ({ page }) => {
    const fixture = process.env.E2E_VERDICT_FIXTURE
    test.skip(!fixture, 'Ustaw E2E_VERDICT_FIXTURE na plik z twarzą/mową (+ modele w detektorach)')

    await page.locator(FILE_INPUT).setInputFiles(fixture!)
    await page.getByRole('button', { name: 'Analizuj plik' }).click()

    await page.waitForURL(/\/analysis-result\//, { timeout: 120_000 })
    await expect(page.getByText('Wróć do historii').first()).toBeVisible()
  })
})
