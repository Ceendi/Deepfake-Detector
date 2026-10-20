import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/config/env', () => ({ env: { apiBaseUrl: '/api' } }))
vi.mock('@/auth/keycloak', () => ({ getToken: vi.fn(() => Promise.resolve('token-1')) }))
vi.mock('@/utils/correlationId', () => ({ newCorrelationId: () => 'corr-1' }))

import { ApiError } from './errors'
import {
  deleteFile,
  getFileMetadata,
  presignFile,
  uploadFile,
  uploadFileWithProgress,
} from './files'

class FakeXhr {
  static last: FakeXhr
  method = ''
  url = ''
  headers: Record<string, string> = {}
  status = 0
  responseText = ''
  responseHeaders: Record<string, string> = {}
  sent: unknown
  upload: { onprogress?: (e: ProgressEvent) => void; onload?: () => void } = {}
  onload?: () => void
  onerror?: () => void
  onabort?: () => void
  constructor() {
    FakeXhr.last = this
  }
  open(method: string, url: string) {
    this.method = method
    this.url = url
  }
  setRequestHeader(name: string, value: string) {
    this.headers[name] = value
  }
  getResponseHeader(name: string) {
    return this.responseHeaders[name] ?? null
  }
  send(body: unknown) {
    this.sent = body
  }
  abort() {
    this.onabort?.()
  }
  respond(status: number, body: string, headers: Record<string, string> = {}) {
    this.status = status
    this.responseText = body
    this.responseHeaders = headers
    this.onload?.()
  }
}

const fetchMock = vi.fn()

beforeEach(() => {
  vi.stubGlobal('XMLHttpRequest', FakeXhr)
  vi.stubGlobal('fetch', fetchMock)
  fetchMock.mockReset()
})

afterEach(() => vi.unstubAllGlobals())

const file = new File(['x'], 'clip.mp4', { type: 'video/mp4' })

describe('uploadFileWithProgress', () => {
  it('sends the file with auth and correlation headers and reports progress', async () => {
    const onProgress = vi.fn()
    const pending = uploadFileWithProgress(file, { onProgress })
    await vi.waitFor(() => expect(FakeXhr.last?.method).toBe('POST'))
    const xhr = FakeXhr.last
    expect(xhr.url).toBe('/api/files/upload')
    expect(xhr.headers).toEqual({ Authorization: 'Bearer token-1', 'X-Correlation-Id': 'corr-1' })
    expect((xhr.sent as FormData).get('file')).toBe(file)

    xhr.upload.onprogress?.({ lengthComputable: true, loaded: 1, total: 4 } as ProgressEvent)
    xhr.upload.onprogress?.({ lengthComputable: false, loaded: 2, total: 0 } as ProgressEvent)
    xhr.upload.onload?.()
    xhr.respond(201, JSON.stringify({ id: 'f1' }))

    await expect(pending).resolves.toEqual({ id: 'f1' })
    expect(onProgress.mock.calls).toEqual([[25], [100]])
  })

  it('maps an error response to ApiError with the server correlation id', async () => {
    const pending = uploadFileWithProgress(file)
    await vi.waitFor(() => expect(FakeXhr.last?.url).toBe('/api/files/upload'))
    FakeXhr.last.respond(
      413,
      JSON.stringify({ code: 'FILE_TOO_LARGE', message: 'Plik jest za duży.' }),
      { 'X-Correlation-Id': 'server-corr' },
    )
    const error = await pending.catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 413, correlationId: 'server-corr' })
  })

  it('treats a non-JSON error body as empty', async () => {
    const pending = uploadFileWithProgress(file)
    await vi.waitFor(() => expect(FakeXhr.last?.url).toBe('/api/files/upload'))
    FakeXhr.last.respond(502, '<html>bad gateway</html>')
    await expect(pending).rejects.toMatchObject({ status: 502, correlationId: 'corr-1' })
  })

  it('reports a network failure', async () => {
    const pending = uploadFileWithProgress(file)
    await vi.waitFor(() => expect(FakeXhr.last?.url).toBe('/api/files/upload'))
    FakeXhr.last.onerror?.()
    await expect(pending).rejects.toMatchObject({
      status: 0,
      message: expect.stringMatching(/sieci/),
    })
  })

  it('aborts through the signal, also when it was aborted before sending', async () => {
    const controller = new AbortController()
    const pending = uploadFileWithProgress(file, { signal: controller.signal })
    await vi.waitFor(() => expect(FakeXhr.last?.url).toBe('/api/files/upload'))
    controller.abort()
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })

    await expect(uploadFileWithProgress(file, { signal: controller.signal })).rejects.toMatchObject(
      {
        name: 'AbortError',
      },
    )
  })
})

describe('file endpoints', () => {
  it('upload, metadata, presign and delete call the documented paths', async () => {
    fetchMock.mockImplementation((url: string, init: RequestInit) =>
      Promise.resolve(
        init.method === 'DELETE'
          ? new Response(null, { status: 204 })
          : Response.json({ url, method: init.method }),
      ),
    )
    await expect(uploadFile(file)).resolves.toEqual({ url: '/api/files/upload', method: 'POST' })
    await expect(getFileMetadata('f1')).resolves.toEqual({
      url: '/api/files/f1/metadata',
      method: 'GET',
    })
    await expect(presignFile('f1')).resolves.toEqual({
      url: '/api/files/f1/presign',
      method: 'GET',
    })
    await expect(deleteFile('f1')).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenLastCalledWith(
      '/api/files/f1',
      expect.objectContaining({ method: 'DELETE' }),
    )
  })
})
