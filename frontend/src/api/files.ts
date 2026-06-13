// File Service — upload, metadata, presign, delete. Kontrakt: docs/contracts/rest-api.md.
import { apiFetch, apiErrorFrom } from './client'
import { ApiError } from './errors'
import { env } from '@/config/env'
import { getToken } from '@/auth/keycloak'
import { newCorrelationId } from '@/utils/correlationId'
import type { FileMetadata, PresignResponse, UploadResponse } from './types'

// POST /api/files/upload (multipart). Walidacja magic bytes + ffprobe jest po stronie backendu:
// 413 = za duży (>500 MB), 422 (code INVALID_FILE) = niedozwolony format A/V.
export function uploadFile(file: File, signal?: AbortSignal): Promise<UploadResponse> {
  const form = new FormData()
  form.append('file', file)
  return apiFetch<UploadResponse>('/files/upload', { method: 'POST', body: form, signal })
}

// Jak uploadFile, ale przez XMLHttpRequest — fetch NIE raportuje postępu WYSYŁKI (brak upload.onprogress).
// Potrzebne do paska „Wgrywanie pliku… X%". Świadoma, wąska duplikacja logiki tokena/correlation/ApiError
// z client.ts (XHR nie ma alternatywy dla postępu); mapowanie body→ApiError współdzielimy przez apiErrorFrom.
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
    // NIE ustawiamy Content-Type — boundary multipart doda przeglądarka (jak w client.ts).

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress?.((e.loaded / e.total) * 100)
    }
    // Gwarancja dobicia do 100% — niektóre przeglądarki nie emitują ostatniego progress eventu.
    // Od tego momentu UI pokazuje „przetwarzanie" (serwer: ffprobe + zapis), bo odpowiedź jeszcze leci.
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

// GET /api/files/{id}/metadata. 404 gdy brak / soft-deleted / cudzy (IDOR).
export function getFileMetadata(id: string, signal?: AbortSignal): Promise<FileMetadata> {
  return apiFetch<FileMetadata>(`/files/${id}/metadata`, { signal })
}

// GET /api/files/{id}/presign — krótkotrwały (1 h) URL do pobrania pliku wprost ze storage.
export function presignFile(id: string, signal?: AbortSignal): Promise<PresignResponse> {
  return apiFetch<PresignResponse>(`/files/${id}/presign`, { signal })
}

// DELETE /api/files/{id} — soft-delete, 204 No Content.
export function deleteFile(id: string): Promise<void> {
  return apiFetch<void>(`/files/${id}`, { method: 'DELETE' })
}
