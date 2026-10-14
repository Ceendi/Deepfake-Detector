// Kody błędów backendu → komunikaty dla użytkownika (PL, neutralne). Jedno źródło dla ekranu Upload
// i strony wyniku. Backend zapisuje błąd analizy jako `errorMessage = "[KOD] komunikat"`, a błędy HTTP
// niosą kod w polu `code` (ErrorResponse). Szczegóły techniczne zostają w logach serwera.

export const MAX_MEDIA_DURATION_SECONDS = 1800 // MEDIA_MAX_DURATION_SECONDS w file-service i detektorach

export const ERROR_CODE_MESSAGES: Readonly<Record<string, string>> = {
  NO_FACE_DETECTED: 'Nie wykryto twarzy w nagraniu. Wgraj materiał, na którym widać twarz.',
  VIDEO_DECODE_FAILED: 'Nie udało się odczytać obrazu z pliku. Plik może być uszkodzony.',
  MEDIA_RESOLUTION_TOO_HIGH: 'Rozdzielczość nagrania jest za wysoka (maks. 4K).',
  MEDIA_TOO_LONG:
    'Materiał przekracza dopuszczalną długość (maks. 30 min). Wgraj krótsze nagranie.',
  MODEL_UNAVAILABLE: 'Usługa analizy jest chwilowo niedostępna. Spróbuj ponownie później.',
  PROCESSING_ERROR: 'Nie udało się przetworzyć materiału. Spróbuj ponownie.',
  ANALYSIS_TIMEOUT: 'Analiza trwała zbyt długo i została przerwana.',
  DEAD_LETTERED: 'Nie udało się zakończyć analizy.',
  STORAGE_QUOTA_EXCEEDED:
    'Przekroczono limit miejsca na pliki (5 GB). Usuń stare analizy – miejsce zwalnia się do 72 h po usunięciu.',
  NO_SPEECH:
    'Brak wystarczająco wyraźnego sygnału audio do analizy. Wgraj nagranie z wyraźnie słyszalną mową.',
  NO_AUDIO_TRACK: 'Materiał nie zawiera ścieżki audio. Wgraj nagranie z dźwiękiem.',
}

// Nieznany kod nie może sugerować winy pliku — błąd równie dobrze może leżeć po stronie serwera.
export const GENERIC_FAILURE_MESSAGE =
  'Nie udało się dokończyć przetwarzania tego materiału. Spróbuj ponownie lub wgraj inny plik.'

const CODE_PREFIX = /^\s*\[([A-Z][A-Z0-9_]*)\]\s*/

export interface ParsedError {
  code?: string
  text: string // komunikat bez prefiksu `[KOD]`
}

// `"[KOD] komunikat"` → `{ code, text }`. Brak prefiksu → sam tekst.
export function parseErrorMessage(message: string | null | undefined): ParsedError {
  const raw = message ?? ''
  const match = raw.match(CODE_PREFIX)
  if (!match) return { text: raw.trim() }
  return { code: match[1], text: raw.slice(match[0].length).trim() }
}

export function messageForCode(code: string | null | undefined): string | undefined {
  return code ? ERROR_CODE_MESSAGES[code] : undefined
}

// Komunikat dla analizy FAILED (errorMessage z backendu).
export function analysisFailureMessage(errorMessage: string | null | undefined): string {
  return messageForCode(parseErrorMessage(errorMessage).code) ?? GENERIC_FAILURE_MESSAGE
}
