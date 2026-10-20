import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/config/env', () => ({ env: { apiBaseUrl: '/api' } }))
vi.mock('@/auth/keycloak', () => ({ getToken: vi.fn(() => Promise.resolve('token-1')) }))
vi.mock('@/utils/correlationId', () => ({ newCorrelationId: () => 'corr-1' }))

import { getToken } from '@/auth/keycloak'
import {
  cancelAnalysis,
  deleteAnalysisRecord,
  fetchArtifact,
  fetchReportPdf,
  getAnalysis,
  getStats,
  listAnalyses,
  startAnalysis,
} from './analysis'

const fetchMock = vi.fn()

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  fetchMock.mockReset()
})

afterEach(() => vi.unstubAllGlobals())

describe('analysis endpoints', () => {
  it('call the documented paths and methods', async () => {
    fetchMock.mockImplementation((url: string, init: RequestInit) =>
      Promise.resolve(
        url.endsWith('/record')
          ? new Response(null, { status: 204 })
          : Response.json({ url, method: init.method, cache: init.cache ?? null }),
      ),
    )
    await expect(startAnalysis({ fileId: 'f1', type: 'FULL' })).resolves.toMatchObject({
      url: '/api/analysis',
      method: 'POST',
    })
    await expect(getAnalysis('a1')).resolves.toEqual({
      url: '/api/analysis/a1',
      method: 'GET',
      cache: 'no-store',
    })
    await expect(listAnalyses()).resolves.toMatchObject({ url: '/api/analysis?page=0&size=20' })
    await expect(listAnalyses(2, 5)).resolves.toMatchObject({ url: '/api/analysis?page=2&size=5' })
    await expect(getStats()).resolves.toMatchObject({ url: '/api/analysis/stats' })
    await expect(cancelAnalysis('a1')).resolves.toMatchObject({ method: 'DELETE' })
    await expect(deleteAnalysisRecord('a1')).resolves.toBeUndefined()
  })
})

describe('fetchArtifact', () => {
  it('strips the /api prefix and authenticates the request', async () => {
    fetchMock.mockResolvedValue(new Response('png-bytes'))
    const blob = await fetchArtifact('/api/analysis/a1/artifacts/frame.png')
    expect(await blob.text()).toBe('png-bytes')
    expect(fetchMock).toHaveBeenCalledWith('/api/analysis/a1/artifacts/frame.png', {
      headers: { 'X-Correlation-Id': 'corr-1', Authorization: 'Bearer token-1' },
      signal: undefined,
    })
  })

  it('omits the authorization header without a token and maps errors', async () => {
    vi.mocked(getToken).mockResolvedValueOnce(undefined)
    fetchMock.mockResolvedValue(new Response(null, { status: 404 }))
    await expect(fetchArtifact('/analysis/a1/artifacts/x.png')).rejects.toMatchObject({
      status: 404,
      correlationId: 'corr-1',
    })
    expect(fetchMock.mock.calls[0][1].headers).toEqual({ 'X-Correlation-Id': 'corr-1' })
  })
})

describe('fetchReportPdf', () => {
  it('uses the filename from Content-Disposition', async () => {
    fetchMock.mockResolvedValue(
      new Response('pdf', {
        headers: { 'Content-Disposition': 'attachment; filename="raport-clip.pdf"' },
      }),
    )
    const report = await fetchReportPdf('a1')
    expect(report.filename).toBe('raport-clip.pdf')
    expect(await report.blob.text()).toBe('pdf')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/analysis/a1/report.pdf')
    expect(fetchMock.mock.calls[0][1].headers.Accept).toBe('application/pdf, application/json')
  })

  it('falls back to a generated filename', async () => {
    fetchMock.mockResolvedValue(new Response('pdf'))
    await expect(fetchReportPdf('a1')).resolves.toMatchObject({ filename: 'report-a1.pdf' })
  })

  it('maps a failed response with the server correlation id', async () => {
    fetchMock.mockResolvedValue(
      new Response(null, { status: 409, headers: { 'X-Correlation-Id': 'server-corr' } }),
    )
    await expect(fetchReportPdf('a1')).rejects.toMatchObject({
      status: 409,
      correlationId: 'server-corr',
    })
  })
})
