import { getAnalysis, startAnalysis } from './analysis'
import { listAllAnalyses } from './analysis-pages'
import { ApiError, UncertainStartError } from './errors'
import type { Analysis, StartAnalysisRequest } from './types'

export function canRetryStart(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 429 || error.status === 503)
}

export async function startAnalysisSafely(
  request: StartAnalysisRequest,
  signal: AbortSignal,
): Promise<Analysis> {
  try {
    return await startAnalysis(request)
  } catch (error) {
    if (
      canRetryStart(error) ||
      (error instanceof ApiError && error.status >= 400 && error.status < 500)
    )
      throw error
    signal.throwIfAborted()
    // A lost response may follow a committed POST; a second POST could create a duplicate.
    try {
      const recoverySignal = AbortSignal.any([signal, AbortSignal.timeout(30000)])
      const analyses = await listAllAnalyses(recoverySignal)
      const existing = analyses.find(
        (item) => item.fileId === request.fileId && item.type === request.type,
      )
      if (existing) return await getAnalysis(existing.id, recoverySignal)
    } catch {
      signal.throwIfAborted()
    }
    throw new UncertainStartError()
  }
}
