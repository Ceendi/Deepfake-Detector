import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'

import { ApiError } from '@/api/errors'
import type { AnalysisSummary, Paged } from '@/api/types'
import { useHistory } from './use-history'

vi.mock('@/api/analysis', () => ({
  listAnalyses: vi.fn(),
}))
import { listAnalyses } from '@/api/analysis'

function summary(id: string): AnalysisSummary {
  return {
    id,
    fileId: `file-${id}`,
    type: 'VIDEO',
    status: 'COMPLETED',
    verdict: 'REAL',
    confidence: 0.9,
    createdAt: '2026-06-13T10:00:00.000Z',
    updatedAt: '2026-06-13T10:00:00.000Z',
  }
}

function paged(content: AnalysisSummary[], totalPages: number): Paged<AnalysisSummary> {
  return {
    content,
    page: { size: 100, number: 0, totalElements: content.length, totalPages },
  }
}

describe('useHistory', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('ładuje pojedynczą stronę i przechodzi w stan ready', async () => {
    vi.mocked(listAnalyses).mockResolvedValue(paged([summary('a'), summary('b')], 1))

    const { result } = renderHook(() => useHistory())

    expect(result.current.state).toBe('loading')
    await waitFor(() => expect(result.current.state).toBe('ready'))
    expect(result.current.items.map((i) => i.id)).toEqual(['a', 'b'])
  })

  it('dociąga i skleja wszystkie strony gdy totalPages > 1', async () => {
    vi.mocked(listAnalyses).mockImplementation((page) =>
      Promise.resolve(page === 0 ? paged([summary('a')], 2) : paged([summary('b')], 2)),
    )

    const { result } = renderHook(() => useHistory())

    await waitFor(() => expect(result.current.state).toBe('ready'))
    expect(result.current.items.map((i) => i.id)).toEqual(['a', 'b'])
    expect(vi.mocked(listAnalyses)).toHaveBeenCalledTimes(2)
  })

  it('usuwa duplikaty po id, gdy strony nachodzą na siebie (nowe analizy w trakcie ładowania)', async () => {
    vi.mocked(listAnalyses).mockImplementation((page) =>
      Promise.resolve(
        page === 0
          ? paged([summary('c'), summary('b')], 2)
          : paged([summary('b'), summary('a')], 2),
      ),
    )

    const { result } = renderHook(() => useHistory())

    await waitFor(() => expect(result.current.state).toBe('ready'))
    expect(result.current.items.map((i) => i.id)).toEqual(['c', 'b', 'a'])
  })

  it('błąd API → stan error', async () => {
    vi.mocked(listAnalyses).mockRejectedValue(new ApiError({ status: 500, message: 'boom' }))

    const { result } = renderHook(() => useHistory())

    await waitFor(() => expect(result.current.state).toBe('error'))
  })

  it('AbortError nie jest traktowany jako błąd (zostaje loading)', async () => {
    vi.mocked(listAnalyses).mockRejectedValue(new DOMException('aborted', 'AbortError'))

    const { result } = renderHook(() => useHistory())

    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(result.current.state).toBe('loading')
  })
})

it('limits concurrent history requests to two', async () => {
  vi.mocked(listAnalyses).mockReset()
  let active = 0
  let peak = 0
  vi.mocked(listAnalyses).mockImplementation(async (page) => {
    active++
    peak = Math.max(peak, active)
    await new Promise((resolve) => setTimeout(resolve, 10))
    active--
    return paged([summary(String(page))], 8)
  })
  const { result } = renderHook(() => useHistory())
  await waitFor(() => expect(result.current.state).toBe('ready'))
  expect(peak).toBeLessThanOrEqual(2)
  expect(result.current.items.map((i) => i.id)).toEqual(['0', '1', '2', '3', '4', '5', '6', '7'])
})
it('waits for Retry-After on a history 429 and retries the rejected page', async () => {
  vi.mocked(listAnalyses).mockReset()
  vi.useFakeTimers()
  try {
    vi.mocked(listAnalyses)
      .mockRejectedValueOnce(new ApiError({ status: 429, message: 'busy', retryAfterSeconds: 2 }))
      .mockResolvedValue(paged([summary('a')], 1))
    const view = renderHook(() => useHistory())
    await act(async () => {})
    expect(view.result.current.state).toBe('loading')
    await act(() => vi.advanceTimersByTimeAsync(1999))
    expect(listAnalyses).toHaveBeenCalledTimes(1)
    await act(() => vi.advanceTimersByTimeAsync(1))
    expect(view.result.current.state).toBe('ready')
    view.unmount()
  } finally {
    vi.useRealTimers()
  }
})

it('cancels a Retry-After wait on unmount without another request', async () => {
  vi.mocked(listAnalyses).mockReset()
  vi.useFakeTimers()
  try {
    vi.mocked(listAnalyses).mockRejectedValue(new ApiError({ status: 429, message: 'busy' }))
    const view = renderHook(() => useHistory())
    await act(async () => {})
    view.unmount()
    await act(() => vi.advanceTimersByTimeAsync(60000))
    expect(listAnalyses).toHaveBeenCalledOnce()
    expect(vi.getTimerCount()).toBe(0)
  } finally {
    vi.useRealTimers()
  }
})
