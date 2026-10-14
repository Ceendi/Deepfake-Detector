import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/errors'
import type { Analysis } from '@/api/types'
import { useAnalysisStream } from './use-analysis-stream'
import { LiveProgress } from './components/LiveProgress'

vi.mock('@/api/stream', () => ({ streamAnalysis: vi.fn() }))
vi.mock('@/api/analysis', () => ({ getAnalysis: vi.fn(), cancelAnalysis: vi.fn() }))
import { streamAnalysis } from '@/api/stream'
import { cancelAnalysis, getAnalysis } from '@/api/analysis'

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

it('result screen keeps a long analysis live and reopens the stream', async () => {
  vi.useFakeTimers()
  vi.mocked(streamAnalysis).mockResolvedValue()
  vi.mocked(getAnalysis).mockResolvedValue(active)
  const onSettled = vi.fn()
  const live = { ...active, createdAt: new Date(Date.now()).toISOString() }
  render(<LiveProgress analysis={live} onSettled={onSettled} />)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(91500 + 5000)
  })
  expect(screen.queryByText('Utracono połączenie z analizą')).not.toBeInTheDocument()
  expect(screen.getByText('Analiza w toku…')).toBeInTheDocument()
  expect(streamAnalysis).toHaveBeenCalledTimes(2)
  vi.mocked(getAnalysis).mockResolvedValue(terminal)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(10000)
  })
  expect(onSettled).toHaveBeenCalledExactlyOnceWith(terminal)
})

it('result screen reports lost connection only after the absolute analysis limit', async () => {
  vi.useFakeTimers()
  vi.mocked(streamAnalysis).mockResolvedValue()
  vi.mocked(getAnalysis).mockResolvedValue(active)
  const old = { ...active, createdAt: new Date(Date.now() - 7200 * 1000).toISOString() }
  render(<LiveProgress analysis={old} onSettled={vi.fn()} />)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(91500)
  })
  expect(screen.getByText('Utracono połączenie z analizą')).toBeInTheDocument()
  expect(getAnalysis).toHaveBeenCalledTimes(9)
})

it('cancel answered with 409 shows the finished analysis instead of spinning', async () => {
  vi.mocked(streamAnalysis).mockImplementation(() => new Promise(() => {}))
  vi.mocked(cancelAnalysis).mockRejectedValue(new ApiError({ status: 409, message: 'done' }))
  vi.mocked(getAnalysis).mockResolvedValue(terminal)
  const onSettled = vi.fn()
  render(<LiveProgress analysis={active} onSettled={onSettled} />)
  fireEvent.click(screen.getByRole('button', { name: 'Anuluj analizę' }))
  await waitFor(() => expect(onSettled).toHaveBeenCalledExactlyOnceWith(terminal))
})

it('cancel with 200 passes the cancelled analysis to the page', async () => {
  vi.mocked(streamAnalysis).mockImplementation(() => new Promise(() => {}))
  const cancelled = { ...active, status: 'CANCELLED' } as Analysis
  vi.mocked(cancelAnalysis).mockResolvedValue(cancelled)
  const onSettled = vi.fn()
  render(<LiveProgress analysis={active} onSettled={onSettled} />)
  fireEvent.click(screen.getByRole('button', { name: 'Anuluj analizę' }))
  await waitFor(() => expect(onSettled).toHaveBeenCalledExactlyOnceWith(cancelled))
})

it('a failed cancel shows an error, stops the spinner and keeps the stream open', async () => {
  let streamSignal: AbortSignal | undefined
  vi.mocked(streamAnalysis).mockImplementation((_id, _handlers, signal) => {
    streamSignal = signal
    return new Promise(() => {})
  })
  vi.mocked(cancelAnalysis).mockRejectedValue(new ApiError({ status: 503, message: 'down' }))
  const onSettled = vi.fn()
  render(<LiveProgress analysis={active} onSettled={onSettled} />)
  const button = screen.getByRole('button', { name: 'Anuluj analizę' })
  fireEvent.click(button)
  expect(
    await screen.findByText('Nie udało się anulować analizy. Spróbuj ponownie.'),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Anuluj analizę' })).not.toBeDisabled()
  expect(onSettled).not.toHaveBeenCalled()
  expect(streamSignal?.aborted).toBe(false)
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
