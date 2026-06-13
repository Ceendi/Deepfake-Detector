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
    expect(isVideoFile(makeFile('a.webm'))).toBe(true)
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
    expect(ACCEPT_ATTR).toBe('.mp4,.mov,.webm,.wav,.mp3,.flac')
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

  it('przepuszcza plik dokładnie na granicy 500 MB (limit jest ostry: >)', () => {
    expect(validateFile(makeFile('edge.mp4', 500 * MB))).toBeNull()
  })
})

describe('pickAnalysisType', () => {
  it('wideo → FULL (oba tory)', () => {
    expect(pickAnalysisType(makeFile('clip.mp4'))).toBe('FULL')
  })

  it('audio → AUDIO', () => {
    expect(pickAnalysisType(makeFile('sound.mp3'))).toBe('AUDIO')
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
  it('413 → komunikat o rozmiarze', () => {
    const msg = messageForError(new ApiError({ status: 413, message: 'x' }))
    expect(msg).toContain('za duży')
  })

  it('422 → komunikat o formacie', () => {
    const msg = messageForError(new ApiError({ status: 422, message: 'x' }))
    expect(msg).toContain('Nieobsługiwany format')
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

  it('inny status ApiError → przepuszcza message z błędu', () => {
    const msg = messageForError(new ApiError({ status: 500, message: 'Serwer padł' }))
    expect(msg).toBe('Serwer padł')
  })

  it('błąd spoza ApiError → fallback', () => {
    expect(messageForError(new Error('cokolwiek'))).toBe('Coś poszło nie tak. Spróbuj ponownie.')
    expect(messageForError('string error')).toBe('Coś poszło nie tak. Spróbuj ponownie.')
  })
})
