import { renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/analysis', () => ({ fetchArtifact: vi.fn() }))

import { fetchArtifact } from '@/api/analysis'
import { useGradcamImages, type GradcamImage } from './use-gradcam-images'

beforeEach(() => {
  vi.stubGlobal('URL', {
    ...URL,
    createObjectURL: vi.fn((blob: Blob) => `blob:${blob.size}`),
    revokeObjectURL: vi.fn(),
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

function revokedShown(images: GradcamImage[]) {
  const revoked = new Set(vi.mocked(URL.revokeObjectURL).mock.calls.map(([url]) => url))
  return images.filter((image) => image.objectUrl != null && revoked.has(image.objectUrl))
}

describe('useGradcamImages', () => {
  it('loads each frame into an object URL and marks failed frames', async () => {
    vi.mocked(fetchArtifact).mockImplementation((url: string) =>
      url.endsWith('bad.png')
        ? Promise.reject(new Error('404'))
        : Promise.resolve(new Blob(['ab'])),
    )
    const { result, unmount } = renderHook(() => useGradcamImages(['/a.png', '/bad.png']))
    expect(result.current.map((image) => image.status)).toEqual(['loading', 'loading'])

    await waitFor(() =>
      expect(result.current).toEqual([
        { url: '/a.png', objectUrl: 'blob:2', status: 'ready' },
        { url: '/bad.png', objectUrl: null, status: 'error' },
      ]),
    )
    unmount()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:2')
  })

  it('does nothing without frames', () => {
    const { result } = renderHook(() => useGradcamImages([]))
    expect(result.current).toEqual([])
    expect(fetchArtifact).not.toHaveBeenCalled()
  })

  it('never returns an object URL it already revoked when the frame list changes', async () => {
    vi.mocked(fetchArtifact)
      .mockResolvedValueOnce(new Blob(['ab']))
      .mockImplementation(() => new Promise<Blob>(() => {}))
    const { result, rerender } = renderHook(({ urls }) => useGradcamImages(urls), {
      initialProps: { urls: ['/a.png'] },
    })
    await waitFor(() => expect(result.current[0]?.status).toBe('ready'))

    rerender({ urls: ['/a.png', '/b.png'] })
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:2')
    expect(revokedShown(result.current)).toEqual([])
    expect(result.current.map((image) => image.status)).toEqual(['loading', 'loading'])

    rerender({ urls: [] })
    rerender({ urls: ['/a.png'] })
    expect(revokedShown(result.current)).toEqual([])
    expect(result.current[0]?.status).toBe('loading')
  })

  it('ignores responses that arrive after unmount', async () => {
    let resolve: (blob: Blob) => void = () => {}
    let reject: (error: Error) => void = () => {}
    vi.mocked(fetchArtifact)
      .mockImplementationOnce(() => new Promise<Blob>((r) => (resolve = r)))
      .mockImplementationOnce(() => new Promise<Blob>((_, r) => (reject = r)))
    const { unmount } = renderHook(() => useGradcamImages(['/late.png', '/late-bad.png']))
    unmount()
    resolve(new Blob(['x']))
    reject(new Error('aborted'))
    await Promise.resolve()
    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })
})
