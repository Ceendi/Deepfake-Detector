import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import Upload from './Upload'

vi.mock('@/api/files', () => ({ uploadFileWithProgress: vi.fn() }))
vi.mock('@/api/analysis', () => ({
  startAnalysis: vi.fn(),
  getAnalysis: vi.fn(),
  cancelAnalysis: vi.fn(),
}))
vi.mock('@/api/stream', () => ({ streamAnalysis: vi.fn() }))
vi.mock('./media-duration', () => ({ probeDuration: vi.fn(() => Promise.resolve(null)) }))

import { uploadFileWithProgress } from '@/api/files'

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(uploadFileWithProgress).mockReset()
  Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:preview'), revokeObjectURL: vi.fn() })
})

function renderUpload() {
  const view = render(
    <MemoryRouter>
      <Upload />
    </MemoryRouter>,
  )
  const input = view.container.querySelector('input[type="file"]') as HTMLInputElement
  return { ...view, input }
}

function pick(input: HTMLInputElement, file: File) {
  fireEvent.change(input, { target: { files: [file] } })
}

function loadMetadata(media: HTMLMediaElement, duration: number) {
  Object.defineProperty(media, 'duration', { configurable: true, value: duration })
  fireEvent.loadedMetadata(media)
}

describe('Upload', () => {
  it('odrzuca przed uploadem materiał dłuższy niż 30 min (długość z podglądu)', () => {
    const { container, input } = renderUpload()
    pick(input, new File(['x'], 'long.mp4', { type: 'video/mp4' }))

    loadMetadata(container.querySelector('video')!, 1801)

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Materiał przekracza dopuszczalną długość (maks. 30 min).',
    )
    expect(screen.queryByRole('button', { name: /Analizuj plik/ })).not.toBeInTheDocument()
    expect(uploadFileWithProgress).not.toHaveBeenCalled()
  })

  it('przepuszcza materiał w limicie', () => {
    const { container, input } = renderUpload()
    pick(input, new File(['x'], 'short.wav', { type: 'audio/wav' }))

    loadMetadata(container.querySelector('audio')!, 1800)

    expect(screen.getByRole('button', { name: /Analizuj plik/ })).toBeInTheDocument()
  })

  it('AVI bez podglądu w przeglądarce: komunikat zamiast pustego odtwarzacza, analiza możliwa', () => {
    const { container, input } = renderUpload()
    pick(input, new File(['x'], 'clip.avi', { type: 'video/x-msvideo' }))

    fireEvent.error(container.querySelector('video')!)

    expect(screen.getByText(/Podgląd niedostępny dla tego formatu/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Analizuj plik/ })).toBeInTheDocument()
  })

  it('odrzuca WEBM jeszcze w przeglądarce (backend go nie przyjmuje)', () => {
    const { input } = renderUpload()
    pick(input, new File(['x'], 'clip.webm', { type: 'video/webm' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Nieobsługiwany format pliku (.webm)')
  })

  it('ostrzega przed zamknięciem karty tylko w trakcie wysyłki', async () => {
    vi.mocked(uploadFileWithProgress).mockReturnValue(new Promise(() => {}))
    const { input } = renderUpload()
    pick(input, new File(['x'], 'clip.mp4', { type: 'video/mp4' }))

    const idle = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(idle)
    expect(idle.defaultPrevented).toBe(false)

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Analizuj plik/ }))
    })

    const uploading = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(uploading)
    expect(uploading.defaultPrevented).toBe(true)
  })
})

describe('Upload — wiele plików', () => {
  it('pokazuje listę z błędem przy niepoprawnym pliku zamiast podglądu', () => {
    const { container, input } = renderUpload()
    fireEvent.change(input, {
      target: {
        files: [
          new File(['x'], 'a.mp4', { type: 'video/mp4' }),
          new File(['x'], 'notatka.txt', { type: 'text/plain' }),
        ],
      },
    })

    expect(screen.getByRole('heading', { name: '2 pliki' })).toBeInTheDocument()
    expect(screen.getByRole('listitem', { name: 'a.mp4' })).toHaveTextContent('Czeka')
    expect(screen.getByRole('listitem', { name: 'notatka.txt' })).toHaveTextContent(
      'Nieobsługiwany format pliku (.txt)',
    )
    expect(screen.getByRole('button', { name: 'Analizuj wszystkie (1)' })).toBeEnabled()
    expect(container.querySelector('video')).toBeNull()
  })

  it('wysyła pliki z kolejki i ostrzega przed zamknięciem karty', async () => {
    vi.mocked(uploadFileWithProgress).mockReturnValue(new Promise(() => {}))
    const { input } = renderUpload()
    fireEvent.change(input, {
      target: {
        files: ['a.mp4', 'b.wav', 'c.mp4'].map((name) => new File(['x'], name)),
      },
    })

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Analizuj wszystkie (3)' }))
    })

    expect(uploadFileWithProgress).toHaveBeenCalledTimes(2)
    expect(screen.getByRole('listitem', { name: 'c.mp4' })).toHaveTextContent('Czeka')
    const leaving = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(leaving)
    expect(leaving.defaultPrevented).toBe(true)
  })

  it('folder bez plików wideo ani audio daje komunikat', () => {
    const { input } = renderUpload()
    Object.defineProperty(input, 'webkitdirectory', { configurable: true, value: true })
    fireEvent.change(input, { target: { files: [new File(['x'], '.DS_Store')] } })

    expect(screen.getByRole('alert')).toHaveTextContent(
      'W wybranym folderze nie ma plików wideo ani audio.',
    )
  })
})

it('prevents browser navigation when a file is dropped outside the dropzone', () => {
  const { unmount } = renderUpload()
  const event = new Event('drop', { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'dataTransfer', { value: { types: ['Files'] } })
  document.body.dispatchEvent(event)
  expect(event.defaultPrevented).toBe(true)
  unmount()
  const after = new Event('drop', { bubbles: true, cancelable: true })
  Object.defineProperty(after, 'dataTransfer', { value: { types: ['Files'] } })
  document.body.dispatchEvent(after)
  expect(after.defaultPrevented).toBe(false)
})
