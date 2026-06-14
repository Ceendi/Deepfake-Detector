import { test, expect } from '@playwright/test'

import { wavPayload } from './fixtures/media'
import { ALICE_STATE, BOB_STATE } from './fixtures/storage'

// D-wymóg / security: IDOR guard. Analiza należąca do alice jest dla boba nieistniejąca (404),
// a backend celowo zwraca 404 zamiast 403, by nie potwierdzać istnienia zasobu.
// Dwa konteksty z gotowych sesji (storageState z setup) — bez logowania w teście.
test('bob nie widzi analizy alice (IDOR → „nie znaleziono")', async ({ browser }) => {
  const aliceCtx = await browser.newContext({ storageState: ALICE_STATE })
  const bobCtx = await browser.newContext({ storageState: BOB_STATE })

  try {
    // --- alice tworzy realną analizę i zapamiętujemy jej id z odpowiedzi 201 ---
    const alicePage = await aliceCtx.newPage()
    await alicePage.goto('/upload')
    await alicePage.locator('input[type="file"]').setInputFiles(wavPayload())

    const [createResp] = await Promise.all([
      alicePage.waitForResponse(
        (r) =>
          new URL(r.url()).pathname.endsWith('/api/analysis') && r.request().method() === 'POST',
        { timeout: 30_000 },
      ),
      alicePage.getByRole('button', { name: 'Analizuj plik' }).click(),
    ])
    expect(createResp.status()).toBe(201)
    const { id } = (await createResp.json()) as { id: string }
    expect(id).toBeTruthy()

    const getPath = `/api/analysis/${id}`
    const isGetForId = (r: { url(): string; request(): { method(): string } }) =>
      new URL(r.url()).pathname === getPath && r.request().method() === 'GET'
    const waitOpts = { timeout: 30_000 }

    // --- bob próbuje otworzyć cudzy zasób po id → 404 + UI „nie znaleziono" ---
    const bobPage = await bobCtx.newPage()
    const [bobGet] = await Promise.all([
      bobPage.waitForResponse(isGetForId, waitOpts),
      bobPage.goto(`/analysis-result/${id}`),
    ])
    expect(bobGet.status()).toBe(404)
    await expect(bobPage.getByText('Nie znaleziono analizy')).toBeVisible()

    // --- kontrola pozytywna: właściciel (alice) dostaje 200 i NIE widzi „nie znaleziono" ---
    const [aliceGet] = await Promise.all([
      alicePage.waitForResponse(isGetForId, waitOpts),
      alicePage.goto(`/analysis-result/${id}`),
    ])
    expect(aliceGet.status()).toBe(200)
    await expect(alicePage.getByText('Nie znaleziono analizy')).toHaveCount(0)
  } finally {
    await aliceCtx.close()
    await bobCtx.close()
  }
})
