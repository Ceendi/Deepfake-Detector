import { describe, it, expect } from 'vitest'
import { ApiError } from '@/api/errors'
import {
  ACCEPT_ATTR,
  getExt,
  isVideoFile,
  formatMB,
  formatDuration,
  validateFile,
  pickAnalysisType,
  pluralPl,
  validateDuration,
  isAbortError,
  messageForError,
} from './upload-utils'

// Helper: tworzy fejkowy File bez alokowania realnych bajtów. `size` nadpisujemy ręcznie,
// bo `new File([...], ...)` liczy rozmiar z treści, a nie chcemy alokować 500 MB w teście.
function makeFile(name: string, sizeBytes = 1024): File {
  const file = new File(['x'], name, { type: 'application/octet-stream' })
  Object.defineProperty(file, 'size', { value: sizeBytes })
  return file
}

const MB = 1024 * 1024

describe('getExt', () => {
  it('zwraca rozszerzenie małymi literami', () => {
    expect(getExt('clip.MP4')).toBe('mp4')
  })

  it('bierze segment po ostatniej kropce', () => {
    expect(getExt('archive.tar.gz')).toBe('gz')
  })

  it('zwraca pusty string gdy brak kropki', () => {
    expect(getExt('noextension')).toBe('')
  })
})

describe('isVideoFile', () => {
  it('rozpoznaje formaty wideo', () => {
    expect(isVideoFile(makeFile('a.mp4'))).toBe(true)
    expect(isVideoFile(makeFile('a.mov'))).toBe(true)
    expect(isVideoFile(makeFile('a.avi'))).toBe(true)
  })

  it('webm nie jest obsługiwany (backend go odrzuca)', () => {
    expect(isVideoFile(makeFile('a.webm'))).toBe(false)
  })

  it('audio nie jest wideo', () => {
    expect(isVideoFile(makeFile('a.wav'))).toBe(false)
    expect(isVideoFile(makeFile('a.mp3'))).toBe(false)
  })
})

describe('formatMB', () => {
  it('konwertuje bajty na MB z domyślnie 1 miejscem po przecinku', () => {
    expect(formatMB(1.5 * MB)).toBe('1.5')
  })

  it('respektuje parametr decimals', () => {
    expect(formatMB(600 * MB, 0)).toBe('600')
  })
})

describe('formatDuration', () => {
  it('formatuje jako mm:ss z zerami wiodącymi', () => {
    expect(formatDuration(0)).toBe('00:00')
    expect(formatDuration(61)).toBe('01:01')
    expect(formatDuration(599)).toBe('09:59')
  })

  it('zaokrągla ułamki sekund', () => {
    expect(formatDuration(61.6)).toBe('01:02')
  })

  it('nie dzieli na godziny — minuty rosną dalej', () => {
    expect(formatDuration(3600)).toBe('60:00')
  })
})

describe('ACCEPT_ATTR', () => {
  it('zawiera wszystkie dozwolone rozszerzenia', () => {
    expect(ACCEPT_ATTR).toBe('.mp4,.mov,.avi,.wav,.mp3,.flac')
  })
})

describe('validateFile', () => {
  it('przepuszcza poprawne wideo i audio (zwraca null)', () => {
    expect(validateFile(makeFile('clip.mp4', 10 * MB))).toBeNull()
    expect(validateFile(makeFile('sound.wav', 10 * MB))).toBeNull()
  })

  it('odrzuca nieobsługiwany format z rozszerzeniem w komunikacie', () => {
    const msg = validateFile(makeFile('doc.pdf'))
    expect(msg).toContain('Nieobsługiwany format')
    expect(msg).toContain('(.pdf)')
  })

  it('odrzuca plik bez rozszerzenia bez nawiasu w komunikacie', () => {
    const msg = validateFile(makeFile('noext'))
    expect(msg).toContain('Nieobsługiwany format')
    expect(msg).not.toContain('(')
  })

  it('odrzuca plik powyżej 500 MB', () => {
    const msg = validateFile(makeFile('big.mp4', 600 * MB))
    expect(msg).toContain('za duży')
    expect(msg).toContain('600 MB')
  })

  it('lista formatów jest zgodna z whitelistą backendu (AVI tak, WEBM nie)', () => {
    expect(validateFile(makeFile('clip.avi'))).toBeNull()
    const msg = validateFile(makeFile('clip.webm'))
    expect(msg).toContain('(.webm)')
    expect(msg).toContain('MP4, MOV, AVI, WAV, MP3, FLAC')
  })

  it('przepuszcza plik dokładnie na granicy 500 MB (limit jest ostry: >)', () => {
    expect(validateFile(makeFile('edge.mp4', 500 * MB))).toBeNull()
  })
})

