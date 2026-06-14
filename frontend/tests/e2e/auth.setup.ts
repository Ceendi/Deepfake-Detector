import { test as setup } from '@playwright/test'

import { ALICE, BOB } from './fixtures/users'
import { ALICE_STATE, BOB_STATE } from './fixtures/storage'
import { login } from './helpers/auth'

// Loguje każdego testowego usera RAZ i zapisuje sesję (cookies KC). Reszta testów reużywa stan
// przez `storageState`, dzięki czemu nie wywołujemy współbieżnych logowań tego samego konta
// (brute-force realmu blokowałby je). Alice i Bob to różni userzy → brak kolizji quick-login.
setup('authenticate as alice', async ({ page }) => {
  await login(page, ALICE)
  await page.context().storageState({ path: ALICE_STATE })
})

setup('authenticate as bob', async ({ page }) => {
  await login(page, BOB)
  await page.context().storageState({ path: BOB_STATE })
})
