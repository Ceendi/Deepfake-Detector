import { describe, expect, it, vi } from 'vitest'
vi.mock('@/config/env', () => ({ env: { apiBaseUrl: '/api' } }))

import { apiErrorFrom } from './client'

describe('apiErrorFrom backpressure', () => {
  it.each(['USER_LIMIT', 'GLOBAL_LIMIT'])('parses %s and the configured limit', (reason) => {
    const body = { queuePosition: 21, retryAfterSeconds: 5, reason, limit: 7 }
    expect(apiErrorFrom(429, body).backpressure).toEqual(body)
  })

  it('treats an older body without reason as the global limit', () => {
    expect(apiErrorFrom(429, { queuePosition: 21, retryAfterSeconds: 5 }).backpressure).toEqual({
      queuePosition: 21,
      retryAfterSeconds: 5,
      reason: 'GLOBAL_LIMIT',
    })
  })

  it('keeps gateway and SSE rate limits without backpressure', () => {
    expect(apiErrorFrom(429, null).backpressure).toBeUndefined()
    expect(
      apiErrorFrom(429, { code: 'TOO_MANY_REQUESTS', message: 'Too many streams' }).backpressure,
    ).toBeUndefined()
  })
})