describe('validateDuration', () => {
  it('odrzuca materiał dłuższy niż 30 min', () => {
    expect(validateDuration(1800.5)).toBe(
      'Materiał przekracza dopuszczalną długość (maks. 30 min).',
    )
  })

  it('przepuszcza dokładnie limit i długość nieznaną', () => {
    expect(validateDuration(1800)).toBeNull()
    expect(validateDuration(Number.NaN)).toBeNull()
    expect(validateDuration(Number.POSITIVE_INFINITY)).toBeNull()
  })
})

describe('pickAnalysisType', () => {
  it('wideo → FULL (oba tory)', () => {
    expect(pickAnalysisType('video/mp4')).toBe('FULL')
    expect(pickAnalysisType('video/x-msvideo')).toBe('FULL')
  })

  it('audio → AUDIO, także audio-only MP4', () => {
    expect(pickAnalysisType('audio/mpeg')).toBe('AUDIO')
    expect(pickAnalysisType('audio/mp4')).toBe('AUDIO')
  })
})

describe('pluralPl', () => {
  it.each([
    [1, 'analizę'],
    [2, 'analizy'],
    [4, 'analizy'],
    [5, 'analiz'],
    [12, 'analiz'],
    [14, 'analiz'],
    [22, 'analizy'],
    [25, 'analiz'],
  ])('%i → %s', (n, expected) => {
    expect(pluralPl(n, 'analizę', 'analizy', 'analiz')).toBe(expected)
  })
})

describe('isAbortError', () => {
  it('rozpoznaje AbortError', () => {
    expect(isAbortError(new DOMException('aborted', 'AbortError'))).toBe(true)
  })

  it('zwykły Error to nie abort', () => {
    expect(isAbortError(new Error('boom'))).toBe(false)
  })

  it('DOMException o innej nazwie to nie abort', () => {
    expect(isAbortError(new DOMException('nope', 'NotFoundError'))).toBe(false)
  })
})

