import type { BackpressureInfo } from './types'

export class UncertainStartError extends Error {
  constructor() {
    super(
      'Nie udało się potwierdzić rozpoczęcia analizy. Sprawdź historię przed ponownym wysłaniem pliku.',
    )
  }
}

export interface ApiErrorParams {
  status: number
  message: string
  code?: string
  correlationId?: string
  backpressure?: BackpressureInfo
  retryAfterSeconds?: number
}

export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly correlationId?: string

  readonly backpressure?: BackpressureInfo
  /** Delay-seconds form of the `Retry-After` header, when the response carried one. */
  readonly retryAfterSeconds?: number

  constructor({
    status,
    message,
    code,
    correlationId,
    backpressure,
    retryAfterSeconds,
  }: ApiErrorParams) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.correlationId = correlationId
    this.backpressure = backpressure
    this.retryAfterSeconds = retryAfterSeconds
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
