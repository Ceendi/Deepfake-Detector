import { test as setup } from '@playwright/test'

import { ALICE, BOB } from './fixtures/users'
import { ALICE_STATE, BOB_STATE } from './fixtures/storage'
import { login } from './helpers/auth'

setup('authenticate as alice', async ({ page }) => {
  await login(page, ALICE)
  await page.context().storageState({ path: ALICE_STATE })
})

setup('authenticate as bob', async ({ page }) => {
  await login(page, BOB)
  await page.context().storageState({ path: BOB_STATE })
})
