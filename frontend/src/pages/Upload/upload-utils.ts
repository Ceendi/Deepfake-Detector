// Reguły walidacji + drobne formattery dla strony Upload.
// To tylko pierwsza linia obrony / UX — autorytetem pozostaje File Service
// (magic bytes → ffprobe → MIME whitelist). Tu chodzi o szybki feedback.
import { ApiError } from '@/api/errors'
import type { AnalysisType } from '@/api/types'

const MAX_SIZE_BYTES = 500 * 1024 * 1024 // 500 MB

const VIDEO_EXTS = ['mp4', 'mov', 'webm'] as const
const AUDIO_EXTS = ['wav', 'mp3', 'flac'] as const
const ALL_EXTS = [...VIDEO_EXTS, ...AUDIO_EXTS]

// `accept` na <input> — wygodny filtr w natywnym oknie wyboru (nie zastępuje walidacji).
export const ACCEPT_ATTR = ALL_EXTS.map((ext) => `.${ext}`).join(',')

export function getExt(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot === -1 ? '' : name.slice(dot + 1).toLowerCase()
}

export function isVideoFile(file: File): boolean {
  return VIDEO_EXTS.includes(getExt(file.name) as (typeof VIDEO_EXTS)[number])
}

export function formatMB(bytes: number, decimals = 1): string {
  return (bytes / (1024 * 1024)).toFixed(decimals)
}

export function formatDuration(seconds: number): string {
  const total = Math.round(seconds)
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/** Zwraca komunikat błędu albo `null` gdy plik przechodzi walidację. */
export function validateFile(file: File): string | null {
  const ext = getExt(file.name)
  if (!ALL_EXTS.includes(ext as (typeof ALL_EXTS)[number])) {
    return `Nieobsługiwany format pliku${ext ? ` (.${ext})` : ''}. Dozwolone: ${ALL_EXTS.map((e) => e.toUpperCase()).join(', ')}.`
  }
  if (file.size > MAX_SIZE_BYTES) {
    return `Plik jest za duży (${formatMB(file.size, 0)} MB). Maksymalny rozmiar to 500 MB.`
  }
  return null
}

// Wideo → FULL (oba tory: obraz + dźwięk), audio → AUDIO. TODO(backend, Osoba 1): potwierdzić, czy
// „oba detektory dla wideo" to FULL czy już VIDEO (kontrakt pokazuje VIDEO z audioProb: null).
export function pickAnalysisType(file: File): AnalysisType {
  return isVideoFile(file) ? 'FULL' : 'AUDIO'
}

// Anulowanie (abort XHR / fetch) leci jako AbortError — UI traktuje je cicho, nie jako błąd.
export function isAbortError(err: unknown): boolean {
  return err instanceof DOMException && err.name === 'AbortError'
}

// Mapuje błąd (ApiError / inne) na komunikat dla usera. Patrz docs/contracts/rest-api.md §HTTP codes.
export function messageForError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 413) return 'Plik jest za duży — maksymalny rozmiar to 500 MB.'
    if (err.status === 422)
      return 'Nieobsługiwany format pliku. Dozwolone: MP4, MOV, WEBM, WAV, MP3, FLAC.'
    if (err.status === 429) {
      if (err.backpressure) {
        return `Kolejka analiz jest pełna (pozycja ${err.backpressure.queuePosition}). Spróbuj ponownie za ${err.backpressure.retryAfterSeconds} s.`
      }
      return 'Zbyt wiele żądań. Odczekaj chwilę i spróbuj ponownie.'
    }
    if (err.status === 404) return 'Nie znaleziono analizy.'
    if (err.status === 401) return 'Sesja wygasła. Zaloguj się ponownie.'
    return err.message
  }
  return 'Coś poszło nie tak. Spróbuj ponownie.'
}
