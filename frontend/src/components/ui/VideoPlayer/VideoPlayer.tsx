import { useEffect, useRef, useState } from 'react'
import type { ChangeEvent, CSSProperties } from 'react'

import { Play, Pause, Volume2, VolumeX, Maximize, Minimize } from 'lucide-react'
import { clsx } from 'clsx'

import styles from './VideoPlayer.module.css'

interface VideoPlayerProps {
  src: string
  className?: string
  onDuration?: (seconds: number) => void
  onError?: () => void
}

function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00'
  const total = Math.floor(seconds)
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

// Własny odtwarzacz wideo (CSS Modules + tokeny) — bez natywnego <video controls>. Kontrolki na
// overlayu (play/pauza, czas, przewijanie, wyciszenie, pełny ekran), chowane podczas odtwarzania.
export function VideoPlayer({ src, className, onDuration, onError }: VideoPlayerProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const videoRef = useRef<HTMLVideoElement>(null)
  const [playing, setPlaying] = useState(false)
  const [current, setCurrent] = useState(0)
  const [duration, setDuration] = useState(0)
  const [muted, setMuted] = useState(false)
  const [hovered, setHovered] = useState(false)
  const [fullscreen, setFullscreen] = useState(false)

  // Nowy src → reset UI podczas renderu (wzorzec „adjust state on prop change", bez efektu).
  const [prevSrc, setPrevSrc] = useState(src)
  if (src !== prevSrc) {
    setPrevSrc(src)
    setPlaying(false)
    setCurrent(0)
    setDuration(0)
  }

  // Synchronizacja ikony pełnego ekranu ze stanem przeglądarki (Esc, natywne przyciski).
  useEffect(() => {
    const onFsChange = () => setFullscreen(document.fullscreenElement === containerRef.current)
    document.addEventListener('fullscreenchange', onFsChange)
    return () => document.removeEventListener('fullscreenchange', onFsChange)
  }, [])

  const togglePlay = () => {
    const video = videoRef.current
    if (!video) return
    if (video.paused) void video.play()
    else video.pause()
  }

  const handleSeek = (e: ChangeEvent<HTMLInputElement>) => {
    const video = videoRef.current
    if (!video) return
    const time = Number(e.target.value)
    video.currentTime = time
    setCurrent(time)
  }

  const toggleMute = () => {
    const video = videoRef.current
    if (!video) return
    video.muted = !video.muted
    setMuted(video.muted)
  }

  const toggleFullscreen = () => {
    if (document.fullscreenElement) void document.exitFullscreen()
    else void containerRef.current?.requestFullscreen()
  }

  const pct = duration > 0 ? (current / duration) * 100 : 0
  const showControls = !playing || hovered

  return (
    <div
      ref={containerRef}
      className={clsx(styles.player, className)}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <video
        ref={videoRef}
        className={styles.video}
        src={src}
        preload="metadata"
        onClick={togglePlay}
        onLoadedMetadata={(e) => {
          const d = e.currentTarget.duration
          setDuration(d)
          onDuration?.(d)
        }}
        onTimeUpdate={(e) => setCurrent(e.currentTarget.currentTime)}
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => setPlaying(false)}
        onError={onError}
      />

      {!playing && (
        <button
          type="button"
          className={styles.centerPlay}
          onClick={togglePlay}
          aria-label="Odtwórz"
        >
          <Play size={28} strokeWidth={2.2} aria-hidden="true" />
        </button>
      )}

      <div className={clsx(styles.controls, !showControls && styles.controlsHidden)}>
        <button
          type="button"
          className={styles.iconButton}
          onClick={togglePlay}
          aria-label={playing ? 'Pauza' : 'Odtwórz'}
        >
          {playing ? (
            <Pause size={18} strokeWidth={2.2} aria-hidden="true" />
          ) : (
            <Play size={18} strokeWidth={2.2} aria-hidden="true" />
          )}
        </button>

        <span className={styles.time}>{formatTime(current)}</span>

        <input
          type="range"
          className={styles.seek}
          min={0}
          max={duration || 0}
          step="any"
          value={current}
          onChange={handleSeek}
          style={{ '--pct': `${pct}%` } as CSSProperties}
          aria-label="Pozycja odtwarzania"
        />

        <span className={clsx(styles.time, styles.timeTotal)}>{formatTime(duration)}</span>

        <button
          type="button"
          className={styles.iconButton}
          onClick={toggleMute}
          aria-label={muted ? 'Włącz dźwięk' : 'Wycisz'}
        >
          {muted ? (
            <VolumeX size={18} strokeWidth={2.2} aria-hidden="true" />
          ) : (
            <Volume2 size={18} strokeWidth={2.2} aria-hidden="true" />
          )}
        </button>

        <button
          type="button"
          className={styles.iconButton}
          onClick={toggleFullscreen}
          aria-label={fullscreen ? 'Wyjdź z pełnego ekranu' : 'Pełny ekran'}
        >
          {fullscreen ? (
            <Minimize size={18} strokeWidth={2.2} aria-hidden="true" />
          ) : (
            <Maximize size={18} strokeWidth={2.2} aria-hidden="true" />
          )}
        </button>
      </div>
    </div>
  )
}
