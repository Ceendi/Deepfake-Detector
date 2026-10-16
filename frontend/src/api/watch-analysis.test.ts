import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './errors'
import type { Analysis } from './types'
import {
  AnalysisStillRunningError,
  AnalysisAgeLimitError,
  MAX_ANALYSIS_AGE_MS,
  recoverAnalysis,
  watchAnalysis,
} from './watch-analysis'

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
    const rejected = expect(pending).rejects.toBeInstanceOf(AnalysisStillRunningError)
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
    const rejected = expect(pending).rejects.toBeInstanceOf(AnalysisStillRunningError)
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

describe('long-running analysis watching', () => {
  const createdNow = () => new Date(Date.now()).toISOString()

  it('reopens SSE after bounded recovery and delivers the later terminal result', async () => {
    vi.mocked(streamAnalysis)
      .mockResolvedValueOnce() // lifetime EOF
      .mockImplementationOnce(async (_id, handlers) => {
        handlers.onResult({ analysisId: 'a1', status: 'COMPLETED', verdict: 'FAKE', confidence: 1 })
      })
    vi.mocked(getAnalysis).mockResolvedValue(active)
    const onResult = vi.fn()
    const onStillRunning = vi.fn()
    const pending = watchAnalysis(
      'a1',
      { onProgress: vi.fn(), onResult },
      new AbortController().signal,
      { createdAt: createdNow(), onStillRunning },
    )
    await vi.advanceTimersByTimeAsync(91500)
    expect(onStillRunning).toHaveBeenCalledOnce()
    expect(onResult).not.toHaveBeenCalled()
    expect(streamAnalysis).toHaveBeenCalledTimes(1)
    vi.mocked(getAnalysis).mockResolvedValue(terminal)
    await vi.advanceTimersByTimeAsync(5000)
    await pending
    expect(streamAnalysis).toHaveBeenCalledTimes(2)
    expect(onResult).toHaveBeenCalledExactlyOnceWith(terminal)
    expect(onStillRunning).toHaveBeenCalledOnce()
  })

  it('falls back to a read when the reopened SSE is unavailable', async () => {
    vi.mocked(streamAnalysis)
      .mockResolvedValueOnce()
      .mockRejectedValue(new ApiError({ status: 429, message: 'Too many streams' }))
    vi.mocked(getAnalysis).mockResolvedValue(active)
    const onResult = vi.fn()
    const pending = watchAnalysis(
      'a1',
      { onProgress: vi.fn(), onResult },
      new AbortController().signal,
      { createdAt: createdNow() },
    )
    await vi.advanceTimersByTimeAsync(91500 + 5000)
    expect(getAnalysis).toHaveBeenCalledTimes(10)
    vi.mocked(getAnalysis).mockResolvedValue(terminal)
    await vi.advanceTimersByTimeAsync(10000)
    await pending
    expect(getAnalysis).toHaveBeenCalledTimes(11)
    expect(onResult).toHaveBeenCalledExactlyOnceWith(terminal)
  })

  it('stops watching once the analysis is older than the absolute limit', async () => {
    vi.mocked(streamAnalysis).mockResolvedValue()
    vi.mocked(getAnalysis).mockResolvedValue(active)
    const createdAt = new Date(Date.now() - MAX_ANALYSIS_AGE_MS + 120000).toISOString()
    const onStillRunning = vi.fn()
    const pending = watchAnalysis(
      'a1',
      { onProgress: vi.fn(), onResult: vi.fn() },
      new AbortController().signal,
      { createdAt, onStillRunning },
    )
    const rejected = expect(pending).rejects.toBeInstanceOf(AnalysisAgeLimitError)
    await vi.advanceTimersByTimeAsync(120000)
    await rejected
    expect(onStillRunning).toHaveBeenCalledOnce()
    const opened = vi.mocked(streamAnalysis).mock.calls.length
    await vi.advanceTimersByTimeAsync(600000)
    expect(streamAnalysis).toHaveBeenCalledTimes(opened)
  })

  it('does not start long watching for an analysis already past the limit', async () => {
    vi.mocked(streamAnalysis).mockResolvedValue()
    vi.mocked(getAnalysis).mockResolvedValue(active)
    const createdAt = new Date(Date.now() - MAX_ANALYSIS_AGE_MS).toISOString()
    const onStillRunning = vi.fn()
    const pending = watchAnalysis(
      'a1',
      { onProgress: vi.fn(), onResult: vi.fn() },
      new AbortController().signal,
      { createdAt, onStillRunning },
    )
    const rejected = expect(pending).rejects.toBeInstanceOf(AnalysisAgeLimitError)
    await vi.advanceTimersByTimeAsync(91500)
    await rejected
    expect(onStillRunning).not.toHaveBeenCalled()
    expect(streamAnalysis).toHaveBeenCalledTimes(1)
  })

  it('aborting during the reopen backoff stops without another stream', async () => {
    vi.mocked(streamAnalysis).mockResolvedValue()
    vi.mocked(getAnalysis).mockResolvedValue(active)
    const controller = new AbortController()
    const pending = watchAnalysis(
      'a1',
      { onProgress: vi.fn(), onResult: vi.fn() },
      controller.signal,
      { createdAt: createdNow() },
    )
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    await vi.advanceTimersByTimeAsync(93000)
    controller.abort()
    await rejected
    await vi.advanceTimersByTimeAsync(600000)
    expect(streamAnalysis).toHaveBeenCalledTimes(1)
  })

  it('a healthy stream ending at its lifetime is reopened at once without bounded recovery', async () => {
    vi.mocked(streamAnalysis)
      .mockImplementationOnce(() => new Promise((resolve) => setTimeout(resolve, 600000)))
      .mockImplementationOnce(() => new Promise(() => {}))
    vi.mocked(getAnalysis).mockResolvedValue(active)
    const onStillRunning = vi.fn()
    void watchAnalysis(
      'a1',
      { onProgress: vi.fn(), onResult: vi.fn() },
      new AbortController().signal,
      { createdAt: createdNow(), onStillRunning },
    ).catch(() => {})
    await vi.advanceTimersByTimeAsync(600000)
    expect(getAnalysis).toHaveBeenCalledOnce()
    expect(streamAnalysis).toHaveBeenCalledTimes(2)
    expect(onStillRunning).not.toHaveBeenCalled()
  })

  it('an idle-watchdog timeout falls back to recovery instead of failing', async () => {
    vi.mocked(streamAnalysis).mockRejectedValue(new DOMException('SSE stream idle', 'TimeoutError'))
    vi.mocked(getAnalysis).mockResolvedValue(terminal)
    const onResult = vi.fn()
    await watchAnalysis('a1', { onProgress: vi.fn(), onResult }, new AbortController().signal)
    expect(onResult).toHaveBeenCalledExactlyOnceWith(terminal)
  })
})
