// Klient SSE dla GET /api/analysis/{id}/stream. Kontrakt: docs/contracts/rest-api.md (§Realtime).
// Ręczny parser na fetch + ReadableStream — native EventSource nie wysyła nagłówka Authorization,
// a token w query stringu wyciekłby do logów. Reconnect (D6) na razie pomijamy (TODO): analiza trwa
// krótko, a backend i tak woła heartbeat; przy zerwaniu user odświeży. Łatwo dołożyć retry-loop później.
import { env } from '@/config/env'
import { getToken } from '@/auth/keycloak'
import { newCorrelationId } from '@/utils/correlationId'
import { apiErrorFrom } from './client'
import type { AnalysisProgressEvent, AnalysisResultEvent } from './types'

export interface StreamHandlers {
  onProgress: (e: AnalysisProgressEvent) => void
  onResult: (e: AnalysisResultEvent) => void
}

// Otwiera stream i rozsyła zdarzenia aż do `result` (serwer zamyka połączenie) lub `signal.abort()`.
// Rzuca ApiError przy złym statusie otwarcia (404 = nie ma / cudzy — IDOR) oraz AbortError przy anulowaniu.
export async function streamAnalysis(
  id: string,
  handlers: StreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  const token = await getToken()
  const correlationId = newCorrelationId()

  const res = await fetch(`${env.apiBaseUrl}/analysis/${id}/stream`, {
    method: 'GET',
    signal,
    headers: {
      Accept: 'text/event-stream',
      'X-Correlation-Id': correlationId,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })

  if (!res.ok) {
    const body = await res.json().catch(() => null)
    throw apiErrorFrom(res.status, body, res.headers.get('X-Correlation-Id') ?? correlationId)
  }
  if (!res.body) throw new Error('Brak strumienia SSE w odpowiedzi.')

  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''

  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += value.replace(/\r/g, '') // normalizuj CRLF → LF, by ramki dzielić po \n\n
      let sep: number
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        dispatchFrame(buffer.slice(0, sep), handlers)
        buffer = buffer.slice(sep + 2)
      }
    }
  } finally {
    reader.releaseLock()
  }
}

// Parsuje pojedynczą ramkę SSE (linie event:/data:); komentarze (": heartbeat") i puste linie pomija.
function dispatchFrame(frame: string, handlers: StreamHandlers): void {
  let event = 'message'
  const dataLines: string[] = []

  for (const line of frame.split('\n')) {
    if (line === '' || line.startsWith(':')) continue
    const idx = line.indexOf(':')
    const field = idx === -1 ? line : line.slice(0, idx)
    let value = idx === -1 ? '' : line.slice(idx + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') event = value
    else if (field === 'data') dataLines.push(value)
  }

  if (dataLines.length === 0) return
  const data = dataLines.join('\n')
  try {
    if (event === 'progress') handlers.onProgress(JSON.parse(data) as AnalysisProgressEvent)
    else if (event === 'result') handlers.onResult(JSON.parse(data) as AnalysisResultEvent)
  } catch {
    // niepoprawny JSON w ramce — ignoruj (nie wywracaj całego streamu)
  }
}
