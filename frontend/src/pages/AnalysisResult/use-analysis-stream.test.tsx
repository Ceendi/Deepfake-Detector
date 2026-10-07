import { act, render, renderHook, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { Analysis } from '@/api/types'
import { useAnalysisStream } from './use-analysis-stream'
import { LiveProgress } from './components/LiveProgress'

vi.mock('@/api/stream', () => ({ streamAnalysis: vi.fn() }))
vi.mock('@/api/analysis', () => ({ getAnalysis: vi.fn(), cancelAnalysis: vi.fn() }))
import { streamAnalysis } from '@/api/stream'
import { getAnalysis } from '@/api/analysis'

const active = { id: 'a1', type: 'VIDEO', status: 'PROCESSING' } as Analysis
const terminal = { ...active, status: 'COMPLETED', verdict: 'FAKE', confidence: 0.8 } as Analysis

beforeEach(() => vi.resetAllMocks())
afterEach(() => vi.useRealTimers())

it.each(['eof', 'error'])('result screen recovers full terminal data after %s', async (ending) => {
  vi.mocked(streamAnalysis).mockImplementation(() =>
    ending === 'eof' ? Promise.resolve() : Promise.reject(new TypeError('Offline')),
  )
  vi.mocked(getAnalysis).mockResolvedValue(terminal)
  const onResult = vi.fn()
  const onError = vi.fn()
  renderHook(() => useAnalysisStream('a1', onResult, onError))
  await waitFor(() => expect(onResult).toHaveBeenCalledExactlyOnceWith(terminal))
  expect(onError).not.toHaveBeenCalled()
})

it('result screen waits through stale active reads then supplies terminal data', async () => {
  vi.useFakeTimers()
  vi.mocked(streamAnalysis).mockResolvedValue()
  vi.mocked(getAnalysis)
    .mockResolvedValueOnce(active)
    .mockResolvedValueOnce(active)
    .mockResolvedValue(terminal)
  const onResult = vi.fn()
  renderHook(() => useAnalysisStream('a1', onResult, vi.fn()))
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1499)
  })
  expect(onResult).not.toHaveBeenCalled()
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1)
  })
  expect(onResult).toHaveBeenCalledExactlyOnceWith(terminal)
})

it('result screen stops showing running when recovery is exhausted', async () => {
  vi.useFakeTimers()
  vi.mocked(streamAnalysis).mockResolvedValue()
  vi.mocked(getAnalysis).mockResolvedValue(active)
  render(<LiveProgress analysis={active} onSettled={vi.fn()} />)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(91500)
  })
  expect(screen.getByText('Utracono połączenie z analizą')).toBeInTheDocument()
  expect(screen.queryByText('Analiza w toku…')).not.toBeInTheDocument()
  expect(getAnalysis).toHaveBeenCalledTimes(9)
})

it('unmount aborts recovery and prevents late callbacks', async () => {
  vi.useFakeTimers()
  vi.mocked(streamAnalysis).mockResolvedValue()
  vi.mocked(getAnalysis).mockResolvedValue(active)
  const onResult = vi.fn()
  const onError = vi.fn()
  const { unmount } = renderHook(() => useAnalysisStream('a1', onResult, onError))
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
  unmount()
  await act(async () => {
    await vi.advanceTimersByTimeAsync(300000)
  })
  expect(getAnalysis).toHaveBeenCalledTimes(1)
  expect(onResult).not.toHaveBeenCalled()
  expect(onError).not.toHaveBeenCalled()
})
