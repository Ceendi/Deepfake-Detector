import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'

import { ApiError } from '@/api/errors'
import type { Analysis, AnalysisProgressEvent, AnalysisResultEvent } from '@/api/types'
import { useAnalysisFlow } from './use-analysis-flow'

vi.mock('@/api/files', () => ({ uploadFileWithProgress: vi.fn() }))
vi.mock('@/api/analysis', () => ({
  startAnalysis: vi.fn(),
  getAnalysis: vi.fn(),
  cancelAnalysis: vi.fn(),
}))
vi.mock('@/api/stream', () => ({ streamAnalysis: vi.fn() }))

import { uploadFileWithProgress } from '@/api/files'
import { startAnalysis, getAnalysis, cancelAnalysis } from '@/api/analysis'
import { streamAnalysis } from '@/api/stream'

let streamHandlers: {
  onProgress: (e: AnalysisProgressEvent) => void
  onResult: (e: AnalysisResultEvent) => void
}

function analysis(overrides: Partial<Analysis> = {}): Analysis {
  return {
    id: 'an-1',
    userId: 'u-1',
    fileId: 'f1',
    fileKey: 'k1',
    type: 'FULL',
    status: 'PROCESSING',
    verdict: null,
    confidence: null,
    videoProb: null,
    audioProb: null,
    details: null,
    errorMessage: null,
    createdAt: '2026-06-13T10:00:00.000Z',
    updatedAt: '2026-06-13T10:00:00.000Z',
    ...overrides,
  }
}

function videoFile() {
  return new File(['x'], 'clip.mp4', { type: 'video/mp4' })
}

function renderFlow() {
  const onComplete = vi.fn()
  const view = renderHook(() => useAnalysisFlow(onComplete))
  return { ...view, onComplete }
}

async function startUntilAnalyzing(result: ReturnType<typeof renderFlow>['result']) {
  await act(async () => {
    await result.current.start(videoFile())
  })
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(uploadFileWithProgress).mockResolvedValue({
    fileId: 'f1',
    fileKey: 'k1',
    size: 1,
    mimetype: 'video/mp4',
  })
  vi.mocked(startAnalysis).mockResolvedValue(analysis({ id: 'an-1' }))
  vi.mocked(getAnalysis).mockResolvedValue(analysis({ status: 'COMPLETED' }))

  vi.mocked(streamAnalysis).mockImplementation((_id, handlers) => {
    return new Promise<void>((resolve) => {
      streamHandlers = {
        onProgress: handlers.onProgress,
        onResult: (event) => {
          handlers.onResult(event)
          resolve()
        },
      }
    })
  })
})

afterEach(() => vi.useRealTimers())

