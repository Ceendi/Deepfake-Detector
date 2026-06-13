import { useRef, useState } from 'react'
import type { ChangeEvent, CSSProperties } from 'react'

import { Play, Pause, Volume2, VolumeX } from 'lucide-react'
import { clsx } from 'clsx'

import styles from './AudioPlayer.module.css'

interface AudioPlayerProps {
  src: string
  className?: string
  onDuration?: (seconds: number) => void // np. wiersz metadanych w Upload czyta długość stąd
  onError?: () => void
}

function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00'
  const total = Math.floor(seconds)
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

// Własny odtwarzacz audio (CSS Modules + tokeny) — bez natywnego <audio controls>, spójny z appką.
// Ukryty <audio> trzyma realny stan odtwarzania; całe UI sterujemy z Reacta.
export function AudioPlayer({ src, className, onDuration, onError }: AudioPlayerProps) {
  const audioRef = useRef<HTMLAudioElement>(null)
  const [playing, setPlaying] = useState(false)
  const [current, setCurrent] = useState(0)
  const [duration, setDuration] = useState(0)
  const [muted, setMuted] = useState(false)

  // Nowy src → reset UI podczas renderu (wzorzec „adjust state on prop change", bez efektu;
  // sam <audio> przeładuje się przez zmianę atrybutu src).
  const [prevSrc, setPrevSrc] = useState(src)
  if (src !== prevSrc) {
    setPrevSrc(src)
    setPlaying(false)
    setCurrent(0)
    setDuration(0)
  }

  const togglePlay = () => {
    const audio = audioRef.current
    if (!audio) return
    if (audio.paused) void audio.play()
    else audio.pause()
  }

  const handleSeek = (e: ChangeEvent<HTMLInputElement>) => {
    const audio = audioRef.current
    if (!audio) return
    const time = Number(e.target.value)
    audio.currentTime = time
    setCurrent(time)
  }

  const toggleMute = () => {
    const audio = audioRef.current
    if (!audio) return
    audio.muted = !audio.muted
    setMuted(audio.muted)
  }

  const pct = duration > 0 ? (current / duration) * 100 : 0

  return (
    <div className={clsx(styles.player, className)}>
      <audio
        ref={audioRef}
        src={src}
        preload="metadata"
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

      <button
        type="button"
        className={styles.playButton}
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
    </div>
  )
}
