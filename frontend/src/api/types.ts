// DTO API DeepfakeDetector — pisane ręcznie z docs/contracts/rest-api.md (świadomie bez OpenAPI
// codegen). Konwencja REST/JSON: camelCase. Zmiana kontraktu backendu → zsynchronizuj ten plik.

// --- Enumy domenowe (domknięte unie zamiast string) --------------------------
export type AnalysisType = 'VIDEO' | 'AUDIO' | 'FULL'

export type AnalysisStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export type Verdict = 'FAKE' | 'REAL'

// --- File Service ------------------------------------------------------------
export interface UploadResponse {
  fileId: string
  fileKey: string
  size: number
  mimetype: string // typ WYKRYTY przez backend (Tika/ffprobe), nie zgłoszony przez klienta
}

export interface FileMetadata {
  fileId: string
  name: string | null // oryginalna nazwa uploadu; może być null
  size: number
  duration: number | null // długość mediów w sekundach (ffprobe); null gdy nieznana
  mimetype: string
}

export interface PresignResponse {
  url: string // host osiągalny z przeglądarki, nie wewnętrzny endpoint storage
  expiresAt: string // ISO 8601
}

// --- Orchestrator ------------------------------------------------------------
export interface StartAnalysisRequest {
  fileId: string
  fileKey: string
  type: AnalysisType
}

// Pełny zasób — GET /api/analysis/{id}. Nullowalność pól zależy od statusu (patrz komentarze).
export interface Analysis {
  id: string
  userId: string
  fileId: string
  fileKey: string
  type: AnalysisType
  status: AnalysisStatus
  verdict: Verdict | null // null gdy status != COMPLETED
  confidence: number | null // 0..1; null gdy status != COMPLETED
  videoProb: number | null // 0..1; null gdy źródło nieanalizowane
  audioProb: number | null // 0..1; null gdy źródło nieanalizowane
  details: Record<string, unknown> | null // MVP: null; później URL-e Grad-CAM + metadane
  errorMessage: string | null // null gdy status != FAILED
  createdAt: string // ISO 8601
  updatedAt: string
}

// Lekka projekcja — GET /api/analysis (lista). Bez fileKey/videoProb/audioProb/errorMessage/details.
export interface AnalysisSummary {
  id: string
  fileId: string
  type: AnalysisType
  status: AnalysisStatus
  verdict: Verdict | null
  confidence: number | null
  createdAt: string
  updatedAt: string
}

// Spring PagedModel — opakowanie listy z metadanymi paginacji.
export interface Paged<T> {
  content: T[]
  page: {
    size: number
    number: number
    totalElements: number
    totalPages: number
  }
}

// Body 429 przy backpressure Orchestratora (rate-limit z Gateway nie ma body — stan w nagłówkach).
export interface BackpressureInfo {
  queuePosition: number
  retryAfterSeconds: number
}

// --- SSE (realtime, KROK 10) -------------------------------------------------
// Zdarzenia z GET /api/analysis/{id}/stream. Tu tylko typy; klient SSE powstaje w KROK 10.
// Nazwy z prefiksem Analysis, by nie kolidować z wbudowanym DOM-owym `ProgressEvent`.
export interface AnalysisProgressEvent {
  analysisId: string
  source: 'video' | 'audio'
  progress: number // 0..100
  stage: string // znane: 'LOADING' | 'INFERENCE' (backend może dodać kolejne)
  status: AnalysisStatus
}

export interface AnalysisResultEvent {
  analysisId: string
  status: AnalysisStatus
  verdict: Verdict | null // null gdy status != COMPLETED
  confidence: number | null
}
