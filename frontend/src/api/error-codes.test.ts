import { describe, expect, it } from 'vitest'

import {
  analysisFailureMessage,
  ERROR_CODE_MESSAGES,
  GENERIC_FAILURE_MESSAGE,
  parseErrorMessage,
  messageForCode,
} from './error-codes'

describe('parseErrorMessage', () => {
  it('zdejmuje prefiks [KOD] i zwraca kod osobno', () => {
    expect(parseErrorMessage('[NO_FACE_DETECTED] No face found in 32 frames')).toEqual({
      code: 'NO_FACE_DETECTED',
      text: 'No face found in 32 frames',
    })
  })

  it('bez prefiksu zwraca sam tekst', () => {
    expect(parseErrorMessage('Detector crashed')).toEqual({ text: 'Detector crashed' })
    expect(parseErrorMessage(null)).toEqual({ text: '' })
  })
})

describe('analysisFailureMessage', () => {
  it.each([
    ['NO_FACE_DETECTED', 'Nie wykryto twarzy w nagraniu. Wgraj materiał, na którym widać twarz.'],
    ['VIDEO_DECODE_FAILED', 'Nie udało się odczytać obrazu z pliku. Plik może być uszkodzony.'],
    ['MEDIA_RESOLUTION_TOO_HIGH', 'Rozdzielczość nagrania jest za wysoka (maks. 4K).'],
    ['MODEL_UNAVAILABLE', 'Usługa analizy jest chwilowo niedostępna. Spróbuj ponownie później.'],
    ['PROCESSING_ERROR', 'Nie udało się przetworzyć materiału. Spróbuj ponownie.'],
    ['ANALYSIS_TIMEOUT', 'Analiza trwała zbyt długo i została przerwana.'],
    ['DEAD_LETTERED', 'Nie udało się zakończyć analizy.'],
    [
      'STORAGE_QUOTA_EXCEEDED',
      'Przekroczono limit miejsca na pliki (5 GB). Usuń stare analizy – miejsce zwalnia się do 72 h po usunięciu.',
    ],
  ])('%s → uzgodniony komunikat', (code, message) => {
    expect(analysisFailureMessage(`[${code}] technical detail`)).toBe(message)
  })

  it('MODEL_UNAVAILABLE nie obwinia pliku', () => {
    expect(ERROR_CODE_MESSAGES.MODEL_UNAVAILABLE).not.toMatch(/plik/i)
  })

  it('nieznany kod lub brak kodu → neutralny komunikat bez surowej treści', () => {
    expect(analysisFailureMessage('[SOMETHING_NEW] java.lang.IllegalStateException')).toBe(
      GENERIC_FAILURE_MESSAGE,
    )
    expect(analysisFailureMessage('Stuck job timeout')).toBe(GENERIC_FAILURE_MESSAGE)
    expect(analysisFailureMessage(null)).toBe(GENERIC_FAILURE_MESSAGE)
  })
})

it('maps the stable unsupported-media code independently of validator wording', () => {
  expect(messageForCode('UNSUPPORTED_MEDIA')).toBe(
    'Nieobsługiwany lub uszkodzony plik. Dozwolone formaty: MP4, MOV, AVI, WAV, MP3, FLAC.',
  )
})
