import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/files', () => ({ presignFile: vi.fn() }))

import { presignFile } from '@/api/files'
import { MediaPreview } from './MediaPreview'

beforeEach(() => vi.clearAllMocks())

describe('MediaPreview', () => {
  it('plays a video file from its presigned URL', async () => {
    vi.mocked(presignFile).mockResolvedValue({ url: 'https://s3.test/clip.mp4' } as never)
    const { container } = render(<MediaPreview fileId="f1" type="FULL" />)
    await screen.findByLabelText('Pozycja odtwarzania')
    expect(container.querySelector('video')).toHaveAttribute('src', 'https://s3.test/clip.mp4')
    expect(presignFile).toHaveBeenCalledWith('f1', expect.any(AbortSignal))
  })

  it('uses the audio player for audio and reports a media error', async () => {
    vi.mocked(presignFile).mockResolvedValue({ url: 'https://s3.test/voice.wav' } as never)
    const { container } = render(<MediaPreview fileId="f2" type="AUDIO" />)
    await screen.findByLabelText('Pozycja odtwarzania')
    const audio = container.querySelector('audio') as HTMLAudioElement
    fireEvent.error(audio)
    expect(screen.getByText('Nie udało się wczytać podglądu pliku.')).toBeInTheDocument()
  })

  it('shows an error when the presign request fails', async () => {
    vi.mocked(presignFile).mockRejectedValue(new Error('404'))
    render(<MediaPreview fileId="f3" type="VIDEO" />)
    expect(await screen.findByText('Nie udało się wczytać podglądu pliku.')).toBeInTheDocument()
  })

  it('stays quiet when unmounted before the URL arrives', async () => {
    vi.mocked(presignFile).mockImplementation(
      (_id: string, signal?: AbortSignal) =>
        new Promise((_, reject) => signal?.addEventListener('abort', () => reject(new Error('x')))),
    )
    const { unmount } = render(<MediaPreview fileId="f4" type="VIDEO" />)
    unmount()
    await Promise.resolve()
  })
})
