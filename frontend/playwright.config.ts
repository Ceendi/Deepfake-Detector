import { defineConfig, devices } from '@playwright/test'

import { ALICE_STATE } from './tests/e2e/fixtures/storage'

// E2E przeciwko ŻYWEMU stackowi (Keycloak :8180 + Gateway :8080 + reszta przez docker compose).
// Logowanie idzie przez prawdziwy redirect PKCE na hostowaną stronę Keycloaka (Keycloakify),
// a /api/** proxuje dev server Vite (vite.config.ts) na Gateway. Dlatego baseURL = dev server.
//
// Wymagania przed uruchomieniem `npm run e2e`:
//   1. docker compose --profile core --profile auth (--profile ml) up   — backend + Keycloak
//   2. realm „deepfake" z userami alice/bob (infra/keycloak/realm-export.json)
//   3. dev server na :5173 (webServer poniżej podniesie go sam, jeśli nie działa)
export default defineConfig({
  testDir: './tests/e2e',
  // Pełna izolacja per test (każdy test loguje się we własnej sesji KC — patrz helpers/auth.ts),
  // więc równoległość jest bezpieczna i nie ma współdzielonego stanu logowania do popsucia.
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  // 1 retry lokalnie absorbuje zimny start (pierwszy dotyk lazy-route kompiluje chunk w Vite,
  // co pod równoległymi workerami potrafi raz przekroczyć timeout); na CI dajemy 2.
  retries: process.env.CI ? 2 : 1,
  reporter: 'html',

  // Hojne limity: pierwszy dotyk lazy-route w dev triggeruje kompilację Vite (kilka sekund),
  // a redirect PKCE to dwa pełne przeładowania strony. Plus realna analiza (upload→start).
  timeout: 120_000,
  expect: { timeout: 20_000 },

  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'on-first-retry',
    navigationTimeout: 30_000,
    actionTimeout: 15_000,
  },

  // Reużywa działający dev server (zwykle masz go już odpalonego). Jeśli nie — podniesie własny.
  // UWAGA: webServer NIE startuje backendu/Keycloaka — te muszą działać niezależnie (docker compose).
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 120_000,
  },

  projects: [
    // Loguje alice i boba RAZ i zapisuje sesje do playwright/.auth/*.json (patrz auth.setup.ts).
    { name: 'setup', testMatch: /auth\.setup\.ts/ },

    // Domyślnie testy startują jako zalogowana alice (storageState). Testy, które tego nie chcą
    // (bramka autoryzacji, flow logowania/wylogowania), nadpisują storageState lokalnie.
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], storageState: ALICE_STATE },
      dependencies: ['setup'],
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'], storageState: ALICE_STATE },
      dependencies: ['setup'],
    },
  ],
})
