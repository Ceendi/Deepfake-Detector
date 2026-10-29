import { createHash } from 'node:crypto'
import { test, expect } from '@playwright/test'
import { ALICE } from './fixtures/users'
import { login } from './helpers/auth'

test.use({
  baseURL: process.env.E2E_CONTAINER_URL ?? 'http://localhost:4173',
  storageState: { cookies: [], origins: [] },
})

test('production CSP permits theme, Keycloak login, silent SSO and history', async ({ page }) => {
  const violations: string[] = []
  page.on('console', (message) => {
    if (
      message.type() === 'error' &&
      /Content Security Policy|violates.*directive/i.test(message.text())
    )
      violations.push(message.text())
  })
  const response = await page.goto('/login')
  const policy = response!.headers()['content-security-policy']
  expect(policy).toContain("frame-ancestors 'none'")
  expect(policy).toContain("default-src 'self'")
  const html = await response!.text()
  const inline = html.match(/<script>([\s\S]*?)<\/script>/)![1].replace(/\r\n?/g, '\n')
  expect(policy).toContain(`'sha256-${createHash('sha256').update(inline).digest('base64')}'`)
  await login(page, ALICE)
  await page.reload()
  await expect(page.getByRole('link', { name: 'Dashboard', exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Historia', exact: true }).click()
  await expect(
    page.getByText('Brak analiz w historii').or(page.getByText(/Wyświetlasz/)),
  ).toBeVisible()
  const asset = html.match(/src="([^" ]*\/assets\/[^" ]+\.js)"/)![1]
  const assetResponse = await page.request.get(asset)
  expect(assetResponse.headers()['referrer-policy']).toBe('strict-origin-when-cross-origin')
  expect(assetResponse.headers()['content-security-policy']).toBe(policy)
  expect(violations).toEqual([])
})
