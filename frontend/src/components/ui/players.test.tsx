import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { AudioPlayer } from './AudioPlayer/AudioPlayer'
import { VideoPlayer } from './VideoPlayer/VideoPlayer'

// jsdom has no media playback; drive the element the way a browser would.
function fakeMedia(media: HTMLMediaElement, duration: number) {
  let paused = true
  Object.defineProperty(media, 'paused', { get: () => paused, configurable: true })
  Object.defineProperty(media, 'duration', { value: duration, configurable: true })
  media.play = vi.fn(() => {
    paused = false
    fireEvent.play(media)
    return Promise.resolve()
  })
  media.pause = vi.fn(() => {
    paused = true
    fireEvent.pause(media)
  })
}

afterEach(() => vi.restoreAllMocks())

describe.each([
  ['AudioPlayer', AudioPlayer, 'audio'],
  ['VideoPlayer', VideoPlayer, 'video'],
] as const)('%s', (_, Player, tag) => {
  it('plays, pauses, seeks, mutes and reports duration and errors', () => {
    const onDuration = vi.fn()
    const onError = vi.fn()
    const { container } = render(
      <Player src="/a.media" onDuration={onDuration} onError={onError} />,
    )
    const media = container.querySelector(tag) as HTMLMediaElement
    fakeMedia(media, 125)

    fireEvent.loadedMetadata(media)
    expect(onDuration).toHaveBeenCalledWith(125)
    expect(screen.getByText('2:05')).toBeInTheDocument()

    const playButtons = screen.getAllByRole('button', { name: 'Odtwórz' })
    fireEvent.click(playButtons[playButtons.length - 1])
    expect(media.play).toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Pauza' }))
    expect(media.pause).toHaveBeenCalled()

    fireEvent.change(screen.getByLabelText('Pozycja odtwarzania'), { target: { value: '61' } })
    expect(media.currentTime).toBe(61)
    expect(screen.getByText('1:01')).toBeInTheDocument()

    media.currentTime = 10
    fireEvent.timeUpdate(media)
    expect(screen.getByText('0:10')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Wycisz' }))
    expect(media.muted).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Włącz dźwięk' }))
    expect(media.muted).toBe(false)

    fireEvent.play(media)
    fireEvent.ended(media)
    expect(screen.getAllByRole('button', { name: 'Odtwórz' }).length).toBeGreaterThan(0)

    fireEvent.error(media)
    expect(onError).toHaveBeenCalled()
  })

  it('resets the position when the source changes and guards invalid durations', () => {
    const { container, rerender } = render(<Player src="/a.media" />)
    const media = container.querySelector(tag) as HTMLMediaElement
    fakeMedia(media, Number.NaN)
    fireEvent.loadedMetadata(media)
    media.currentTime = 5
    fireEvent.timeUpdate(media)
    expect(screen.getByText('0:05')).toBeInTheDocument()

    rerender(<Player src="/b.media" />)
    expect(screen.getAllByText('0:00')).toHaveLength(2)
  })
})

describe('VideoPlayer extras', () => {
  it('toggles play from the video surface and hides controls only while playing without hover', () => {
    const { container } = render(<VideoPlayer src="/v.mp4" />)
    const video = container.querySelector('video') as HTMLVideoElement
    fakeMedia(video, 10)
    const player = container.firstElementChild as HTMLElement

    fireEvent.click(video)
    expect(video.play).toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Odtwórz' })).not.toBeInTheDocument()
    const controls = screen.getByRole('button', { name: 'Pauza' }).parentElement as HTMLElement
    const hiddenClass = controls.className
    fireEvent.mouseEnter(player)
    expect(controls.className).not.toBe(hiddenClass)
    fireEvent.mouseLeave(player)
    expect(controls.className).toBe(hiddenClass)
  })

  it('enters and leaves fullscreen', () => {
    const { container } = render(<VideoPlayer src="/v.mp4" />)
    const player = container.firstElementChild as HTMLElement
    let fsElement: Element | null = null
    Object.defineProperty(document, 'fullscreenElement', {
      get: () => fsElement,
      configurable: true,
    })
    player.requestFullscreen = vi.fn(() => {
      fsElement = player
      document.dispatchEvent(new Event('fullscreenchange'))
      return Promise.resolve()
    })
    document.exitFullscreen = vi.fn(() => {
      fsElement = null
      document.dispatchEvent(new Event('fullscreenchange'))
      return Promise.resolve()
    })

    act(() => screen.getByRole('button', { name: 'Pełny ekran' }).click())
    expect(player.requestFullscreen).toHaveBeenCalled()
    act(() => screen.getByRole('button', { name: 'Wyjdź z pełnego ekranu' }).click())
    expect(document.exitFullscreen).toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Pełny ekran' })).toBeInTheDocument()
  })
})
