import { test, expect } from '@playwright/test'

import { ALICE, BOB } from './fixtures/users'
import { ANONYMOUS_STATE } from './fixtures/storage'
import { login, logout, expectAuthenticated, expectOnLoginScreen } from './helpers/auth'

// Testy autoryzacji startują WYLOGOWANE — nadpisujemy domyślny storageState (zalogowana alice).
test.use({ storageState: ANONYMOUS_STATE })

test.describe('auth', () => {
  test('niezalogowany użytkownik jest przekierowany na /login', async ({ page }) => {
    await page.goto('/dashboard')
    // ProtectedRoute czeka aż check-sso ustali brak sesji, potem przekierowuje na ekran wejścia.
    await expectOnLoginScreen(page)
  })

  // Realne logowanie/wylogowanie odpalamy na JEDNEJ przeglądarce: realm ma brute-force ON
  // (quickLoginCheckMilliSeconds=1000), więc współbieżne logowania tego samego usera dałyby
  // tymczasową blokadę konta. Renderowanie cross-browser pokrywają pozostałe testy (storageState).
  test.describe('flow logowania', () => {
    test.beforeEach(({ browserName }) => {
      test.skip(browserName !== 'chromium', 'flow logowania tylko na chromium (brute-force KC)')
    })

    test('logowanie przez Keycloak prowadzi na Dashboard', async ({ page }) => {
      await login(page, ALICE)

      await expectAuthenticated(page)
      // Po zalogowaniu lądujemy na dashboardzie — powitanie „Cześć, …" jest wspólne dla stanu
      // pustego (onboarding) i stanu z danymi, więc nie zależy od tego, czy alice ma już analizy.
      await expect(page.getByRole('heading', { name: /Cześć/, level: 1 })).toBeVisible()
    })

    test('wylogowanie wraca na ekran logowania', async ({ page }) => {
      // Bob (nie alice) — świeże logowanie tutaj nie koliduje z równoległym testem logowania alice.
      await login(page, BOB)
      await logout(page)
      await expectOnLoginScreen(page)

      // Sesja faktycznie zamknięta: próba wejścia na chronioną trasę znów ląduje na /login.
      await page.goto('/history')
      await expectOnLoginScreen(page)
    })
  })
})
