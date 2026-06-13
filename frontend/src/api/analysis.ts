// Orchestrator — start analizy, pobranie, lista (paginacja), anulowanie.
// Kontrakt: docs/contracts/rest-api.md. SSE (stream postępu) i report.pdf → osobno (KROK 10/11).
import { apiFetch, apiErrorFrom } from './client'
import { env } from '@/config/env'
import { getToken } from '@/auth/keycloak'
import { newCorrelationId } from '@/utils/correlationId'
import type { Analysis, AnalysisSummary, Paged, StartAnalysisRequest, UserStats } from './types'

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

// GET /api/analysis/stats → agregaty analiz usera (dashboard). Scoped do jwt.sub (brak IDOR).
export function getStats(signal?: AbortSignal): Promise<UserStats> {
  return apiFetch<UserStats>('/analysis/stats', { signal })
}

// DELETE /api/analysis/{id} → 200 Analysis (CANCELLED). Idempotentne; 409 gdy już skończona.
export function cancelAnalysis(id: string): Promise<Analysis> {
  return apiFetch<Analysis>(`/analysis/${id}`, { method: 'DELETE' })
}

// GET artefaktu Grad-CAM (image/png). `url` pochodzi z `details.*.gradcamUrls` i ZAWIERA już
// prefiks '/api', więc NIE idzie przez apiFetch (który dokleiłby env.apiBaseUrl drugi raz).
// Endpoint wymaga Bearer → pobieramy surowo jako Blob z tokenem; wołający robi URL.createObjectURL
// (przeglądarka nie doda nagłówka Authorization do zwykłego <img src>). Prefiks '/api' podmieniamy
// na env.apiBaseUrl, żeby działało i za proxy ('/api'), i przy pełnym hoście gatewaya.
export async function fetchArtifact(url: string, signal?: AbortSignal): Promise<Blob> {
  const correlationId = newCorrelationId()
  const token = await getToken()

  const headers: Record<string, string> = { 'X-Correlation-Id': correlationId }
  if (token) headers.Authorization = `Bearer ${token}`

  const response = await fetch(`${env.apiBaseUrl}${url.replace(/^\/api/, '')}`, { headers, signal })
  if (!response.ok) {
    throw apiErrorFrom(
      response.status,
      null,
      response.headers.get('X-Correlation-Id') ?? correlationId,
    )
  }
  return response.blob()
}
