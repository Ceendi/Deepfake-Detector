import { apiFetch, apiErrorFrom } from './client'
import { env } from '@/config/env'
import { getToken } from '@/auth/keycloak'
import { newCorrelationId } from '@/utils/correlationId'
import type { Analysis, AnalysisSummary, Paged, StartAnalysisRequest, UserStats } from './types'

export function startAnalysis(request: StartAnalysisRequest): Promise<Analysis> {
  return apiFetch<Analysis>('/analysis', { method: 'POST', body: request })
}

export function getAnalysis(id: string, signal?: AbortSignal): Promise<Analysis> {
  return apiFetch<Analysis>(`/analysis/${id}`, { signal, cache: 'no-store' })
}

export function listAnalyses(
  page = 0,
  size = 20,
  signal?: AbortSignal,
): Promise<Paged<AnalysisSummary>> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  return apiFetch<Paged<AnalysisSummary>>(`/analysis?${query.toString()}`, { signal })
}

export function getStats(signal?: AbortSignal): Promise<UserStats> {
  return apiFetch<UserStats>('/analysis/stats', { signal })
}

export function cancelAnalysis(id: string, signal?: AbortSignal): Promise<Analysis> {
  return apiFetch<Analysis>(`/analysis/${id}`, { method: 'DELETE', signal })
}

export function deleteAnalysisRecord(id: string): Promise<void> {
  return apiFetch<void>(`/analysis/${id}/record`, { method: 'DELETE' })
}

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

export async function fetchReportPdf(
  id: string,
  signal?: AbortSignal,
): Promise<{ blob: Blob; filename: string }> {
  const correlationId = newCorrelationId()
  const token = await getToken()

  const headers: Record<string, string> = {
    Accept: 'application/pdf, application/json',
    'X-Correlation-Id': correlationId,
  }
  if (token) headers.Authorization = `Bearer ${token}`

  const response = await fetch(`${env.apiBaseUrl}/analysis/${id}/report.pdf`, { headers, signal })
  if (!response.ok) {
    throw apiErrorFrom(
      response.status,
      null,
      response.headers.get('X-Correlation-Id') ?? correlationId,
    )
  }
  return {
    blob: await response.blob(),
    filename:
      filenameFromDisposition(response.headers.get('Content-Disposition')) ?? `report-${id}.pdf`,
  }
}

function filenameFromDisposition(header: string | null): string | null {
  const name = header?.match(/filename="?([^";]+)"?/i)?.[1]?.trim()
  return name || null
}
