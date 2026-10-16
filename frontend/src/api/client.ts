import { env } from '@/config/env'
import { newCorrelationId } from '@/utils/correlationId'
import { ApiError } from './errors'
import type { BackpressureInfo } from './types'

type TokenProvider = () => Promise<string | undefined>

let tokenProvider: TokenProvider = () => Promise.resolve(undefined)

export function setTokenProvider(provider: TokenProvider): void {
  tokenProvider = provider
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'

  body?: unknown

  signal?: AbortSignal
  headers?: Record<string, string>
  cache?: RequestCache
}

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, signal, headers = {}, cache } = options

  const correlationId = newCorrelationId()
  const requestHeaders: Record<string, string> = {
    'X-Correlation-Id': correlationId,
    ...headers,
  }

  const token = await tokenProvider()
  if (token) requestHeaders.Authorization = `Bearer ${token}`

  let payload: BodyInit | undefined
  if (body instanceof FormData) {
    payload = body
  } else if (body !== undefined) {
    payload = JSON.stringify(body)
    requestHeaders['Content-Type'] = 'application/json'
  }

  const response = await fetch(`${env.apiBaseUrl}${path}`, {
    method,
    headers: requestHeaders,
    body: payload,
    signal,
    cache,
  })

  if (!response.ok) {
    throw await toApiError(response, correlationId)
  }

  if (response.status === 204) return undefined as T

  return (await response.json()) as T
}

async function toApiError(response: Response, sentCorrelationId: string): Promise<ApiError> {
  const correlationId = response.headers.get('X-Correlation-Id') ?? sentCorrelationId
  const data = await readJsonSafe(response)
  return apiErrorFrom(response.status, data, correlationId)
}

export function apiErrorFrom(status: number, body: unknown, correlationId?: string): ApiError {
  let code: string | undefined
  let message = `Żądanie nie powiodło się (HTTP ${status})`
  let backpressure: BackpressureInfo | undefined

  if (body && typeof body === 'object') {
    const obj = body as Record<string, unknown>
    if (typeof obj.code === 'string') code = obj.code
    if (typeof obj.message === 'string') message = obj.message
    if (typeof obj.queuePosition === 'number' && typeof obj.retryAfterSeconds === 'number') {
      backpressure = {
        queuePosition: obj.queuePosition,
        retryAfterSeconds: obj.retryAfterSeconds,
        reason: obj.reason === 'USER_LIMIT' ? 'USER_LIMIT' : 'GLOBAL_LIMIT',
      }
      if (typeof obj.limit === 'number' && Number.isInteger(obj.limit) && obj.limit > 0) {
        backpressure.limit = obj.limit
      }
    }
  }

  return new ApiError({ status, message, code, correlationId, backpressure })
}

async function readJsonSafe(response: Response): Promise<unknown> {
  if (!response.headers.get('Content-Type')?.includes('application/json')) return null
  try {
    return await response.json()
  } catch {
    return null
  }
}