describe('useAnalysisFlow', () => {
  it.each([404, 503])('create rejection (%s) stops before opening a stream', async (status) => {
    vi.mocked(startAnalysis).mockRejectedValue(
      new ApiError({ status, message: 'File unavailable' }),
    )
    const { result, onComplete } = renderFlow()
    await startUntilAnalyzing(result)
    expect(result.current.state.name).toBe('failed')
    expect(streamAnalysis).not.toHaveBeenCalled()
    expect(onComplete).not.toHaveBeenCalled()
  })

  it('happy path: upload → start → analyzing → COMPLETED woła onComplete', async () => {
    const { result, onComplete } = renderFlow()

    await startUntilAnalyzing(result)
    expect(result.current.state).toEqual({ name: 'analyzing', analysisId: 'an-1' })
    expect(startAnalysis).toHaveBeenCalledWith({ fileId: 'f1', type: 'FULL' })

    act(() => {
      streamHandlers.onResult({
        analysisId: 'an-1',
        status: 'COMPLETED',
        verdict: 'FAKE',
        confidence: 0.9,
      })
    })
    await waitFor(() => expect(onComplete).toHaveBeenCalledWith('an-1'))
  })

  it('agreguje postęp per źródło (video + audio)', async () => {
    const { result } = renderFlow()
    await startUntilAnalyzing(result)

    act(() => {
      streamHandlers.onProgress({
        analysisId: 'an-1',
        source: 'video',
        progress: 40,
        stage: 'INFERENCE',
        status: 'PROCESSING',
      })
      streamHandlers.onProgress({
        analysisId: 'an-1',
        source: 'audio',
        progress: 70,
        stage: 'INFERENCE',
        status: 'PROCESSING',
      })
    })

    expect(result.current.bySource.video?.progress).toBe(40)
    expect(result.current.bySource.audio?.progress).toBe(70)
  })

  it('błąd uploadu → failed z komunikatem zmapowanym ze statusu', async () => {
    vi.mocked(uploadFileWithProgress).mockRejectedValue(new ApiError({ status: 413, message: 'x' }))
    const { result } = renderFlow()

    await startUntilAnalyzing(result)

    expect(result.current.state.name).toBe('failed')
    expect((result.current.state as { message: string }).message).toContain('za duży')
  })

  it('anulowanie uploadu (AbortError) → powrót do idle, bez failed', async () => {
    vi.mocked(uploadFileWithProgress).mockRejectedValue(new DOMException('aborted', 'AbortError'))
    const { result } = renderFlow()

    await startUntilAnalyzing(result)

    expect(result.current.state).toEqual({ name: 'idle' })
  })

  it('błąd strumienia (nie-abort) → failed', async () => {
    vi.mocked(streamAnalysis).mockImplementationOnce(() =>
      Promise.reject(new ApiError({ status: 404, message: 'stream padł' })),
    )
    const { result } = renderFlow()

    await startUntilAnalyzing(result)

    await waitFor(() => expect(result.current.state.name).toBe('failed'))
    expect((result.current.state as { message: string }).message).toBe('Nie znaleziono analizy.')
  })

  it.each([401, 403])(
    'watch rejection (%s) links to the existing analysis instead of a re-upload',
    async (status) => {
      vi.mocked(streamAnalysis).mockImplementationOnce(() =>
        Promise.reject(new ApiError({ status, message: 'denied' })),
      )
      const { result } = renderFlow()
      await startUntilAnalyzing(result)
      await waitFor(() =>
        expect(result.current.state).toEqual({
          name: 'stillRunning',
          analysisId: 'an-1',
          watching: false,
        }),
      )
      expect(uploadFileWithProgress).toHaveBeenCalledTimes(1)
    },
  )

  it.each(['eof', 'error'])(
    'recovers a terminal result after %s without an SSE result',
    async (ending) => {
      vi.mocked(streamAnalysis).mockImplementationOnce(() =>
        ending === 'eof' ? Promise.resolve() : Promise.reject(new TypeError('Connection lost')),
      )
      const { result, onComplete } = renderFlow()
      await startUntilAnalyzing(result)
      await waitFor(() => expect(onComplete).toHaveBeenCalledWith('an-1'))
      expect(getAnalysis).toHaveBeenCalledTimes(1)
    },
  )

  it('upload waits through stale cache and eventually completes', async () => {
    vi.useFakeTimers()
    vi.mocked(streamAnalysis).mockResolvedValue()
    vi.mocked(getAnalysis)
      .mockResolvedValueOnce(analysis())
      .mockResolvedValueOnce(analysis())
      .mockResolvedValue(analysis({ status: 'COMPLETED' }))
    const { result, onComplete } = renderFlow()
    await startUntilAnalyzing(result)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1499)
    })
    expect(onComplete).not.toHaveBeenCalled()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(onComplete).toHaveBeenCalledExactlyOnceWith('an-1')
  })

  it('upload keeps watching a long analysis instead of failing after recovery', async () => {
    vi.useFakeTimers()
    vi.mocked(startAnalysis).mockResolvedValue(
      analysis({ createdAt: new Date(Date.now()).toISOString() }),
    )
    vi.mocked(streamAnalysis).mockResolvedValueOnce()
    vi.mocked(getAnalysis).mockResolvedValue(analysis())
    const { result, onComplete } = renderFlow()
    await startUntilAnalyzing(result)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(91500)
    })
    expect(result.current.state).toEqual({
      name: 'stillRunning',
      analysisId: 'an-1',
      watching: true,
    })
    expect(onComplete).not.toHaveBeenCalled()
    expect(getAnalysis).toHaveBeenCalledTimes(9)
    expect(uploadFileWithProgress).toHaveBeenCalledOnce()

    // The reopened SSE replays the terminal result from the database.
    vi.mocked(getAnalysis).mockResolvedValue(analysis({ status: 'COMPLETED' }))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })
    expect(streamAnalysis).toHaveBeenCalledTimes(2)
    act(() => {
      streamHandlers.onResult({
        analysisId: 'an-1',
        status: 'COMPLETED',
        verdict: 'REAL',
        confidence: 0.9,
      })
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(onComplete).toHaveBeenCalledExactlyOnceWith('an-1')
  })

  it('upload cancellation aborts recovery without late completion', async () => {
    vi.useFakeTimers()
    vi.mocked(streamAnalysis).mockResolvedValue()
    vi.mocked(getAnalysis).mockResolvedValue(analysis())
    vi.mocked(cancelAnalysis).mockResolvedValue(analysis({ status: 'CANCELLED' }))
    const { result, onComplete } = renderFlow()
    await startUntilAnalyzing(result)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    await act(async () => {
      await result.current.cancel()
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300000)
    })
    expect(result.current.state.name).toBe('idle')
    expect(onComplete).not.toHaveBeenCalled()
    expect(getAnalysis).toHaveBeenCalledTimes(1)
  })

  it('wynik FAILED dociąga errorMessage z pełnego zasobu i mapuje kod na komunikat', async () => {
    vi.mocked(getAnalysis).mockResolvedValue(
      analysis({ status: 'FAILED', errorMessage: '[MODEL_UNAVAILABLE] Model file missing' }),
    )
    const { result } = renderFlow()
    await startUntilAnalyzing(result)

    act(() => {
      streamHandlers.onResult({
        analysisId: 'an-1',
        status: 'FAILED',
        verdict: null,
        confidence: null,
      })
    })

    await waitFor(() =>
      expect(result.current.state).toEqual({
        name: 'failed',
        message: 'Usługa analizy jest chwilowo niedostępna. Spróbuj ponownie później.',
        retry: 'reset',
      }),
    )
    expect(getAnalysis).toHaveBeenCalledWith('an-1', expect.any(AbortSignal))
  })

  it('typ analizy wynika z MIME wykrytego przez backend, nie z rozszerzenia', async () => {
    vi.mocked(uploadFileWithProgress).mockResolvedValue({
      fileId: 'f1',
      fileKey: 'k1',
      size: 1,
      mimetype: 'audio/mp4',
    })
    const { result } = renderFlow()
    await startUntilAnalyzing(result)

    expect(startAnalysis).toHaveBeenCalledWith({ fileId: 'f1', type: 'AUDIO' })
    expect(result.current.analysisType).toBe('AUDIO')
  })

  it('cancel w trakcie analizy woła cancelAnalysis i wraca do idle', async () => {
    vi.mocked(cancelAnalysis).mockResolvedValue(analysis({ status: 'CANCELLED' }))
    const { result } = renderFlow()
    await startUntilAnalyzing(result)

    await act(async () => {
      await result.current.cancel()
    })

    expect(cancelAnalysis).toHaveBeenCalledWith('an-1', expect.any(AbortSignal))
    expect(result.current.state).toEqual({ name: 'idle' })
  })

  it('retry z failed wraca do idle', async () => {
    vi.mocked(uploadFileWithProgress).mockRejectedValue(new ApiError({ status: 500, message: 'x' }))
    const { result } = renderFlow()
    await startUntilAnalyzing(result)
    expect(result.current.state.name).toBe('failed')

    act(() => result.current.retry())
    expect(result.current.state).toEqual({ name: 'idle' })
  })

  it.each([429, 503])(
    'retry after a failed start (%s) only restarts the analysis for the uploaded file',
    async (status) => {
      vi.mocked(startAnalysis).mockRejectedValueOnce(new ApiError({ status, message: 'busy' }))
      const { result } = renderFlow()
      await startUntilAnalyzing(result)
      expect(result.current.state).toMatchObject({ name: 'failed', retry: 'start' })

      await act(async () => {
        result.current.retry()
      })

      expect(result.current.state).toEqual({ name: 'analyzing', analysisId: 'an-1' })
      expect(uploadFileWithProgress).toHaveBeenCalledOnce()
      expect(startAnalysis).toHaveBeenCalledTimes(2)
      expect(startAnalysis).toHaveBeenLastCalledWith({ fileId: 'f1', type: 'FULL' })
    },
  )

  it('retry after a missing file (404) goes back to the preview for a fresh upload', async () => {
    vi.mocked(startAnalysis).mockRejectedValueOnce(new ApiError({ status: 404, message: 'gone' }))
    const { result } = renderFlow()
    await startUntilAnalyzing(result)
    expect(result.current.state).toMatchObject({ name: 'failed', retry: 'reset' })

    act(() => result.current.retry())

    expect(result.current.state).toEqual({ name: 'idle' })
    expect(startAnalysis).toHaveBeenCalledOnce()
  })

  it('unmount while starting never opens a stream or navigates', async () => {
    let resolveStart!: (a: Analysis) => void
    vi.mocked(startAnalysis).mockImplementation(
      () => new Promise<Analysis>((resolve) => (resolveStart = resolve)),
    )
    const { result, onComplete, unmount } = renderFlow()
    let started!: Promise<void>
    act(() => {
      started = result.current.start(videoFile())
    })
    await waitFor(() => expect(startAnalysis).toHaveBeenCalled())
    unmount()
    await act(async () => {
      resolveStart(analysis({ id: 'an-1' }))
      await started
    })
    expect(streamAnalysis).not.toHaveBeenCalled()
    expect(getAnalysis).not.toHaveBeenCalled()
    expect(onComplete).not.toHaveBeenCalled()
  })

  it('unmount while analyzing closes the stream and ignores a late result', async () => {
    let streamSignal: AbortSignal | undefined
    vi.mocked(streamAnalysis).mockImplementation((_id, handlers, signal) => {
      streamSignal = signal
      streamHandlers = { onProgress: handlers.onProgress, onResult: handlers.onResult }
      return new Promise<void>(() => {})
    })
    const { result, onComplete, unmount } = renderFlow()
    await startUntilAnalyzing(result)
    unmount()
    expect(streamSignal?.aborted).toBe(true)
    streamHandlers.onResult({
      analysisId: 'an-1',
      status: 'COMPLETED',
      verdict: 'REAL',
      confidence: 0.9,
    })
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(onComplete).not.toHaveBeenCalled()
  })

  it('cancel right as the upload finishes never creates an analysis', async () => {
    let resolveUpload!: (value: Awaited<ReturnType<typeof uploadFileWithProgress>>) => void
    vi.mocked(uploadFileWithProgress).mockImplementation(
      () => new Promise((resolve) => (resolveUpload = resolve)),
    )
    const { result } = renderFlow()
    let started!: Promise<void>
    act(() => {
      started = result.current.start(videoFile())
    })
    await waitFor(() => expect(result.current.state.name).toBe('uploading'))
    await act(async () => {
      // The XHR already finished; the user's click lands before start() resumes.
      resolveUpload({ fileId: 'f1', fileKey: 'k1', size: 1, mimetype: 'video/mp4' })
      await result.current.cancel()
      await started
    })
    expect(startAnalysis).not.toHaveBeenCalled()
    expect(result.current.state).toEqual({ name: 'idle' })
  })

  it('cancel while the start request is in flight cancels the created analysis', async () => {
    let resolveStart!: (a: Analysis) => void
    vi.mocked(startAnalysis).mockImplementation(
      () => new Promise<Analysis>((resolve) => (resolveStart = resolve)),
    )
    vi.mocked(cancelAnalysis).mockResolvedValue(analysis({ status: 'CANCELLED' }))
    const { result } = renderFlow()
    let started!: Promise<void>
    act(() => {
      started = result.current.start(videoFile())
    })
    await waitFor(() => expect(result.current.state.name).toBe('starting'))
    await act(async () => {
      await result.current.cancel()
      resolveStart(analysis({ id: 'an-1' }))
      await started
    })
    expect(result.current.state).toEqual({ name: 'idle' })
    expect(cancelAnalysis).toHaveBeenCalledWith('an-1')
    expect(streamAnalysis).not.toHaveBeenCalled()
  })

  it('cancel answered with 409 reads the analysis and routes a finished one to the result', async () => {
    vi.mocked(cancelAnalysis).mockRejectedValue(new ApiError({ status: 409, message: 'done' }))
    vi.mocked(getAnalysis).mockResolvedValue(analysis({ status: 'COMPLETED' }))
    const { result, onComplete } = renderFlow()
    await startUntilAnalyzing(result)

    await act(async () => {
      await result.current.cancel()
    })

    expect(getAnalysis).toHaveBeenCalledWith('an-1', expect.any(AbortSignal))
    expect(onComplete).toHaveBeenCalledExactlyOnceWith('an-1')
    expect(result.current.cancelling).toBe(false)
  })

  it('a failed cancel shows an inline error and keeps tracking the analysis', async () => {
    let streamSignal: AbortSignal | undefined
    vi.mocked(streamAnalysis).mockImplementation((_id, handlers, signal) => {
      streamSignal = signal
      return new Promise<void>((resolve) => {
        streamHandlers = {
          onProgress: handlers.onProgress,
          onResult: (event) => {
            handlers.onResult(event)
            resolve()
          },
        }
      })
    })
    vi.mocked(cancelAnalysis).mockRejectedValue(new ApiError({ status: 503, message: 'down' }))
    const { result, onComplete } = renderFlow()
    await startUntilAnalyzing(result)

    await act(async () => {
      await result.current.cancel()
    })

    expect(result.current.state).toEqual({ name: 'analyzing', analysisId: 'an-1' })
    expect(result.current.cancelling).toBe(false)
    expect(result.current.cancelError).toBe('Nie udało się anulować analizy. Spróbuj ponownie.')
    expect(streamSignal?.aborted).toBe(false)

    act(() => {
      streamHandlers.onResult({
        analysisId: 'an-1',
        status: 'COMPLETED',
        verdict: 'REAL',
        confidence: 0.9,
      })
    })
    await waitFor(() => expect(onComplete).toHaveBeenCalledWith('an-1'))
  })

  it('stops offering cancel once watching ended at the absolute age limit', async () => {
    vi.useFakeTimers()
    vi.mocked(startAnalysis).mockResolvedValue(
      analysis({ createdAt: new Date(Date.now() - 7200 * 1000).toISOString() }),
    )
    vi.mocked(streamAnalysis).mockResolvedValue()
    vi.mocked(getAnalysis).mockResolvedValue(analysis())
    const { result } = renderFlow()
    await startUntilAnalyzing(result)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(91500)
    })
    expect(result.current.state).toEqual({
      name: 'stillRunning',
      analysisId: 'an-1',
      watching: false,
      timedOut: true,
    })
    await act(async () => {
      await result.current.cancel()
    })
    expect(cancelAnalysis).not.toHaveBeenCalled()
  })

  it('a hanging cancel request times out into an inline error', async () => {
    vi.useFakeTimers()
    vi.mocked(cancelAnalysis).mockImplementation(() => new Promise(() => {}))
    const { result } = renderFlow()
    await startUntilAnalyzing(result)
    let cancelled!: Promise<void>
    act(() => {
      cancelled = result.current.cancel()
    })
    expect(result.current.cancelling).toBe(true)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10000)
      await cancelled
    })
    expect(result.current.cancelling).toBe(false)
    expect(result.current.cancelError).toBe('Nie udało się anulować analizy. Spróbuj ponownie.')
    expect(result.current.state.name).toBe('analyzing')
  })
})
