import { ApiError, UncertainStartError } from '@/api/errors'
import { MAX_MEDIA_DURATION_SECONDS, messageForCode, parseErrorMessage } from '@/api/error-codes'
import type { AnalysisType } from '@/api/types'

const MAX_SIZE_BYTES = 500 * 1024 * 1024 // 500 MB

// Per-user server defaults (docs/contracts/rest-api.md). The server stays authoritative: these only
// avoid needless 429s. Each active analysis holds one SSE stream, below the 4 allowed per user.
export const BATCH_LIMITS = { uploads: 2, analyses: 3 } as const

export const VIDEO_EXTS = ['mp4', 'mov', 'avi'] as const
export const AUDIO_EXTS = ['wav', 'mp3', 'flac'] as const
const ALL_EXTS = [...VIDEO_EXTS, ...AUDIO_EXTS]

const upper = (exts: readonly string[]) => exts.map((ext) => ext.toUpperCase())
export const VIDEO_FORMATS_LABEL = upper(VIDEO_EXTS).join(' · ')
export const AUDIO_FORMATS_LABEL = upper(AUDIO_EXTS).join(' · ')
export const ALL_FORMATS_LABEL = upper(ALL_EXTS).join(', ')

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

export function hasSupportedExt(name: string): boolean {
  return ALL_EXTS.includes(getExt(name) as (typeof ALL_EXTS)[number])
}

export function validateFile(file: File): string | null {
  const ext = getExt(file.name)
  if (!hasSupportedExt(file.name)) {
    return `Nieobsługiwany format pliku${ext ? ` (.${ext})` : ''}. Dozwolone: ${ALL_FORMATS_LABEL}.`
  }
  if (file.size > MAX_SIZE_BYTES) {
    return `Plik jest za duży (${formatMB(file.size, 0)} MB). Maksymalny rozmiar to 500 MB.`
  }
  return null
}

export const DURATION_TOO_LONG_MESSAGE = `Materiał przekracza dopuszczalną długość (maks. ${MAX_MEDIA_DURATION_SECONDS / 60} min).`

export function validateDuration(seconds: number): string | null {
  return Number.isFinite(seconds) && seconds > MAX_MEDIA_DURATION_SECONDS
    ? DURATION_TOO_LONG_MESSAGE
    : null
}

// Use the server-detected MIME because an extension can misidentify audio-only containers.
export function pickAnalysisType(detectedMimetype: string): AnalysisType {
  return detectedMimetype.toLowerCase().startsWith('audio/') ? 'AUDIO' : 'FULL'
}

export function pluralPl(n: number, one: string, few: string, many: string): string {
  if (n === 1) return one
  const lastDigit = n % 10
  const lastTwo = n % 100
  if (lastDigit >= 2 && lastDigit <= 4 && (lastTwo < 12 || lastTwo > 14)) return few
  return many
}

function activeAnalyses(n: number): string {
  return `${n} ${pluralPl(n, 'aktywną analizę', 'aktywne analizy', 'aktywnych analiz')}`
}

function invalidFileMessage(message: string): string {
  const { code, text } = parseErrorMessage(message)
  const known = messageForCode(code)
  if (known) return known
  if (/resolution/i.test(text)) return messageForCode('MEDIA_RESOLUTION_TOO_HIGH')!
  if (/duration exceeds/i.test(text)) return DURATION_TOO_LONG_MESSAGE
  if (/duration/i.test(text))
    return 'Nie udało się odczytać długości nagrania. Plik może być uszkodzony.'
  return `Nieobsługiwany lub uszkodzony plik. Dozwolone formaty: ${ALL_FORMATS_LABEL}.`
}

export function isAbortError(err: unknown): boolean {
  return err instanceof DOMException && err.name === 'AbortError'
}

export function messageForError(err: unknown, step?: 'start'): string {
  if (err instanceof UncertainStartError) return err.message
  if (err instanceof ApiError) {
    if (err.status === 413) return 'Plik jest za duży — maksymalny rozmiar to 500 MB.'
    if (err.status === 422) return messageForCode(err.code) ?? invalidFileMessage(err.message)
    if (err.status === 429) {
      if (err.code === 'UPLOAD_USER_LIMIT') return messageForCode(err.code)!
      if (err.backpressure) {
        if (err.backpressure.reason === 'USER_LIMIT' && err.backpressure.limit !== undefined) {
          return `Masz już ${activeAnalyses(err.backpressure.limit)} — poczekaj, aż któraś się zakończy.`
        }
        return `Kolejka analiz jest pełna (pozycja ${err.backpressure.queuePosition}). Spróbuj ponownie za ${err.backpressure.retryAfterSeconds} s.`
      }
      return 'Zbyt wiele żądań. Odczekaj chwilę i spróbuj ponownie.'
    }
    if (err.status === 404)
      return step === 'start'
        ? 'Nie znaleziono przesłanego pliku — wgraj go ponownie.'
        : 'Nie znaleziono analizy.'
    if (err.status === 401) return 'Sesja wygasła. Zaloguj się ponownie.'
    const known = messageForCode(err.code) ?? messageForCode(parseErrorMessage(err.message).code)
    if (known) return known

    if (err.status === 0) return err.message
    if (err.status === 400) return 'Nieprawidłowe żądanie. Odśwież stronę i spróbuj ponownie.'
    if (err.status === 409)
      return 'Stan analizy właśnie się zmienił. Odśwież stronę i spróbuj ponownie.'
    if (err.status === 503) return 'Usługa jest chwilowo niedostępna. Spróbuj ponownie za chwilę.'
    return 'Coś poszło nie tak. Spróbuj ponownie.'
  }
  return 'Coś poszło nie tak. Spróbuj ponownie.'
}
