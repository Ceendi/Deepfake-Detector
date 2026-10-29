/// <reference types="node" />
// @vitest-environment node
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'

const template = readFileSync(new URL('../../nginx/default.conf.template', import.meta.url), 'utf8')
it('allows the contracted 501 MiB multipart request body', () => {
  expect(template).toContain('client_max_body_size 501m;')
})
it('keeps CSP and Referrer-Policy on each location that declares headers', () => {
  const blocks = template.split(/location [^{]+\{/).slice(1)
  expect(blocks.length).toBeGreaterThanOrEqual(4)
  for (const block of blocks.filter((b) => b.includes('add_header'))) {
    expect(block).toContain('add_header Referrer-Policy strict-origin-when-cross-origin always;')
    expect(block).toContain('add_header Content-Security-Policy')
    if (block.includes("frame-ancestors 'self'")) {
      expect(block).toContain("default-src 'none'")
      expect(block).toContain("script-src 'self'")
    } else {
      expect(block).toContain("frame-ancestors 'none'")
      expect(block).toContain("default-src 'self'")
    }
  }
})
it('hashes the inline theme script exactly as served in the source HTML', () => {
  const html = readFileSync(
    new URL('../../../frontend/index.html', import.meta.url),
    'utf8',
  ).replace(/\r\n?/g, '\n')
  const inline = html.match(/<script>([\s\S]*?)<\/script>/)![1]
  const hash = createHash('sha256').update(inline).digest('base64')
  const rendered = execFileSync(
    process.execPath,
    [
      fileURLToPath(new URL('../../nginx/render-csp.mjs', import.meta.url)),
      fileURLToPath(new URL('../../../frontend/index.html', import.meta.url)),
      fileURLToPath(new URL('../../nginx/default.conf.template', import.meta.url)),
    ],
    { encoding: 'utf8' },
  )
  expect(rendered).toContain(`'sha256-${hash}'`)
  expect(template).not.toMatch(/script-src[^;]*unsafe-inline/)
})

it('allows same-origin framing only for the silent SSO callback, with an external script', () => {
  expect(template).toContain('location = /silent-check-sso.html {')
  expect(template).toContain("frame-src 'self' $keycloak_csp_source")
  const callback = readFileSync(
    new URL('../../public/silent-check-sso.html', import.meta.url),
    'utf8',
  )
  expect(callback).toContain('<script src="/silent-check-sso.js"></script>')
  expect(callback).not.toMatch(/<script>/)
})