describe('messageForError', () => {
  it.each([
    [1, '1 aktywną analizę'],
    [2, '2 aktywne analizy'],
    [3, '3 aktywne analizy'],
    [7, '7 aktywnych analiz'],
  ])('429 USER_LIMIT podaje limit %s z poprawną odmianą', (limit, phrase) => {
    const msg = messageForError(
      new ApiError({
        status: 429,
        message: 'x',
        backpressure: { queuePosition: 21, retryAfterSeconds: 5, reason: 'USER_LIMIT', limit },
      }),
    )
    expect(msg).toBe(`Masz już ${phrase} — poczekaj, aż któraś się zakończy.`)
  })

  it('429 GLOBAL_LIMIT zachowuje komunikat o kolejce', () => {
    const msg = messageForError(
      new ApiError({
        status: 429,
        message: 'x',
        backpressure: {
          queuePosition: 21,
          retryAfterSeconds: 5,
          reason: 'GLOBAL_LIMIT',
          limit: 20,
        },
      }),
    )
    expect(msg).toBe('Kolejka analiz jest pełna (pozycja 21). Spróbuj ponownie za 5 s.')
  })

  it('413 → komunikat o rozmiarze', () => {
    const msg = messageForError(new ApiError({ status: 413, message: 'x' }))
    expect(msg).toContain('za duży')
  })

  it('422 z nieznaną przyczyną → komunikat o formacie z listą zgodną z backendem', () => {
    const msg = messageForError(
      new ApiError({ status: 422, code: 'INVALID_FILE', message: 'unsupported media type: x' }),
    )
    expect(msg).toBe(
      'Nieobsługiwany lub uszkodzony plik. Dozwolone formaty: MP4, MOV, AVI, WAV, MP3, FLAC.',
    )
  })

  it('422 za długość → komunikat o długości, nie o formacie', () => {
    const msg = messageForError(
      new ApiError({
        status: 422,
        code: 'INVALID_FILE',
        message: 'media duration exceeds 1800 seconds',
      }),
    )
    expect(msg).toBe('Materiał przekracza dopuszczalną długość (maks. 30 min).')
  })

  it('422 z kodem w prefiksie → komunikat z mapy kodów (rozdzielczość)', () => {
    const msg = messageForError(
      new ApiError({
        status: 422,
        code: 'INVALID_FILE',
        message: '[MEDIA_RESOLUTION_TOO_HIGH] Rozdzielczość nagrania jest za wysoka (maks. 4K).',
      }),
    )
    expect(msg).toBe('Rozdzielczość nagrania jest za wysoka (maks. 4K).')
  })

  it('409 STORAGE_QUOTA_EXCEEDED przy uploadzie → komunikat o limicie miejsca', () => {
    const msg = messageForError(
      new ApiError({ status: 409, code: 'STORAGE_QUOTA_EXCEEDED', message: 'quota' }),
    )
    expect(msg).toBe(
      'Przekroczono limit miejsca na pliki (5 GB). Usuń stare analizy, aby wgrać nowe.',
    )
  })

  it('429 z backpressure → pozycja w kolejce i czas retry', () => {
    const msg = messageForError(
      new ApiError({
        status: 429,
        message: 'x',
        backpressure: { queuePosition: 7, retryAfterSeconds: 30 },
      }),
    )
    expect(msg).toContain('pozycja 7')
    expect(msg).toContain('30 s')
  })

  it('429 bez backpressure → ogólny rate-limit', () => {
    const msg = messageForError(new ApiError({ status: 429, message: 'x' }))
    expect(msg).toContain('Zbyt wiele żądań')
  })

  it('404 → nie znaleziono analizy', () => {
    expect(messageForError(new ApiError({ status: 404, message: 'x' }))).toContain(
      'Nie znaleziono analizy',
    )
  })

  it('401 → sesja wygasła', () => {
    expect(messageForError(new ApiError({ status: 401, message: 'x' }))).toContain('Sesja wygasła')
  })

  it.each([
    [400, 'Nieprawidłowe żądanie'],
    [409, 'Stan analizy właśnie się zmienił'],
    [503, 'Usługa jest chwilowo niedostępna'],
    [500, 'Coś poszło nie tak'],
  ])('%i → polski komunikat zamiast technicznej treści z backendu', (status, expected) => {
    const msg = messageForError(
      new ApiError({ status, message: 'Request method not supported: java.lang.Foo' }),
    )
    expect(msg).toContain(expected)
    expect(msg).not.toContain('java')
  })

  it('błąd sieci (status 0) → komunikat klienta', () => {
    const msg = messageForError(
      new ApiError({ status: 0, message: 'Błąd sieci podczas wysyłki pliku.' }),
    )
    expect(msg).toBe('Błąd sieci podczas wysyłki pliku.')
  })

  it('błąd spoza ApiError → fallback', () => {
    expect(messageForError(new Error('cokolwiek'))).toBe('Coś poszło nie tak. Spróbuj ponownie.')
    expect(messageForError('string error')).toBe('Coś poszło nie tak. Spróbuj ponownie.')
  })
})
