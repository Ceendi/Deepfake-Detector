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
  const callback = await page.request.get('/silent-check-sso.html')
  expect(callback.headers()['content-security-policy']).toContain("frame-ancestors 'self'")
  const echoedUrl = await page.evaluate(
    () =>
      new Promise<string>((resolve, reject) => {
        const frame = document.createElement('iframe')
        const timer = setTimeout(
          () => reject(new Error('Silent SSO callback did not post its URL')),
          5000,
        )
        const receive = (event: MessageEvent) => {
          if (event.source !== frame.contentWindow || event.origin !== location.origin) return
          clearTimeout(timer)
          window.removeEventListener('message', receive)
          frame.remove()
          resolve(event.data)
        }
        window.addEventListener('message', receive)
        frame.src = '/silent-check-sso.html#csp-proof'
        document.body.append(frame)
      }),
  )
  expect(echoedUrl).toContain('/silent-check-sso.html#csp-proof')
  expect(violations).toEqual([])
})
