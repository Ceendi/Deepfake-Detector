// Cienki, typowany wrapper na fetch dla całego API.
// Robi: bazowy URL, token (Authorization), X-Correlation-Id (D2), serializację body
// (JSON/FormData), parsowanie odpowiedzi i mapowanie błędów na ApiError.
// Komponenty NIE wołają fetch wprost — idą przez funkcje z api/analysis.ts i api/files.ts.
import { env } from '@/config/env'
import { newCorrelationId } from '@/utils/correlationId'
import { ApiError } from './errors'
import type { BackpressureInfo } from './types'

// --- Dostawca tokena (odwrócenie zależności) ---------------------------------
// client.ts świadomie NIE importuje modułu auth, żeby warstwa API była niezależna i testowalna
// bez Keycloaka. Moduł auth (KROK 5) rejestruje dostawcę przez setTokenProvider(getToken).
// Do tego czasu żądania lecą bez nagłówka Authorization (np. zwrócą 401 z chronionych endpointów).
type TokenProvider = () => Promise<string | undefined>

let tokenProvider: TokenProvider = () => Promise.resolve(undefined)

export function setTokenProvider(provider: TokenProvider): void {
  tokenProvider = provider
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  // Obiekt → JSON.stringify; FormData → wysyłane surowo (przeglądarka sama ustawi boundary).
  body?: unknown
  // AbortSignal — anulowanie (cancel uploadu / sprzątanie fetcha w useEffect).
  signal?: AbortSignal
  headers?: Record<string, string>
}

// `path` podajemy WZGLĘDEM env.apiBaseUrl (= '/api'), czyli np. '/analysis', '/files/upload'.
export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, signal, headers = {} } = options

  const correlationId = newCorrelationId()
  const requestHeaders: Record<string, string> = {
    'X-Correlation-Id': correlationId,
    ...headers,
  }

  const token = await tokenProvider()
  if (token) requestHeaders.Authorization = `Bearer ${token}`

  let payload: BodyInit | undefined
  if (body instanceof FormData) {
    payload = body // NIE ustawiamy Content-Type — boundary doda przeglądarka.
  } else if (body !== undefined) {
    payload = JSON.stringify(body)
    requestHeaders['Content-Type'] = 'application/json'
  }

  const response = await fetch(`${env.apiBaseUrl}${path}`, {
    method,
    headers: requestHeaders,
    body: payload,
    signal,
  })

  if (!response.ok) {
    throw await toApiError(response, correlationId)
  }

  // 204 No Content (np. DELETE pliku) — brak ciała do sparsowania.
  if (response.status === 204) return undefined as T

  return (await response.json()) as T
}

// Mapuje nie-OK odpowiedź na ApiError. Body bywa JSON-em ({ code, message }), pustką (rate-limit
// z Gateway) albo backpressure ({ queuePosition, retryAfterSeconds }).
async function toApiError(response: Response, sentCorrelationId: string): Promise<ApiError> {
  const correlationId = response.headers.get('X-Correlation-Id') ?? sentCorrelationId

  let code: string | undefined
  let message = `Żądanie nie powiodło się (HTTP ${response.status})`
  let backpressure: BackpressureInfo | undefined

  const data = await readJsonSafe(response)
  if (data && typeof data === 'object') {
    const obj = data as Record<string, unknown>
    if (typeof obj.code === 'string') code = obj.code
    if (typeof obj.message === 'string') message = obj.message
    if (typeof obj.queuePosition === 'number' && typeof obj.retryAfterSeconds === 'number') {
      backpressure = { queuePosition: obj.queuePosition, retryAfterSeconds: obj.retryAfterSeconds }
    }
  }

  return new ApiError({ status: response.status, message, code, correlationId, backpressure })
}

async function readJsonSafe(response: Response): Promise<unknown> {
  if (!response.headers.get('Content-Type')?.includes('application/json')) return null
  try {
    return await response.json()
  } catch {
    return null
  }
}
