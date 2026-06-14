import { test, expect } from '@playwright/test'

import { wavPayload } from './fixtures/media'

// Hidden <input type="file"> obsługujemy bezpośrednio przez setInputFiles (omijamy natywne
// okno wyboru). Walidacja kliencka jest deterministyczna; ścieżka upload→start uderza w realny
// File Service (magic bytes + ffprobe) i Orchestrator. Sesja alice z domyślnego storageState.
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
    // Bez wgrywania czegokolwiek — to czysto frontendowy odrzut przed dotknięciem backendu.
    await expect(page.getByRole('button', { name: 'Analizuj plik' })).toBeHidden()
  })

  test('poprawny plik startuje realną analizę (upload + POST /analysis = 201)', async ({
    page,
  }) => {
    await page.locator(FILE_INPUT).setInputFiles(wavPayload())

    // PreviewState — plik przeszedł walidację kliencką i czeka na „Analizuj plik".
    await expect(page.getByText('Plik gotowy do analizy')).toBeVisible()

    // Klik uruchamia upload (File Service waliduje realne bajty) → start analizy (Orchestrator).
    // POST /api/analysis = 201 to twardy dowód integracji całej ścieżki, niezależny od tego,
    // czy detektor ma modele i dojdzie do werdyktu.
    const [createResp] = await Promise.all([
      page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname.endsWith('/api/analysis') && r.request().method() === 'POST',
        { timeout: 30_000 }, // upload realnych bajtów + walidacja ffprobe bywa wolniejsza na Firefoxie
      ),
      page.getByRole('button', { name: 'Analizuj plik' }).click(),
    ])
    expect(createResp.status()).toBe(201)

    // UI opuszcza podgląd i wchodzi w fazę analizy (znika CTA „Analizuj plik").
    await expect(page.getByRole('button', { name: 'Analizuj plik' })).toBeHidden()
  })

  test('można anulować trwającą analizę', async ({ page }) => {
    await page.locator(FILE_INPUT).setInputFiles(wavPayload())
    await page.getByRole('button', { name: 'Analizuj plik' }).click()

    // Po starcie pojawia się przycisk „Anuluj" (faza uploading/analyzing). Zakłada, że analiza
    // nie zakończy się terminalnie w ułamku sekundy — realny detektor potrzebuje na to chwili.
    const cancel = page.getByRole('button', { name: 'Anuluj' })
    await expect(cancel).toBeVisible({ timeout: 20_000 })
    await cancel.click()

    // Anulowanie (DELETE /analysis/{id}) wraca cykl do idle → znów widać „Analizuj plik".
    await expect(page.getByRole('button', { name: 'Analizuj plik' })).toBeVisible({
      timeout: 20_000,
    })
  })

  // Pełna ścieżka login → upload → progress → WERDYKT. Wymaga realnego pliku z twarzą/mową
  // ORAZ wgranych modeli w detektorach (inaczej analiza kończy się FAILED). Dlatego domyślnie
  // pominięty — włącz przez E2E_VERDICT_FIXTURE=ścieżka/do/pliku. Patrz tests/e2e/README.md.
  test('upload realnego pliku kończy się werdyktem', async ({ page }) => {
    const fixture = process.env.E2E_VERDICT_FIXTURE
    test.skip(!fixture, 'Ustaw E2E_VERDICT_FIXTURE na plik z twarzą/mową (+ modele w detektorach)')

    await page.locator(FILE_INPUT).setInputFiles(fixture!)
    await page.getByRole('button', { name: 'Analizuj plik' }).click()

    // Flow nawiguje na stronę wyniku dopiero po statusie COMPLETED z SSE.
    await page.waitForURL(/\/analysis-result\//, { timeout: 120_000 })
    await expect(page.getByText('Wróć do historii').first()).toBeVisible()
  })
})
