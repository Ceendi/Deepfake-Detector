export type AnalysisType = 'VIDEO' | 'AUDIO' | 'FULL'

export type AnalysisStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export type Verdict = 'FAKE' | 'REAL'

// --- File Service ------------------------------------------------------------
export interface UploadResponse {
  fileId: string
  fileKey: string
  size: number
  mimetype: string
}

export interface FileMetadata {
  fileId: string
  objectKey: string
  name: string | null
  size: number
  duration: number | null
  mimetype: string
}

// POST /api/files/{id}/claim returns the same metadata as GET /metadata.
export type FileClaimResponse = FileMetadata

// Upload errors (ErrorResponse.code): 409 STORAGE_QUOTA_EXCEEDED, 422 INVALID_FILE with a message
// prefix: UNSUPPORTED_MEDIA, INVALID_MEDIA_DURATION, MEDIA_TOO_LONG or MEDIA_RESOLUTION_TOO_HIGH;
// 413 PAYLOAD_TOO_LARGE: file > 500 MiB or multipart request > 501 MiB.
// 429 UPLOAD_USER_LIMIT and 503 SERVICE_UNAVAILABLE (replica full or probe failure), both Retry-After: 5.
export interface PresignResponse {
  url: string
  expiresAt: string // ISO 8601
}

// --- Orchestrator ------------------------------------------------------------
export interface StartAnalysisRequest {
  fileId: string
  type: AnalysisType
}

export interface SourceDetails {
  verdict?: Verdict
  confidence?: number // Distance from the decision boundary for the versioned audio contract.
  status?: 'NOT_APPLICABLE'
  reason?: 'NO_SPEECH' | 'NO_AUDIO_TRACK'
  message?: string
  modelVersion?: string
  gradcamKeys?: string[]
  gradcamUrls?: string[]
  metadata?: Record<string, unknown>
}

export interface AnalysisDetails {
  video?: SourceDetails
  audio?: SourceDetails
}

// Audio windows on the versioned shared decision scale, not calibrated probabilities.
// raw_prob_fake is retained in metadata for audit; only prob_fake drives timeline colors.

export interface AudioSegmentPrediction {
  start_time: number
  end_time: number
  prob_fake: number
}

export interface Analysis {
  id: string
  userId: string
  fileId: string
  fileKey: string
  type: AnalysisType
  status: AnalysisStatus
  verdict: Verdict | null
  confidence: number | null
  videoProb: number | null
  audioProb: number | null
  details: AnalysisDetails | null
  // Backend recovery/DLQ use [ANALYSIS_TIMEOUT] / [DEAD_LETTERED] with neutral Polish messages.
  errorMessage: string | null
  createdAt: string // ISO 8601
  updatedAt: string
}

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
  avgConfidence: number | null
  last7Days: number
  lastAnalysisAt: string | null
}

// Spring PagedModel: size ≤ 100, createdAt DESC + id DESC; sort ignored, offset ≤ 2147483647.
export interface Paged<T> {
  content: T[]
  page: {
    size: number
    number: number
    totalElements: number
    totalPages: number
  }
}

export interface BackpressureInfo {
  reason?: 'GLOBAL_LIMIT' | 'USER_LIMIT'
  limit?: number
  queuePosition: number
  retryAfterSeconds: number
}

export interface AnalysisProgressEvent {
  analysisId: string
  source: 'video' | 'audio'
  progress: number // 0..100
  stage: string
  status: AnalysisStatus
}

export interface AnalysisResultEvent {
  analysisId: string
  status: AnalysisStatus
  verdict: Verdict | null
  confidence: number | null
}
