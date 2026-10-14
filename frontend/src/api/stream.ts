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

// The backend sends a heartbeat every 15 s; silence this long means a half-open connection.
export const STREAM_IDLE_TIMEOUT_MS = 45000

// Read framed events until EOF or cancellation; callers own the recovery policy.
export async function streamAnalysis(
  id: string,
  handlers: StreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  signal.throwIfAborted()
  const idle = new AbortController()
  const streamSignal = AbortSignal.any([signal, idle.signal])
  let idleTimer: ReturnType<typeof setTimeout> | undefined
  const armIdle = () => {
    clearTimeout(idleTimer)
    idleTimer = setTimeout(
      () => idle.abort(new DOMException('SSE stream idle', 'TimeoutError')),
      STREAM_IDLE_TIMEOUT_MS,
    )
  }

  try {
    armIdle()
    const token = await abortable(getToken, streamSignal)
    streamSignal.throwIfAborted()
    const correlationId = newCorrelationId()

    const res = await fetch(`${env.apiBaseUrl}/analysis/${id}/stream`, {
      method: 'GET',
      signal: streamSignal,
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
    await readFrames(res.body, handlers, streamSignal, armIdle)
  } finally {
    clearTimeout(idleTimer)
  }
}

async function readFrames(
  body: ReadableStream<BufferSource>,
  handlers: StreamHandlers,
  signal: AbortSignal,
  onData: () => void,
): Promise<void> {
  const reader = body.pipeThrough(new TextDecoderStream()).getReader()
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
      onData() // Any bytes, including heartbeat comments, prove the connection is alive.
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
