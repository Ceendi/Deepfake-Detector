import { abortable } from './abort'
import { getAnalysis } from './analysis'
import { ApiError } from './errors'
import { streamAnalysis, type StreamHandlers } from './stream'
import type { Analysis } from './types'

// Nine reads span 91.5 seconds, exceeding the backend's 60-second GET cache TTL.
const RECOVERY_DELAYS = [0, 500, 1000, 2000, 4000, 8000, 16000, 30000, 30000]
const READ_TIMEOUT_MS = 10000

function retryable(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status === 429 || error.status >= 500
}

function delay(ms: number, signal: AbortSignal): Promise<void> {
  signal.throwIfAborted()
  return new Promise((resolve, reject) => {
    const abort = () => {
      clearTimeout(timer)
      reject(signal.reason)
    }
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', abort)
      resolve()
    }, ms)
    signal.addEventListener('abort', abort, { once: true })
  })
}

/** Poll until terminal, never treating one cached active response as successful catch-up. */
export async function recoverAnalysis(id: string, signal: AbortSignal): Promise<Analysis> {
  for (const ms of RECOVERY_DELAYS) {
    if (ms) await delay(ms, signal)
    signal.throwIfAborted()
    const timeoutController = new AbortController()
    const timer = setTimeout(
      () => timeoutController.abort(new DOMException('Read timed out', 'TimeoutError')),
      READ_TIMEOUT_MS,
    )
    const readSignal = AbortSignal.any([signal, timeoutController.signal])
    try {
      const analysis = await abortable(() => getAnalysis(id, readSignal), readSignal)
      signal.throwIfAborted()
      if (analysis.status !== 'PENDING' && analysis.status !== 'PROCESSING') return analysis
    } catch (error) {
      signal.throwIfAborted()
      if (!retryable(error)) throw error
    } finally {
      clearTimeout(timer)
    }
  }
  throw new Error('Nie udało się odzyskać wyniku analizy. Odśwież stronę, aby spróbować ponownie.')
}

/** Shared by upload and result: SSE first, then bounded catch-up on result, EOF or failure. */
export async function watchAnalysis(
  id: string,
  handlers: Omit<StreamHandlers, 'onResult'> & { onResult: (analysis: Analysis) => void },
  signal: AbortSignal,
): Promise<void> {
  signal.throwIfAborted()
  const streamController = new AbortController()
  const streamSignal = AbortSignal.any([signal, streamController.signal])
  let receivedResult = false
  try {
    await abortable(
      () =>
        streamAnalysis(
          id,
          {
            onProgress: (event) => {
              if (!signal.aborted) handlers.onProgress(event)
            },
            onResult: () => {
              receivedResult = true
              streamController.abort()
            },
          },
          streamSignal,
        ),
      streamSignal,
    )
  } catch (error) {
    signal.throwIfAborted()
    if (!receivedResult && !retryable(error)) throw error
  }
  signal.throwIfAborted()
  const analysis = await recoverAnalysis(id, signal)
  signal.throwIfAborted()
  handlers.onResult(analysis)
}
