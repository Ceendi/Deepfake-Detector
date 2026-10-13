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

import { uploadFileWithProgress } from '@/api/files'

beforeEach(() => {
  vi.resetAllMocks()
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
