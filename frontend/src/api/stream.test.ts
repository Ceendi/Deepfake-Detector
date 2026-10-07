// @vitest-environment node
import { beforeEach, expect, it, vi } from 'vitest'
import { streamAnalysis } from './stream'

vi.mock('@/auth/keycloak', () => ({ getToken: vi.fn().mockResolvedValue('token') }))
vi.mock('@/config/env', () => ({ env: { apiBaseUrl: '/api' } }))

beforeEach(() => vi.unstubAllGlobals())

it('reads fragmented CRLF frames and returns on EOF without inventing a result', async () => {
  const encoder = new TextEncoder()
  const body = new ReadableStream({
    start(controller) {
      controller.enqueue(
        encoder.encode(': heartbeat\r\n\r\nevent: progress\r\ndata: {"analysisId":"a1",'),
      )
      controller.enqueue(
        encoder.encode(
          '"source":"video","progress":50,"stage":"INFERENCE","status":"PROCESSING"}\r\n\r\n',
        ),
      )
      controller.close()
    },
  })
  const fetchMock = vi.fn().mockResolvedValue(new Response(body))
  vi.stubGlobal('fetch', fetchMock)
  const onProgress = vi.fn()
  const onResult = vi.fn()
  await streamAnalysis('a1', { onProgress, onResult }, new AbortController().signal)
  expect(onProgress).toHaveBeenCalledTimes(1)
  expect(onResult).not.toHaveBeenCalled()
  expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer token')
})

it('propagates a reader error so the shared watcher can catch up', async () => {
  const body = new ReadableStream({
    start(controller) {
      controller.error(new Error('Socket lost'))
    },
  })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body)))
  await expect(
    streamAnalysis('a1', { onProgress: vi.fn(), onResult: vi.fn() }, new AbortController().signal),
  ).rejects.toThrow('Socket lost')
})

it('does not issue a request for an already aborted subscription', async () => {
  const fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
  const controller = new AbortController()
  controller.abort()
  await expect(
    streamAnalysis('a1', { onProgress: vi.fn(), onResult: vi.fn() }, controller.signal),
  ).rejects.toMatchObject({ name: 'AbortError' })
  expect(fetchMock).not.toHaveBeenCalled()
})
