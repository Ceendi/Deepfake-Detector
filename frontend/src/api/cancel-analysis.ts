import { abortable } from './abort'
import { cancelAnalysis, getAnalysis } from './analysis'
import { ApiError } from './errors'
import type { Analysis } from './types'

const CANCEL_TIMEOUT_MS = 10000

/**
 * Cancel and resolve to the terminal resource. 200 and 409 are both settled outcomes: 409 means
 * the analysis finished first, so its real status is read back. Resolves `null` when that read
 * fails or still shows it active (the open stream delivers the result). Other errors propagate,
 * including a timeout, so the caller never spins forever.
 */
export async function cancelAndSettle(id: string, signal?: AbortSignal): Promise<Analysis | null> {
  const timeout = new AbortController()
  const timer = setTimeout(
    () => timeout.abort(new DOMException('Cancel timed out', 'TimeoutError')),
    CANCEL_TIMEOUT_MS,
  )
  const requestSignal = signal ? AbortSignal.any([signal, timeout.signal]) : timeout.signal
  try {
    try {
      return await abortable(() => cancelAnalysis(id, requestSignal), requestSignal)
    } catch (error) {
      if (!(error instanceof ApiError && error.isConflict)) throw error
    }
    const analysis = await abortable(() => getAnalysis(id, requestSignal), requestSignal).catch(
      () => null,
    )
    return !analysis || analysis.status === 'PENDING' || analysis.status === 'PROCESSING'
      ? null
      : analysis
  } finally {
    clearTimeout(timer)
  }
}

export const CANCEL_FAILED_MESSAGE = 'Nie udało się anulować analizy. Spróbuj ponownie.'
