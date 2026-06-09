// File Service — upload, metadata, presign, delete. Kontrakt: docs/contracts/rest-api.md.
import { apiFetch } from './client'
import type { FileMetadata, PresignResponse, UploadResponse } from './types'

// POST /api/files/upload (multipart). Walidacja magic bytes + ffprobe jest po stronie backendu:
// 413 = za duży (>500 MB), 422 (code INVALID_FILE) = niedozwolony format A/V.
export function uploadFile(file: File, signal?: AbortSignal): Promise<UploadResponse> {
  const form = new FormData()
  form.append('file', file)
  return apiFetch<UploadResponse>('/files/upload', { method: 'POST', body: form, signal })
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
