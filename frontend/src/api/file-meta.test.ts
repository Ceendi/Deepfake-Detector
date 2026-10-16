import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/errors'

vi.mock('@/api/files', () => ({ getFileMetadata: vi.fn() }))

import { getFileMetadata } from '@/api/files'

import { useFileNames } from './file-meta'

function metadata(fileId: string, name: string | null) {
  return { fileId, objectKey: `${fileId}.mp4`, name, size: 1, duration: 3, mimetype: 'video/mp4' }
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('useFileNames', () => {
  it('nie pobiera metadanych, dopóki użytkownik nie szuka', () => {
    renderHook(() => useFileNames(['idle-1'], false))
    expect(getFileMetadata).not.toHaveBeenCalled()
  })

  it('przy wyszukiwaniu dociąga nazwy wszystkich plików, pomijając niedostępne', async () => {
    vi.mocked(getFileMetadata).mockImplementation((id) =>
      id === 'gone'
        ? Promise.reject(new ApiError({ status: 404, message: 'x' }))
        : Promise.resolve(metadata(id, `${id}.mp4`)),
    )
    const ids = ['a', 'b', 'gone', 'a']

    const { result } = renderHook(() => useFileNames(ids, true))

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect([...result.current.names.entries()]).toEqual([
      ['a', 'a.mp4'],
      ['b', 'b.mp4'],
    ])
    expect(getFileMetadata).toHaveBeenCalledTimes(3)
  })

  it('zatrzymuje ładowanie po 429 zamiast wysyłać żądania dla całej historii', async () => {
    vi.mocked(getFileMetadata).mockRejectedValue(new ApiError({ status: 429, message: 'x' }))
    const ids = Array.from({ length: 50 }, (_, i) => `rl-${i}`)

    const { result } = renderHook(() => useFileNames(ids, true))

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(vi.mocked(getFileMetadata).mock.calls.length).toBeLessThanOrEqual(4)
  })
})
