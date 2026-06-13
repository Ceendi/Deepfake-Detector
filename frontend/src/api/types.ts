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

// Wynik jednego źródła (video/audio) w `Analysis.details`. `gradcamUrls` to gotowe ścieżki
// endpointu artefaktów (TO czytamy), `gradcamKeys` = surowe klucze w buckecie (audyt),
// `metadata` = wolnoformatowy obiekt detektora (snake_case, np. `frames_analyzed`).
export interface SourceDetails {
  modelVersion: string
  gradcamKeys: string[]
  gradcamUrls: string[]
  metadata?: Record<string, unknown>
}

// `details` grupuje wyniki per źródło — obecne są tylko te, które zdążyły zaraportować.
export interface AnalysisDetails {
  video?: SourceDetails
  audio?: SourceDetails
}

// Per-segmentowa predykcja audio (`details.audio.metadata.segment_predictions`). Czasy w sekundach,
// `prob_fake` to realne P(FAKE) okna (kontrakt: amqp-messages.md — w przeciwieństwie do wideo
// `frame_predictions`, które są wagami attention). Okna nachodzą się (≈1 s okno, 0.5 s skok),
// mogą mieć luki (cisza/brak mowy) i być downsamplowane do ≤500 wpisów.
export interface AudioSegmentPrediction {
  start_time: number
  end_time: number
  prob_fake: number
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
  details: AnalysisDetails | null // null dopóki żaden detektor nie zaraportował
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

// Agregaty analiz usera — GET /api/analysis/stats (dashboard). Scoped do jwt.sub (brak IDOR).
// verdicts liczy tylko COMPLETED (fake + real == byStatus.completed). avgConfidence null do
// pierwszego COMPLETED; lastAnalysisAt null dla usera bez analiz.
export interface UserStats {
  total: number
  byStatus: {
    completed: number
    failed: number
    cancelled: number
    inProgress: number // PENDING + PROCESSING
  }
  byType: {
    video: number
    audio: number
    full: number
  }
  verdicts: {
    fake: number
    real: number
  }
  avgConfidence: number | null // 0..1; null dopóki nic nie ukończone
  last7Days: number // analizy utworzone w ostatnich 7 dniach (dowolny status)
  lastAnalysisAt: string | null // ISO 8601 createdAt najnowszej; null dla świeżego usera
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
