import { cancelAnalysis, getAnalysis } from './analysis'
import { ApiError } from './errors'
import type { Analysis } from './types'

/**
 * Cancel and resolve to the terminal resource. 200 and 409 are both settled outcomes: 409 means
 * the analysis finished first, so its real status is read back. Resolves `null` when that read
 * fails or still shows it active (the open stream delivers the result). Other errors propagate.
 */
export async function cancelAndSettle(id: string): Promise<Analysis | null> {
  try {
    return await cancelAnalysis(id)
  } catch (error) {
    if (!(error instanceof ApiError && error.isConflict)) throw error
  }
  const analysis = await getAnalysis(id).catch(() => null)
  return !analysis || analysis.status === 'PENDING' || analysis.status === 'PROCESSING'
    ? null
    : analysis
}

export const CANCEL_FAILED_MESSAGE = 'Nie udało się anulować analizy. Spróbuj ponownie.'
