import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'

import { ApiError } from '@/api/errors'
import type { Analysis, UploadResponse } from '@/api/types'

import { BATCH_LIMITS } from './upload-utils'
import { useBatchQueue } from './use-batch-queue'

vi.mock('@/api/files', () => ({ uploadFileWithProgress: vi.fn() }))
vi.mock('@/api/analysis', () => ({
  startAnalysis: vi.fn(),
  cancelAnalysis: vi.fn(),
  listAnalyses: vi.fn(),
  getAnalysis: vi.fn(),
}))
vi.mock('@/api/stream', () => ({ streamAnalysis: vi.fn() }))
vi.mock('@/api/cancel-analysis', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/cancel-analysis')>()),
  cancelAndSettle: vi.fn(),
}))
vi.mock('@/api/watch-analysis', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/watch-analysis')>()),
  watchAnalysis: vi.fn(),
}))
vi.mock('./media-duration', () => ({ probeDuration: vi.fn() }))

import { uploadFileWithProgress } from '@/api/files'
import { startAnalysis, listAnalyses, getAnalysis } from '@/api/analysis'
import { cancelAndSettle } from '@/api/cancel-analysis'
import { watchAnalysis } from '@/api/watch-analysis'
import { probeDuration } from './media-duration'

function analysis(overrides: Partial<Analysis> = {}): Analysis {
  return {
    id: 'an-1',
    userId: 'u-1',
    fileId: 'f1',
    fileKey: 'k1',
    type: 'FULL',
    status: 'PENDING',
    verdict: null,
    confidence: null,
    videoProb: null,
    audioProb: null,
    details: null,
    errorMessage: null,
    createdAt: '2026-10-28T10:00:00.000Z',
    updatedAt: '2026-10-28T10:00:00.000Z',
    ...overrides,
  }
}

function clip(name: string) {
  return new File(['x'], name, { type: 'video/mp4' })
}

const uploaded = (file: File): UploadResponse => ({
  fileId: `f-${file.name}`,
  fileKey: 'k',
  size: 1,
  mimetype: 'video/mp4',
})

// Uploads stay in flight until the test resolves them; aborting rejects like the real XHR.
let inFlight: { file: File; signal?: AbortSignal; resolve: () => void }[]
// Result callbacks of watched analyses, by analysis id.
let results: Map<string, (result: Analysis) => void>

function holdUploads() {
  vi.mocked(uploadFileWithProgress).mockImplementation(
    (file, opts) =>
      new Promise((resolve, reject) => {
        inFlight.push({ file, signal: opts?.signal, resolve: () => resolve(uploaded(file)) })
        opts?.signal?.addEventListener('abort', () =>
          reject(new DOMException('Upload przerwany.', 'AbortError')),
        )
      }),
  )
}

async function flush() {
  await act(async () => {})
}

async function advance(ms: number) {
  await act(() => vi.advanceTimersByTimeAsync(ms))
}

function renderQueue(names: string[]) {
  const view = renderHook(() => useBatchQueue())
  act(() => view.result.current.add(names.map(clip)))
  return view
}

const statusOf = (view: ReturnType<typeof renderQueue>) =>
  view.result.current.items.map((item) => item.status)

beforeEach(() => {
  vi.resetAllMocks()
  inFlight = []
  results = new Map()
  vi.mocked(probeDuration).mockResolvedValue(null)
  vi.mocked(uploadFileWithProgress).mockImplementation((file) => Promise.resolve(uploaded(file)))
  vi.mocked(startAnalysis).mockImplementation(({ fileId }) =>
    Promise.resolve(analysis({ id: `an-${fileId}`, fileId })),
  )
  vi.mocked(watchAnalysis).mockImplementation((id, handlers) => {
    results.set(id, handlers.onResult)
    return new Promise(() => {})
  })
})

afterEach(() => {
  vi.useRealTimers()
})

