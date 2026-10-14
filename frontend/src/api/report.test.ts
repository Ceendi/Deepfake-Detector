import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/config/env', () => ({ env: { apiBaseUrl: '/api' } }))
vi.mock('@/auth/keycloak', () => ({ getToken: vi.fn(() => Promise.resolve('tok')) }))

import { fetchReportPdf } from './analysis'
import { ApiError } from './errors'

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('fetchReportPdf', () => {
  it('wysyła Bearer i akceptuje JSON obok PDF (błąd nie kończy się 406)', async () => {
    fetchMock.mockResolvedValue(
      new Response('%PDF', {
        status: 200,
        headers: { 'Content-Disposition': 'attachment; filename="report-an-1.pdf"' },
      }),
    )

    const { filename, blob } = await fetchReportPdf('an-1')

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const headers = init.headers as Record<string, string>
    expect(url).toBe('/api/analysis/an-1/report.pdf')
    expect(headers.Authorization).toBe('Bearer tok')
    expect(headers.Accept).toContain('application/pdf')
    expect(headers.Accept).toContain('application/json')
    expect(filename).toBe('report-an-1.pdf')
    expect(blob.size).toBe(4)
  })

  it('bez Content-Disposition używa nazwy domyślnej', async () => {
    fetchMock.mockResolvedValue(new Response('%PDF', { status: 200 }))
    expect((await fetchReportPdf('an-2')).filename).toBe('report-an-2.pdf')
  })

  it('409 → ApiError z isConflict', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 409 }))
    const err = await fetchReportPdf('an-3').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).isConflict).toBe(true)
  })
})
