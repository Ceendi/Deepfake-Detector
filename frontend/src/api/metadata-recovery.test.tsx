import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from './errors'
import { useFileMeta, useFileNames } from './file-meta'

vi.mock('./files', () => ({ getFileMetadata: vi.fn() }))
import { getFileMetadata } from './files'

beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers()
})
afterEach(() => vi.useRealTimers())
const meta = (id: string) => ({
  fileId: id,
  objectKey: id,
  name: 'odzyskana.mp4',
  duration: 3,
  size: 1,
  mimetype: 'video/mp4',
})

it.each([new ApiError({ status: 429, message: 'limited' }), new TypeError('offline')])(
  'retries transient metadata failure once without remounting (%s)',
  async (error) => {
    const id = `retry-${error.name}`
    vi.mocked(getFileMetadata).mockRejectedValueOnce(error).mockResolvedValue(meta(id))
    const { result } = renderHook(() => useFileMeta(id))
    await act(() => vi.advanceTimersByTimeAsync(0))
    expect(result.current?.name).toBeNull()
    await act(() => vi.advanceTimersByTimeAsync(4999))
    expect(getFileMetadata).toHaveBeenCalledTimes(1)
    await act(() => vi.advanceTimersByTimeAsync(1))
    expect(result.current?.name).toBe('odzyskana.mp4')
    expect(getFileMetadata).toHaveBeenCalledTimes(2)
  },
)

it('bounds retries and cancels pending retry on unmount', async () => {
  vi.mocked(getFileMetadata).mockRejectedValue(new TypeError('offline'))
  const view = renderHook(() => useFileMeta('bounded-retry'))
  await act(() => vi.advanceTimersByTimeAsync(60000))
  expect(getFileMetadata).toHaveBeenCalledTimes(2)
  view.unmount()
  const next = renderHook(() => useFileMeta('cancelled-retry'))
  await act(() => vi.advanceTimersByTimeAsync(0))
  next.unmount()
  await act(() => vi.advanceTimersByTimeAsync(60000))
  expect(getFileMetadata).toHaveBeenCalledTimes(3)
})

it.each([401, 403, 404])('does not retry permanent metadata rejection %s', async (status) => {
  vi.mocked(getFileMetadata).mockRejectedValue(new ApiError({ status, message: 'denied' }))
  renderHook(() => useFileMeta(`permanent-${status}`))
  await act(() => vi.advanceTimersByTimeAsync(60000))
  expect(getFileMetadata).toHaveBeenCalledTimes(1)
})

it('reports incomplete search after throttling without restarting on render', async () => {
  vi.mocked(getFileMetadata).mockRejectedValue(new ApiError({ status: 429, message: 'limited' }))
  const ids = Array.from({ length: 20 }, (_, i) => `partial-${i}`)
  const view = renderHook(() => useFileNames(ids, true))
  await act(() => vi.advanceTimersByTimeAsync(0))
  expect(view.result.current).toMatchObject({ loading: false, incomplete: true })
  view.rerender()
  await act(() => vi.advanceTimersByTimeAsync(60000))
  expect(getFileMetadata).toHaveBeenCalledTimes(4)
})