describe('useBatchQueue', () => {
  it('marks invalid files as failed up front and never uploads them', async () => {
    const view = renderHook(() => useBatchQueue())
    act(() =>
      view.result.current.add([
        new File(['x'], 'notes.txt', { type: 'text/plain' }),
        clip('a.mp4'),
      ]),
    )
    expect(view.result.current.items[0]).toMatchObject({
      status: 'failed',
      message: expect.stringContaining('Nieobsługiwany format'),
    })

    act(() => view.result.current.start())
    await flush()

    expect(uploadFileWithProgress).toHaveBeenCalledOnce()
    expect(statusOf(view)).toEqual(['failed', 'analyzing'])
  })

  it('keeps at most 2 uploads and 3 analyses in flight and fills slots as they free', async () => {
    holdUploads()
    const view = renderQueue(['a.mp4', 'b.mp4', 'c.mp4', 'd.mp4', 'e.mp4'])
    act(() => view.result.current.start())
    await flush()

    expect(inFlight).toHaveLength(BATCH_LIMITS.uploads)
    expect(statusOf(view)).toEqual(['uploading', 'uploading', 'pending', 'pending', 'pending'])

    // Resolve every upload as it starts; analyses take over the uploaded files.
    for (let done = 0; done < 5; done += 1) {
      inFlight[done].resolve()
      await flush()
    }

    expect(inFlight).toHaveLength(5)
    expect(startAnalysis).toHaveBeenCalledTimes(BATCH_LIMITS.analyses)
    expect(watchAnalysis).toHaveBeenCalledTimes(BATCH_LIMITS.analyses)
    expect(statusOf(view)).toEqual(['analyzing', 'analyzing', 'analyzing', 'queued', 'queued'])

    await act(async () =>
      results.get('an-f-b.mp4')!(analysis({ status: 'COMPLETED', verdict: 'FAKE' })),
    )

    expect(view.result.current.items[1]).toMatchObject({ status: 'completed', verdict: 'FAKE' })
    expect(startAnalysis).toHaveBeenCalledTimes(4)
    expect(startAnalysis).toHaveBeenLastCalledWith({ fileId: 'f-d.mp4', type: 'FULL' })
    expect(statusOf(view)).toEqual(['analyzing', 'completed', 'analyzing', 'analyzing', 'queued'])
  })

  it('uploads at most one analysis batch ahead while analyses are busy', async () => {
    holdUploads()
    const view = renderQueue(['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'].map((n) => `${n}.mp4`))
    act(() => view.result.current.start())
    await flush()

    // Analyses never finish here; resolve every upload that the queue lets start.
    for (let done = 0; done < inFlight.length; done += 1) {
      inFlight[done].resolve()
      await flush()
    }

    expect(inFlight).toHaveLength(BATCH_LIMITS.analyses * 2)
    expect(statusOf(view)).toEqual([
      ...Array(BATCH_LIMITS.analyses).fill('analyzing'),
      ...Array(BATCH_LIMITS.analyses).fill('queued'),
      'pending',
      'pending',
    ])

    await act(async () => results.get('an-f-a.mp4')!(analysis({ status: 'COMPLETED' })))
    await flush()
    expect(inFlight).toHaveLength(BATCH_LIMITS.analyses * 2 + 1)
  })

  it('waits Retry-After before retrying the same upload', async () => {
    vi.useFakeTimers()
    vi.mocked(uploadFileWithProgress).mockRejectedValueOnce(
      new ApiError({
        status: 429,
        message: 'limit',
        code: 'UPLOAD_USER_LIMIT',
        retryAfterSeconds: 7,
      }),
    )
    const view = renderQueue(['a.mp4'])
    act(() => view.result.current.start())
    await flush()

    expect(view.result.current.items[0]).toMatchObject({
      status: 'pending',
      message: expect.stringContaining('7 s'),
    })
    await advance(6999)
    expect(uploadFileWithProgress).toHaveBeenCalledOnce()

    await advance(1)
    expect(uploadFileWithProgress).toHaveBeenCalledTimes(2)
    expect(statusOf(view)).toEqual(['analyzing'])
  })

  it('backs off 5 s, then 10 s when a 429 has no Retry-After', async () => {
    vi.useFakeTimers()
    const rateLimited = new ApiError({ status: 429, message: 'Too Many Requests' })
    vi.mocked(uploadFileWithProgress)
      .mockRejectedValueOnce(rateLimited)
      .mockRejectedValueOnce(rateLimited)
    const view = renderQueue(['a.mp4'])
    act(() => view.result.current.start())
    await flush()

    await advance(4999)
    expect(uploadFileWithProgress).toHaveBeenCalledOnce()
    await advance(1)
    expect(uploadFileWithProgress).toHaveBeenCalledTimes(2)

    await advance(9999)
    expect(uploadFileWithProgress).toHaveBeenCalledTimes(2)
    await advance(1)
    expect(uploadFileWithProgress).toHaveBeenCalledTimes(3)
    expect(statusOf(view)).toEqual(['analyzing'])
  })

  it('retries a backpressured analysis start after retryAfterSeconds from the body', async () => {
    vi.useFakeTimers()
    vi.mocked(startAnalysis).mockRejectedValueOnce(
      new ApiError({
        status: 429,
        message: 'busy',
        backpressure: { reason: 'USER_LIMIT', limit: 3, queuePosition: 4, retryAfterSeconds: 3 },
      }),
    )
    const view = renderQueue(['a.mp4'])
    act(() => view.result.current.start())
    await flush()

    expect(statusOf(view)).toEqual(['queued'])
    await advance(3000)
    expect(startAnalysis).toHaveBeenCalledTimes(2)
    expect(statusOf(view)).toEqual(['analyzing'])
    expect(uploadFileWithProgress).toHaveBeenCalledOnce()
  })

  it('a permanent error fails only that file', async () => {
    vi.mocked(uploadFileWithProgress).mockImplementation((file) =>
      file.name === 'bad.mp4'
        ? Promise.reject(new ApiError({ status: 422, message: 'x', code: 'UNSUPPORTED_MEDIA' }))
        : Promise.resolve(uploaded(file)),
    )
    vi.mocked(startAnalysis).mockImplementation(({ fileId }) =>
      fileId === 'f-gone.mp4'
        ? Promise.reject(new ApiError({ status: 404, message: 'gone' }))
        : Promise.resolve(analysis({ id: `an-${fileId}` })),
    )
    const view = renderQueue(['bad.mp4', 'gone.mp4', 'ok.mp4'])
    act(() => view.result.current.start())
    await flush()

    expect(statusOf(view)).toEqual(['failed', 'failed', 'analyzing'])
    expect(view.result.current.items[1].message).toBe(
      'Nie znaleziono przesłanego pliku — wgraj go ponownie.',
    )
  })

  it('rejects a file longer than the limit before uploading it', async () => {
    vi.mocked(probeDuration).mockResolvedValueOnce(1801)
    const view = renderQueue(['long.mp4', 'short.mp4'])
    act(() => view.result.current.start())
    await flush()

    expect(view.result.current.items[0]).toMatchObject({
      status: 'failed',
      message: expect.stringContaining('maks. 30 min'),
    })
    expect(uploadFileWithProgress).toHaveBeenCalledOnce()
    expect(statusOf(view)).toEqual(['failed', 'analyzing'])
  })

  it('cancel all stops pending files, aborts uploads and cancels started analyses', async () => {
    holdUploads()
    vi.mocked(cancelAndSettle).mockImplementation((id) =>
      Promise.resolve(analysis({ id, status: 'CANCELLED' })),
    )
    const view = renderQueue(['a.mp4', 'b.mp4', 'c.mp4', 'd.mp4', 'e.mp4'])
    act(() => view.result.current.start())
    await flush()
    inFlight[0].resolve()
    inFlight[1].resolve()
    await flush()
    expect(statusOf(view)).toEqual(['analyzing', 'analyzing', 'uploading', 'uploading', 'pending'])

    await act(async () => view.result.current.cancelAll())

    expect(cancelAndSettle).toHaveBeenCalledTimes(2)
    expect(vi.mocked(cancelAndSettle).mock.calls.map(([id]) => id)).toEqual([
      'an-f-a.mp4',
      'an-f-b.mp4',
    ])
    expect(inFlight[2].signal?.aborted).toBe(true)
    expect(inFlight[3].signal?.aborted).toBe(true)
    expect(statusOf(view)).toEqual(Array(5).fill('cancelled'))
    expect(view.result.current.finished).toBe(true)
    expect(uploadFileWithProgress).toHaveBeenCalledTimes(4)
  })

  it('keeps watching an analysis whose cancel failed', async () => {
    vi.mocked(cancelAndSettle).mockRejectedValue(new ApiError({ status: 500, message: 'x' }))
    const view = renderQueue(['a.mp4'])
    act(() => view.result.current.start())
    await flush()

    await act(async () => view.result.current.cancelAll())

    expect(view.result.current.items[0]).toMatchObject({
      status: 'analyzing',
      message: 'Nie udało się anulować analizy. Spróbuj ponownie.',
    })
    await act(async () => results.get('an-f-a.mp4')!(analysis({ status: 'CANCELLED' })))
    expect(statusOf(view)).toEqual(['cancelled'])
  })

  it('unmount aborts uploads without cancelling analyses', async () => {
    holdUploads()
    const view = renderQueue(['a.mp4'])
    act(() => view.result.current.start())
    await flush()

    view.unmount()

    expect(inFlight[0].signal?.aborted).toBe(true)
    expect(cancelAndSettle).not.toHaveBeenCalled()
  })
})

