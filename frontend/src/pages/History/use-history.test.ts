import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'

import { ApiError } from '@/api/errors'
import type { AnalysisSummary, Paged } from '@/api/types'
import { useHistory } from './use-history'

// Mockujemy warstwę API — hook testujemy w izolacji od HTTP.
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

  it('błąd API → stan error', async () => {
    vi.mocked(listAnalyses).mockRejectedValue(new ApiError({ status: 500, message: 'boom' }))

    const { result } = renderHook(() => useHistory())

    await waitFor(() => expect(result.current.state).toBe('error'))
  })

  it('AbortError nie jest traktowany jako błąd (zostaje loading)', async () => {
    vi.mocked(listAnalyses).mockRejectedValue(new DOMException('aborted', 'AbortError'))

    const { result } = renderHook(() => useHistory())

    // Pozwól odrzuceniu się rozpropagować, potem sprawdź że NIE wpadliśmy w error.
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(result.current.state).toBe('loading')
  })
})
