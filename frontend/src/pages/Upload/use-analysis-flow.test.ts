import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'

import { ApiError } from '@/api/errors'
import type { Analysis, AnalysisProgressEvent, AnalysisResultEvent } from '@/api/types'
import { useAnalysisFlow } from './use-analysis-flow'

// --- Mock całej warstwy API (upload, orchestrator, SSE). upload-utils zostaje REALNE
//     (pickAnalysisType / messageForError / isAbortError to czyste funkcje, już osobno przetestowane).
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

// Przechwytujemy handlery przekazane do streamAnalysis, żeby z testu „odgrywać" zdarzenia SSE.
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

// Doprowadza hook do stanu 'analyzing' (upload + start OK, stream otwarty i przechwycony).
async function startUntilAnalyzing(result: ReturnType<typeof renderFlow>['result']) {
  await act(async () => {
    await result.current.start(videoFile())
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(uploadFileWithProgress).mockResolvedValue({
    fileId: 'f1',
    fileKey: 'k1',
    size: 1,
    mimetype: 'video/mp4',
  })
  vi.mocked(startAnalysis).mockResolvedValue(analysis({ id: 'an-1' }))
  // Domyślnie stream zostaje otwarty (pending) — wynik wstrzykujemy ręcznie przez streamHandlers.
  vi.mocked(streamAnalysis).mockImplementation((_id, handlers) => {
    streamHandlers = handlers
    return new Promise<void>(() => {})
  })
})

describe('useAnalysisFlow', () => {
  it('happy path: upload → start → analyzing → COMPLETED woła onComplete', async () => {
    const { result, onComplete } = renderFlow()

    await startUntilAnalyzing(result)
    expect(result.current.state).toEqual({ name: 'analyzing', analysisId: 'an-1' })
    expect(startAnalysis).toHaveBeenCalledWith({ fileId: 'f1', fileKey: 'k1', type: 'FULL' })

    act(() => {
      streamHandlers.onResult({
        analysisId: 'an-1',
        status: 'COMPLETED',
        verdict: 'FAKE',
        confidence: 0.9,
      })
    })
    expect(onComplete).toHaveBeenCalledWith('an-1')
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
      Promise.reject(new ApiError({ status: 500, message: 'stream padł' })),
    )
    const { result } = renderFlow()

    await startUntilAnalyzing(result)

    await waitFor(() => expect(result.current.state.name).toBe('failed'))
    expect((result.current.state as { message: string }).message).toBe('stream padł')
  })

  it('wynik FAILED dociąga errorMessage z pełnego zasobu', async () => {
    vi.mocked(getAnalysis).mockResolvedValue(
      analysis({ status: 'FAILED', errorMessage: 'Detektor padł' }),
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
      expect(result.current.state).toEqual({ name: 'failed', message: 'Detektor padł' }),
    )
    expect(getAnalysis).toHaveBeenCalledWith('an-1')
  })

  it('cancel w trakcie analizy woła cancelAnalysis i wraca do idle', async () => {
    vi.mocked(cancelAnalysis).mockResolvedValue(analysis({ status: 'CANCELLED' }))
    const { result } = renderFlow()
    await startUntilAnalyzing(result)

    await act(async () => {
      await result.current.cancel()
    })

    expect(cancelAnalysis).toHaveBeenCalledWith('an-1')
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
})
