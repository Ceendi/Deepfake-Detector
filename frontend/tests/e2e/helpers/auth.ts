import { expect, type Page } from '@playwright/test'

import type { TestUser } from '../fixtures/users'

// Pełny, REALNY przepływ logowania przez Keycloak (Authorization Code + PKCE):
//   /login → klik „Zaloguj się" → redirect na hostowaną stronę KC (:8180) → formularz →
//   redirect z `code` z powrotem na app → keycloak-js wymienia code na token → Dashboard.
//
// Każdy test loguje się we własnym kontekście (świeża sesja KC), więc wylogowanie w jednym
// teście nie unieważnia sesji innym — kluczowe przy fullyParallel.
export async function login(page: Page, user: TestUser): Promise<void> {
  await page.goto('/login')

  // Przycisk pojawia się dopiero, gdy keycloak-js skończy check-sso (isLoading=false).
  await page.getByRole('button', { name: 'Zaloguj się', exact: true }).click()

  // Hostowana strona Keycloaka (Keycloakify, login.ftl) — stałe id pól, niezależne od locale.
  await page.waitForURL(/\/realms\/deepfake\//)
  await page.locator('#username').fill(user.username)
  await page.locator('#password').fill(user.password)
  // Submit przez selektor type=submit (a nie tekst przycisku) — odporne na język realmu.
  await page.locator('#kc-form-login button[type="submit"]').click()

  await expectAuthenticated(page)
}

// Wylogowanie: menu użytkownika w nagłówku → „Wyloguj" → KC kasuje sesję → /login.
export async function logout(page: Page): Promise<void> {
  await page.locator('button[aria-haspopup="menu"]').click()
  await page.getByRole('menuitem', { name: 'Wyloguj' }).click()
  await expectOnLoginScreen(page)
}

// Sygnał „zalogowany": nagłówek z nawigacją renderuje się wyłącznie w chronionym Layoucie,
// więc widoczny link „Dashboard" oznacza aktywną sesję (i powrót na origin aplikacji).
export async function expectAuthenticated(page: Page): Promise<void> {
  await expect(page.getByRole('link', { name: 'Dashboard', exact: true })).toBeVisible()
}

// Sygnał „niezalogowany": pełnoekranowy ekran wejścia z przyciskiem „Zaloguj się".
export async function expectOnLoginScreen(page: Page): Promise<void> {
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('button', { name: 'Zaloguj się', exact: true })).toBeVisible()
}
