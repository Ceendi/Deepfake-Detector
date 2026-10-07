// Authenticated SSE transport. Recovery is shared by both screens in watch-analysis.ts.
import { abortable } from './abort'
import { env } from '@/config/env'
import { getToken } from '@/auth/keycloak'
import { newCorrelationId } from '@/utils/correlationId'
import { apiErrorFrom } from './client'
import type { AnalysisProgressEvent, AnalysisResultEvent } from './types'

export interface StreamHandlers {
  onProgress: (e: AnalysisProgressEvent) => void
  onResult: (e: AnalysisResultEvent) => void
}

// Read framed events until EOF or cancellation; callers own the recovery policy.
export async function streamAnalysis(
  id: string,
  handlers: StreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  signal.throwIfAborted()
  const token = await abortable(getToken, signal)
  signal.throwIfAborted()
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
  const abortReader = () => {
    void reader.cancel().catch(() => {})
  }
  signal.addEventListener('abort', abortReader, { once: true })
  let buffer = ''

  try {
    for (;;) {
      signal.throwIfAborted()
      const { value, done } = await reader.read()
      signal.throwIfAborted()
      if (done) break
      buffer += value.replace(/\r/g, '') // Normalize CRLF frame separators.
      let sep: number
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        signal.throwIfAborted()
        dispatchFrame(buffer.slice(0, sep), handlers)
        buffer = buffer.slice(sep + 2)
      }
    }
  } finally {
    signal.removeEventListener('abort', abortReader)
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

// Parse event/data fields; ignore comments and empty frames.
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
    // Ignore malformed JSON frames.
  }
}
