import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/errors'
import type { Analysis, FileMetadata } from '@/api/types'

vi.mock('@/api/files', () => ({ getFileMetadata: vi.fn() }))
vi.mock('./ReportPdfButton', () => ({ ReportPdfButton: () => null }))

import { getFileMetadata } from '@/api/files'

import { ResultHeader } from './ResultHeader'

const FILE_ID = '550e8400-e29b-41d4-a716-446655440000'
let sequence = 0

function analysis(fileKey = `${FILE_ID}.mp4`): Analysis {
  return {
    id: `analysis-${++sequence}`,
    userId: 'user',
    fileId: `file-${sequence}`,
    fileKey,
    type: 'VIDEO',
    status: 'FAILED',
    verdict: null,
    confidence: null,
    videoProb: null,
    audioProb: null,
    details: null,
    errorMessage: 'error',
    createdAt: '2026-10-15T10:00:00Z',
    updatedAt: '2026-10-15T10:01:00Z',
  }
}

function metadata(name: string | null): FileMetadata {
  return {
    fileId: FILE_ID,
    objectKey: `${FILE_ID}.mp4`,
    name,
    size: 1,
    duration: 3,
    mimetype: 'video/mp4',
  }
}

function header(value: Analysis) {
  return (
    <MemoryRouter>
      <ResultHeader analysis={value} />
    </MemoryRouter>
  )
}

beforeEach(() => vi.resetAllMocks())

describe('ResultHeader filename', () => {
  it('loads the original name from File Service and hides the storage UUID', async () => {
    const value = analysis()
    vi.mocked(getFileMetadata).mockResolvedValue(metadata('Wakacje.mp4'))

    render(header(value))

    expect(await screen.findByRole('heading', { name: 'Wakacje.mp4' })).toHaveAttribute(
      'title',
      'Wakacje.mp4',
    )
    expect(getFileMetadata).toHaveBeenCalledExactlyOnceWith(value.fileId)
    expect(screen.queryByText(new RegExp(FILE_ID))).not.toBeInTheDocument()
  })

  it('reserves a heading while loading, then removes control characters from a long name', async () => {
    let resolve!: (value: FileMetadata) => void
    vi.mocked(getFileMetadata).mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    render(header(analysis()))

    const heading = screen.getByRole('heading', { name: 'Ładowanie nazwy pliku…' })
    expect(heading).toHaveAttribute('aria-busy', 'true')
    expect(heading).not.toHaveTextContent(FILE_ID)
    const name = `${'bardzo-długa-nazwa-'.repeat(30)}.mp4`
    await act(async () => resolve(metadata(`\u0000\n\u202e${name}\u007f`)))

    expect(screen.getByRole('heading', { name })).toBe(heading)
    expect(heading).toHaveAttribute('aria-busy', 'false')
    expect(heading).toHaveAttribute('title', name)
  })

  it.each([
    new ApiError({ status: 404, message: 'gone' }),
    new ApiError({ status: 429, message: 'rate limit' }),
    new TypeError('Network down'),
  ])('uses a neutral fallback when metadata fails: %s', async (error) => {
    vi.mocked(getFileMetadata).mockRejectedValue(error)
    render(header(analysis()))

    expect(await screen.findByRole('heading', { name: 'Plik bez nazwy' })).toHaveAttribute(
      'aria-busy',
      'false',
    )
    expect(screen.queryByText(new RegExp(FILE_ID))).not.toBeInTheDocument()
  })

  it.each([null, '', '\u0000\n\u202e'])(
    'uses a neutral fallback for an unavailable name (%s)',
    async (name) => {
      vi.mocked(getFileMetadata).mockResolvedValue(metadata(name))
      render(header(analysis()))

      expect(await screen.findByRole('heading', { name: 'Plik bez nazwy' })).toBeInTheDocument()
    },
  )

  it('keeps the original name from a historical key when metadata is unavailable', async () => {
    vi.mocked(getFileMetadata).mockRejectedValue(new ApiError({ status: 404, message: 'gone' }))
    render(header(analysis(`${FILE_ID}_stare\n-nagranie.mp4`)))

    expect(await screen.findByRole('heading', { name: 'stare-nagranie.mp4' })).toBeInTheDocument()
    expect(screen.queryByText(new RegExp(FILE_ID))).not.toBeInTheDocument()
  })

  it('never shows the previous file name when the file changes or its request finishes late', async () => {
    let resolveFirst!: (value: FileMetadata) => void
    const first = analysis()
    const second = analysis()
    vi.mocked(getFileMetadata)
      .mockReturnValueOnce(
        new Promise((done) => {
          resolveFirst = done
        }),
      )
      .mockResolvedValueOnce(metadata('drugie.mp4'))
    const view = render(header(first))
    view.rerender(header(second))

    expect(await screen.findByRole('heading', { name: 'drugie.mp4' })).toBeInTheDocument()
    await act(async () => resolveFirst(metadata('pierwsze.mp4')))
    expect(screen.queryByRole('heading', { name: 'pierwsze.mp4' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'drugie.mp4' })).toBeInTheDocument()
  })

  it('replaces a loaded name with the placeholder immediately when the file changes', async () => {
    vi.mocked(getFileMetadata)
      .mockResolvedValueOnce(metadata('poprzednie.mp4'))
      .mockReturnValueOnce(new Promise(() => {}))
    const view = render(header(analysis()))
    await screen.findByRole('heading', { name: 'poprzednie.mp4' })

    view.rerender(header(analysis()))

    expect(screen.queryByRole('heading', { name: 'poprzednie.mp4' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Ładowanie nazwy pliku…' })).toHaveAttribute(
      'aria-busy',
      'true',
    )
  })

  it('uses cached metadata on remount without requesting it again', async () => {
    const value = analysis()
    vi.mocked(getFileMetadata).mockResolvedValue(metadata('z-cache.mp4'))
    const view = render(header(value))
    await screen.findByRole('heading', { name: 'z-cache.mp4' })
    view.unmount()
    render(header(value))

    expect(screen.getByRole('heading', { name: 'z-cache.mp4' })).toBeInTheDocument()
    await waitFor(() => expect(getFileMetadata).toHaveBeenCalledTimes(1))
  })
})
