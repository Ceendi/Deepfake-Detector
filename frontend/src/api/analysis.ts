// Orchestrator — start analizy, pobranie, lista (paginacja), anulowanie.
// Kontrakt: docs/contracts/rest-api.md. SSE (stream postępu) i report.pdf → osobno (KROK 10/11).
import { apiFetch } from './client'
import type { Analysis, AnalysisSummary, Paged, StartAnalysisRequest } from './types'

// POST /api/analysis → 201 Analysis. 429 = backpressure (ApiError.backpressure) lub rate-limit.
export function startAnalysis(request: StartAnalysisRequest): Promise<Analysis> {
  return apiFetch<Analysis>('/analysis', { method: 'POST', body: request })
}

// GET /api/analysis/{id} → pełny Analysis. 404 gdy brak / cudzy (IDOR).
export function getAnalysis(id: string, signal?: AbortSignal): Promise<Analysis> {
  return apiFetch<Analysis>(`/analysis/${id}`, { signal })
}

// GET /api/analysis?page&size → strona lekkich AnalysisSummary (createdAt DESC).
export function listAnalyses(
  page = 0,
  size = 20,
  signal?: AbortSignal,
): Promise<Paged<AnalysisSummary>> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  return apiFetch<Paged<AnalysisSummary>>(`/analysis?${query.toString()}`, { signal })
}

// DELETE /api/analysis/{id} → 200 Analysis (CANCELLED). Idempotentne; 409 gdy już skończona.
export function cancelAnalysis(id: string): Promise<Analysis> {
  return apiFetch<Analysis>(`/analysis/${id}`, { method: 'DELETE' })
}
