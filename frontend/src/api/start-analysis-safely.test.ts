import { beforeEach, expect, it, vi } from 'vitest'
import { getAnalysis, listAnalyses, startAnalysis } from './analysis'
import { ApiError, UncertainStartError } from './errors'
import { startAnalysisSafely } from './start-analysis-safely'

vi.mock('./analysis', () => ({
  getAnalysis: vi.fn(),
  listAnalyses: vi.fn(),
  startAnalysis: vi.fn(),
}))
const request = { fileId: 'file', type: 'VIDEO' as const }
beforeEach(() => vi.resetAllMocks())

it.each([400, 401, 404, 409, 429, 503])(
  'does not recover a definitive rejection (%s)',
  async (status) => {
    const error = new ApiError({ status, message: 'rejected' })
    vi.mocked(startAnalysis).mockRejectedValue(error)
    await expect(startAnalysisSafely(request, new AbortController().signal)).rejects.toBe(error)
    expect(listAnalyses).not.toHaveBeenCalled()
  },
)
it('keeps an ambiguous start uncertain if history is unavailable', async () => {
  vi.mocked(startAnalysis).mockRejectedValue(new TypeError('lost response'))
  vi.mocked(listAnalyses).mockRejectedValue(new ApiError({ status: 500, message: 'unavailable' }))
  await expect(startAnalysisSafely(request, new AbortController().signal)).rejects.toBeInstanceOf(
    UncertainStartError,
  )
  expect(startAnalysis).toHaveBeenCalledOnce()
})
it('does not start recovery after cancellation', async () => {
  const controller = new AbortController()
  vi.mocked(startAnalysis).mockImplementation(async () => {
    controller.abort()
    throw new TypeError('lost response')
  })
  await expect(startAnalysisSafely(request, controller.signal)).rejects.toMatchObject({
    name: 'AbortError',
  })
  expect(listAnalyses).not.toHaveBeenCalled()
})
it('propagates cancellation while reading recovery history', async () => {
  const controller = new AbortController()
  vi.mocked(startAnalysis).mockRejectedValue(new TypeError('lost response'))
  vi.mocked(listAnalyses).mockImplementation(async () => {
    controller.abort()
    throw controller.signal.reason
  })
  await expect(startAnalysisSafely(request, controller.signal)).rejects.toMatchObject({
    name: 'AbortError',
  })
  expect(getAnalysis).not.toHaveBeenCalled()
})
it('does not attach a different file or analysis type after an ambiguous start', async () => {
  vi.mocked(startAnalysis).mockRejectedValue(new TypeError('lost response'))
  vi.mocked(listAnalyses).mockResolvedValue({
    content: [
      { id: 'wrong-file', fileId: 'other', type: 'VIDEO' },
      { id: 'wrong-type', fileId: 'file', type: 'AUDIO' },
    ] as Awaited<ReturnType<typeof listAnalyses>>['content'],
    page: { totalPages: 1, size: 100, number: 0, totalElements: 2 },
  })
  await expect(startAnalysisSafely(request, new AbortController().signal)).rejects.toBeInstanceOf(
    UncertainStartError,
  )
  expect(getAnalysis).not.toHaveBeenCalled()
})