it('stops retrying a file after five upload 503s', async () => {
  vi.useFakeTimers()
  vi.mocked(uploadFileWithProgress).mockRejectedValue(
    new ApiError({ status: 503, message: 'busy', retryAfterSeconds: 1 }),
  )
  const view = renderQueue(['slow.mp4'])
  act(() => view.result.current.start())
  await flush()
  for (let i = 0; i < 4; i++) await advance(1000)
  expect(uploadFileWithProgress).toHaveBeenCalledTimes(5)
  expect(statusOf(view)).toEqual(['failed'])
  expect(view.result.current.finished).toBe(true)
  await advance(60000)
  expect(uploadFileWithProgress).toHaveBeenCalledTimes(5)
})
it.each([429, 503])('keeps waiting for upload capacity (%s UPLOAD_USER_LIMIT)', async (status) => {
  vi.useFakeTimers()
  vi.mocked(uploadFileWithProgress).mockRejectedValue(
    new ApiError({ status, code: 'UPLOAD_USER_LIMIT', message: 'busy', retryAfterSeconds: 1 }),
  )
  const view = renderQueue(['a.mp4'])
  act(() => view.result.current.start())
  await flush()
  for (let i = 0; i < 7; i++) await advance(1000)
  expect(uploadFileWithProgress).toHaveBeenCalledTimes(8)
  expect(statusOf(view)).toEqual(['pending'])
})
it('ignores upload progress events within the same whole percent', async () => {
  holdUploads()
  const view = renderQueue(['a.mp4', 'b.mp4'])
  act(() => view.result.current.start())
  await flush()
  const progress = vi.mocked(uploadFileWithProgress).mock.calls[0][1]!.onProgress!
  act(() => progress(20.1))
  const previous = view.result.current.items
  act(() => progress(20.4))
  expect(view.result.current.items).toBe(previous)
  act(() => progress(21))
  expect(view.result.current.items[0].progress).toBe(21)
  expect(view.result.current.items[1]).toBe(previous[1])
})
it('recovers a batch start after an ambiguous POST by fileId', async () => {
  vi.mocked(startAnalysis).mockRejectedValueOnce(new ApiError({ status: 504, message: 'timeout' }))
  vi.mocked(listAnalyses).mockResolvedValue({
    content: [analysis({ fileId: 'f-a.mp4' })],
    page: { number: 0, size: 100, totalPages: 1, totalElements: 1 },
  })
  vi.mocked(getAnalysis).mockResolvedValue(analysis({ fileId: 'f-a.mp4' }))
  const view = renderQueue(['a.mp4'])
  act(() => view.result.current.start())
  await flush()
  expect(statusOf(view)).toEqual(['analyzing'])
  expect(view.result.current.items[0].analysisId).toBe('an-1')
  expect(startAnalysis).toHaveBeenCalledOnce()
})
