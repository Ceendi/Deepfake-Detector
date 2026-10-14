// @vitest-environment node
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { STREAM_IDLE_TIMEOUT_MS, streamAnalysis } from './stream'

vi.mock('@/auth/keycloak', () => ({ getToken: vi.fn().mockResolvedValue('token') }))
vi.mock('@/config/env', () => ({ env: { apiBaseUrl: '/api' } }))

beforeEach(() => vi.unstubAllGlobals())
afterEach(() => vi.useRealTimers())

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

it('aborts a half-open stream after the idle timeout so the watcher can recover', async () => {
  vi.useFakeTimers()
  let cancelled = false
  const body = new ReadableStream({
    cancel() {
      cancelled = true
    },
  })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body)))
  const pending = streamAnalysis(
    'a1',
    { onProgress: vi.fn(), onResult: vi.fn() },
    new AbortController().signal,
  )
  const rejected = expect(pending).rejects.toMatchObject({ name: 'TimeoutError' })
  await vi.advanceTimersByTimeAsync(STREAM_IDLE_TIMEOUT_MS - 1)
  expect(cancelled).toBe(false)
  await vi.advanceTimersByTimeAsync(1)
  await rejected
  expect(cancelled).toBe(true)
})

it('heartbeats keep a quiet stream open past the idle timeout', async () => {
  vi.useFakeTimers()
  const encoder = new TextEncoder()
  let push: ReadableStreamDefaultController<Uint8Array> | undefined
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      push = controller
    },
  })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body)))
  const onResult = vi.fn()
  const pending = streamAnalysis(
    'a1',
    { onProgress: vi.fn(), onResult },
    new AbortController().signal,
  )
  for (let i = 0; i < 6; i++) {
    await vi.advanceTimersByTimeAsync(15000)
    push!.enqueue(encoder.encode(': heartbeat\n\n'))
  }
  push!.enqueue(
    encoder.encode(
      'event: result\ndata: {"analysisId":"a1","status":"COMPLETED","verdict":"REAL","confidence":0.9}\n\n',
    ),
  )
  push!.close()
  await expect(pending).resolves.toBeUndefined()
  expect(onResult).toHaveBeenCalledOnce()
})

it('times out a stream request whose response never arrives', async () => {
  vi.useFakeTimers()
  const fetchMock = vi.fn(
    (_url: string, init: RequestInit) =>
      new Promise<Response>((_resolve, reject) => {
        init.signal?.addEventListener('abort', () => reject(init.signal?.reason))
      }),
  )
  vi.stubGlobal('fetch', fetchMock)
  const pending = streamAnalysis(
    'a1',
    { onProgress: vi.fn(), onResult: vi.fn() },
    new AbortController().signal,
  )
  const rejected = expect(pending).rejects.toMatchObject({ name: 'TimeoutError' })
  await vi.advanceTimersByTimeAsync(STREAM_IDLE_TIMEOUT_MS)
  await rejected
})
