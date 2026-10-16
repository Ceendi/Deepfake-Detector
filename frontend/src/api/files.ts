import { apiFetch, apiErrorFrom } from './client'
import { ApiError } from './errors'
import { env } from '@/config/env'
import { getToken } from '@/auth/keycloak'
import { newCorrelationId } from '@/utils/correlationId'
import type { FileMetadata, PresignResponse, UploadResponse } from './types'

export function uploadFile(file: File, signal?: AbortSignal): Promise<UploadResponse> {
  const form = new FormData()
  form.append('file', file)
  return apiFetch<UploadResponse>('/files/upload', { method: 'POST', body: form, signal })
}

export async function uploadFileWithProgress(
  file: File,
  opts: { onProgress?: (pct: number) => void; signal?: AbortSignal } = {},
): Promise<UploadResponse> {
  const { onProgress, signal } = opts
  const token = await getToken()

  return new Promise<UploadResponse>((resolve, reject) => {
    if (signal?.aborted) return reject(abortError())

    const correlationId = newCorrelationId()
    const form = new FormData()
    form.append('file', file)

    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${env.apiBaseUrl}/files/upload`)
    if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    xhr.setRequestHeader('X-Correlation-Id', correlationId)

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress?.((e.loaded / e.total) * 100)
    }

    xhr.upload.onload = () => onProgress?.(100)

    xhr.onload = () => {
      const corr = xhr.getResponseHeader('X-Correlation-Id') ?? correlationId
      const body = parseJsonSafe(xhr.responseText)
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(body as UploadResponse)
      } else {
        reject(apiErrorFrom(xhr.status, body, corr))
      }
    }
    xhr.onerror = () =>
      reject(
        new ApiError({ status: 0, message: 'Błąd sieci podczas wysyłki pliku.', correlationId }),
      )
    xhr.onabort = () => reject(abortError())

    signal?.addEventListener('abort', () => xhr.abort(), { once: true })
    xhr.send(form)
  })
}

function abortError(): DOMException {
  return new DOMException('Upload przerwany.', 'AbortError')
}

function parseJsonSafe(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

export function getFileMetadata(id: string, signal?: AbortSignal): Promise<FileMetadata> {
  return apiFetch<FileMetadata>(`/files/${id}/metadata`, { signal })
}

export function presignFile(id: string, signal?: AbortSignal): Promise<PresignResponse> {
  return apiFetch<PresignResponse>(`/files/${id}/presign`, { signal })
}

// DELETE /api/files/{id} — soft-delete, 204 No Content.
export function deleteFile(id: string): Promise<void> {
  return apiFetch<void>(`/files/${id}`, { method: 'DELETE' })
}
