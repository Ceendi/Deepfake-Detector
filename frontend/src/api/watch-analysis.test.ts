import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './errors'
import type { Analysis } from './types'
import { recoverAnalysis, watchAnalysis } from './watch-analysis'

vi.mock('./analysis', () => ({ getAnalysis: vi.fn() }))
vi.mock('./stream', () => ({ streamAnalysis: vi.fn() }))
import { getAnalysis } from './analysis'
import { streamAnalysis } from './stream'

const active = { id: 'a1', status: 'PROCESSING' } as Analysis
const terminal = { ...active, status: 'COMPLETED', verdict: 'FAKE', confidence: 0.8 } as Analysis

beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers()
})
afterEach(() => vi.useRealTimers())

describe('bounded SSE recovery', () => {
  it.each(['eof', 'error'])('catches up on %s without result', async (ending) => {
    vi.mocked(streamAnalysis).mockImplementation(() =>
      ending === 'eof' ? Promise.resolve() : Promise.reject(new TypeError('Socket lost')),
    )
    vi.mocked(getAnalysis).mockResolvedValue(terminal)
    const onResult = vi.fn()
    await watchAnalysis('a1', { onProgress: vi.fn(), onResult }, new AbortController().signal)
    expect(onResult).toHaveBeenCalledExactlyOnceWith(terminal)
  })

  it('waits through stale cache for longer than its TTL, then uses the terminal resource', async () => {
    vi.mocked(streamAnalysis).mockResolvedValue()
    vi.mocked(getAnalysis).mockResolvedValue(active).mockResolvedValueOnce(active)
    const onResult = vi.fn()
    const pending = watchAnalysis(
      'a1',
      { onProgress: vi.fn(), onResult },
      new AbortController().signal,
    )
    await vi.advanceTimersByTimeAsync(0)
    expect(getAnalysis).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(499)
    expect(getAnalysis).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(getAnalysis).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(61000)
    expect(getAnalysis).toHaveBeenCalledTimes(8)
    expect(onResult).not.toHaveBeenCalled()
    vi.mocked(getAnalysis).mockResolvedValue(terminal)
    await vi.advanceTimersByTimeAsync(30000)
    await pending
    expect(getAnalysis).toHaveBeenCalledTimes(9)
    expect(onResult).toHaveBeenCalledExactlyOnceWith(terminal)
    expect(streamAnalysis).toHaveBeenCalledTimes(1)
  })

  it('exhausts exactly nine attempts and never starts an infinite loop', async () => {
    vi.mocked(getAnalysis).mockResolvedValue(active)
    const pending = recoverAnalysis('a1', new AbortController().signal)
    const rejected = expect(pending).rejects.toThrow('Nie udało się odzyskać')
    await vi.advanceTimersByTimeAsync(91500)
    await rejected
    expect(getAnalysis).toHaveBeenCalledTimes(9)
    await vi.advanceTimersByTimeAsync(300000)
    expect(getAnalysis).toHaveBeenCalledTimes(9)
  })

  it('aborts a backoff without another request or result', async () => {
    vi.mocked(streamAnalysis).mockResolvedValue()
    vi.mocked(getAnalysis).mockResolvedValue(active)
    const controller = new AbortController()
    const onResult = vi.fn()
    const pending = watchAnalysis('a1', { onProgress: vi.fn(), onResult }, controller.signal)
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    await vi.advanceTimersByTimeAsync(0)
    controller.abort()
    await rejected
    await vi.advanceTimersByTimeAsync(300000)
    expect(getAnalysis).toHaveBeenCalledTimes(1)
    expect(onResult).not.toHaveBeenCalled()
  })

  it('aborts a never-resolving GET promptly and passes cancellation to the request', async () => {
    const controller = new AbortController()
    vi.mocked(getAnalysis).mockImplementation(() => new Promise(() => {}))
    const pending = recoverAnalysis('a1', controller.signal)
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    await vi.advanceTimersByTimeAsync(0)
    controller.abort()
    await rejected
    expect(vi.mocked(getAnalysis).mock.calls[0][1]?.aborted).toBe(true)
  })

  it('times out a never-resolving whole GET and continues after backoff', async () => {
    vi.mocked(getAnalysis)
      .mockImplementationOnce(() => new Promise(() => {}))
      .mockResolvedValue(terminal)
    const pending = recoverAnalysis('a1', new AbortController().signal)
    await vi.advanceTimersByTimeAsync(10000)
    expect(getAnalysis).toHaveBeenCalledTimes(1)
    expect(vi.mocked(getAnalysis).mock.calls[0][1]?.aborted).toBe(true)
    await vi.advanceTimersByTimeAsync(500)
    await expect(pending).resolves.toEqual(terminal)
    expect(getAnalysis).toHaveBeenCalledTimes(2)
  })

  it('exhausts recovery even when every whole GET never resolves', async () => {
    vi.mocked(getAnalysis).mockImplementation(() => new Promise(() => {}))
    const pending = recoverAnalysis('a1', new AbortController().signal)
    const rejected = expect(pending).rejects.toThrow('Nie udało się odzyskać')
    await vi.advanceTimersByTimeAsync(181500)
    await rejected
    expect(getAnalysis).toHaveBeenCalledTimes(9)
  })

  it('aborts a stream whose opening promise never resolves', async () => {
    vi.mocked(streamAnalysis).mockImplementation(() => new Promise(() => {}))
    const controller = new AbortController()
    const onResult = vi.fn()
    const pending = watchAnalysis('a1', { onProgress: vi.fn(), onResult }, controller.signal)
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    controller.abort()
    await rejected
    expect(getAnalysis).not.toHaveBeenCalled()
    expect(onResult).not.toHaveBeenCalled()
  })

  it('retries transient GET failures with backoff', async () => {
    vi.mocked(getAnalysis)
      .mockRejectedValueOnce(new TypeError('Offline'))
      .mockResolvedValue(terminal)
    const pending = recoverAnalysis('a1', new AbortController().signal)
    await vi.advanceTimersByTimeAsync(500)
    await expect(pending).resolves.toEqual(terminal)
    expect(getAnalysis).toHaveBeenCalledTimes(2)
  })

  it.each([401, 403, 404])(
    'does not poll or disclose state after stream rejection %s',
    async (status) => {
      vi.mocked(streamAnalysis).mockRejectedValue(new ApiError({ status, message: 'Denied' }))
      const onResult = vi.fn()
      await expect(
        watchAnalysis('a1', { onProgress: vi.fn(), onResult }, new AbortController().signal),
      ).rejects.toMatchObject({ status })
      expect(getAnalysis).not.toHaveBeenCalled()
      expect(onResult).not.toHaveBeenCalled()
    },
  )

  it('uses one catch-up policy after an SSE result and does not reopen the stream', async () => {
    vi.mocked(streamAnalysis).mockImplementation(async (_id, handlers, signal) => {
      handlers.onResult({ analysisId: 'a1', status: 'COMPLETED', verdict: 'FAKE', confidence: 0.8 })
      expect(signal.aborted).toBe(true)
      throw new DOMException('Stream stopped', 'AbortError')
    })
    vi.mocked(getAnalysis).mockResolvedValueOnce(active).mockResolvedValue(terminal)
    const onResult = vi.fn()
    const pending = watchAnalysis(
      'a1',
      { onProgress: vi.fn(), onResult },
      new AbortController().signal,
    )
    await vi.advanceTimersByTimeAsync(500)
    await pending
    expect(onResult).toHaveBeenCalledExactlyOnceWith(terminal)
    expect(streamAnalysis).toHaveBeenCalledTimes(1)
  })
})
