// @vitest-environment node
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { setTokenProvider } from './client'
import { recoverAnalysis } from './watch-analysis'
import { streamAnalysis } from './stream'

vi.mock('@/auth/keycloak', () => ({ getToken: vi.fn() }))
vi.mock('@/config/env', () => ({ env: { apiBaseUrl: '/api' } }))
import { getToken } from '@/auth/keycloak'

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('fetch', vi.fn())
  setTokenProvider(() => new Promise(() => {}))
})
afterEach(() => {
  setTokenProvider(async () => undefined)
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

it('times out real apiFetch token acquisition before fetch and resumes bounded recovery', async () => {
  const pending = recoverAnalysis('a1', new AbortController().signal)
  await vi.advanceTimersByTimeAsync(10000)
  expect(fetch).not.toHaveBeenCalled()
  setTokenProvider(async () => undefined)
  vi.mocked(fetch).mockResolvedValue(
    new Response(JSON.stringify({ id: 'a1', status: 'COMPLETED' })),
  )
  await vi.advanceTimersByTimeAsync(500)
  await expect(pending).resolves.toMatchObject({ status: 'COMPLETED' })
  expect(fetch).toHaveBeenCalledTimes(1)
  expect(vi.mocked(fetch).mock.calls[0][1]?.cache).toBe('no-store')
})

it('promptly aborts recovery while the real API token provider never resolves', async () => {
  const controller = new AbortController()
  const pending = recoverAnalysis('a1', controller.signal)
  const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  await vi.advanceTimersByTimeAsync(0)
  controller.abort()
  await rejected
  expect(fetch).not.toHaveBeenCalled()
})

it('promptly aborts SSE while its token refresh never resolves', async () => {
  vi.mocked(getToken).mockImplementation(() => new Promise(() => {}))
  const controller = new AbortController()
  const pending = streamAnalysis(
    'a1',
    { onProgress: vi.fn(), onResult: vi.fn() },
    controller.signal,
  )
  const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  await vi.advanceTimersByTimeAsync(0)
  controller.abort()
  await rejected
  expect(fetch).not.toHaveBeenCalled()
})
