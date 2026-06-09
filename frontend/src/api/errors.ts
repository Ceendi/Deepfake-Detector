// Jednolity typ błędu API. Mapowanie statusów → znaczenie wg docs/contracts/rest-api.md.
// 404 = brak LUB cudzy zasób (IDOR) — backend nigdy nie zwraca 403, więc traktuj je jak „nie ma”.
import type { BackpressureInfo } from './types'

export interface ApiErrorParams {
  status: number
  message: string
  code?: string // np. INVALID_FILE, VALIDATION_FAILED, CONFLICT
  correlationId?: string
  backpressure?: BackpressureInfo
}

export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly correlationId?: string
  // Obecne tylko przy 429 z backpressure Orchestratora ({ queuePosition, retryAfterSeconds }).
  readonly backpressure?: BackpressureInfo

  constructor({ status, message, code, correlationId, backpressure }: ApiErrorParams) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.correlationId = correlationId
    this.backpressure = backpressure
  }

  get isUnauthorized(): boolean {
    return this.status === 401
  }

  get isNotFound(): boolean {
    return this.status === 404
  }

  get isConflict(): boolean {
    return this.status === 409
  }

  get isRateLimited(): boolean {
    return this.status === 429
  }
}
