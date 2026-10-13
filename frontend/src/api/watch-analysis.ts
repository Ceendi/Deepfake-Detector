import { abortable } from './abort'
import { getAnalysis } from './analysis'
import { ApiError } from './errors'
import { streamAnalysis, type StreamHandlers } from './stream'
import type { Analysis } from './types'

// Nine reads span 91.5 seconds, exceeding the backend's 60-second GET cache TTL.
const RECOVERY_DELAYS = [0, 500, 1000, 2000, 4000, 8000, 16000, 30000, 30000]
const READ_TIMEOUT_MS = 10000
// Stuck recovery fails any analysis older than this, so watching longer is pointless.
export const MAX_ANALYSIS_AGE_MS = 7200 * 1000
// Backoff between SSE reopens once bounded recovery saw a still-active analysis.
const REOPEN_DELAYS = [5000, 10000, 20000, 30000]
// A stream that stayed open this long was healthy; reopen it without growing the backoff.
const HEALTHY_STREAM_MS = 60000

/** Bounded recovery ended while the analysis is still legitimately running. */
export class AnalysisStillRunningError extends Error {
  constructor() {
    super('Analiza nadal trwa. Wynik pojawi się na stronie analizy.')
    this.name = 'AnalysisStillRunningError'
  }
}

export interface WatchOptions {
  /** Server creation time; bounds how long an active analysis is watched. */
  createdAt?: string
  /** Called once when bounded recovery ends and long watching starts. */
  onStillRunning?: () => void
}

function retryable(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status === 429 || error.status >= 500
}

function isActive(analysis: Analysis): boolean {
  return analysis.status === 'PENDING' || analysis.status === 'PROCESSING'
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

// One whole GET including token acquisition, bounded by its own timeout.
async function readOnce(id: string, signal: AbortSignal): Promise<Analysis> {
  const timeoutController = new AbortController()
  const timer = setTimeout(
    () => timeoutController.abort(new DOMException('Read timed out', 'TimeoutError')),
    READ_TIMEOUT_MS,
  )
  const readSignal = AbortSignal.any([signal, timeoutController.signal])
  try {
    return await abortable(() => getAnalysis(id, readSignal), readSignal)
  } finally {
    clearTimeout(timer)
  }
}

/** Poll until terminal, never treating one cached active response as successful catch-up. */
export async function recoverAnalysis(id: string, signal: AbortSignal): Promise<Analysis> {
  for (const ms of RECOVERY_DELAYS) {
    if (ms) await delay(ms, signal)
    signal.throwIfAborted()
    try {
      const analysis = await readOnce(id, signal)
      signal.throwIfAborted()
      if (!isActive(analysis)) return analysis
    } catch (error) {
      signal.throwIfAborted()
      if (!retryable(error)) throw error
    }
  }
  throw new AnalysisStillRunningError()
}

// Open SSE once; resolves whether a result event arrived. Non-retryable rejections propagate.
async function streamOnce(
  id: string,
  handlers: Pick<StreamHandlers, 'onProgress'>,
  signal: AbortSignal,
): Promise<boolean> {
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
  return receivedResult
}

function deadlineFor(createdAt: string | undefined): number {
  const created = createdAt ? Date.parse(createdAt) : NaN
  return (Number.isFinite(created) ? created : Date.now()) + MAX_ANALYSIS_AGE_MS
}

/**
 * Shared by upload and result: SSE first, then bounded catch-up on result, EOF or failure.
 * While the analysis stays active, reopen SSE with backoff until it is terminal or older
 * than the backend's absolute limit; opening SSE replays the terminal result from the database.
 */
export async function watchAnalysis(
  id: string,
  handlers: Omit<StreamHandlers, 'onResult'> & { onResult: (analysis: Analysis) => void },
  signal: AbortSignal,
  options: WatchOptions = {},
): Promise<void> {
  signal.throwIfAborted()
  await streamOnce(id, handlers, signal)
  try {
    const analysis = await recoverAnalysis(id, signal)
    signal.throwIfAborted()
    handlers.onResult(analysis)
    return
  } catch (error) {
    signal.throwIfAborted()
    if (!(error instanceof AnalysisStillRunningError)) throw error
  }

  const deadline = deadlineFor(options.createdAt)
  if (Date.now() >= deadline) throw new AnalysisStillRunningError()
  options.onStillRunning?.()

  let attempt = 0
  for (;;) {
    const wait = Math.min(
      REOPEN_DELAYS[Math.min(attempt, REOPEN_DELAYS.length - 1)],
      deadline - Date.now(),
    )
    if (wait > 0) await delay(wait, signal)
    attempt += 1

    const openedAt = Date.now()
    const receivedResult = await streamOnce(id, handlers, signal)
    if (Date.now() - openedAt >= HEALTHY_STREAM_MS) attempt = 0

    if (receivedResult) {
      // Same stale-cache-safe catch-up as the first result.
      try {
        const analysis = await recoverAnalysis(id, signal)
        signal.throwIfAborted()
        handlers.onResult(analysis)
        return
      } catch (error) {
        signal.throwIfAborted()
        if (!(error instanceof AnalysisStillRunningError)) throw error
      }
    } else {
      // SSE may be unavailable (quota, proxy); one read keeps progress towards a result.
      try {
        const analysis = await readOnce(id, signal)
        signal.throwIfAborted()
        if (!isActive(analysis)) {
          handlers.onResult(analysis)
          return
        }
      } catch (error) {
        signal.throwIfAborted()
        if (!retryable(error)) throw error
      }
    }

    if (Date.now() >= deadline) throw new AnalysisStillRunningError()
  }
}
