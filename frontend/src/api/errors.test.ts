import { describe, it, expect } from 'vitest'
import { ApiError } from './errors'

describe('ApiError', () => {
  it('jest instancją Error z nazwą "ApiError" i ustawionym message', () => {
    const err = new ApiError({ status: 500, message: 'boom' })
    expect(err).toBeInstanceOf(Error)
    expect(err.name).toBe('ApiError')
    expect(err.message).toBe('boom')
  })

  it('przepisuje opcjonalne pola (code, correlationId, backpressure)', () => {
    const err = new ApiError({
      status: 429,
      message: 'rate limited',
      code: 'CONFLICT',
      correlationId: 'corr-1',
      backpressure: { queuePosition: 3, retryAfterSeconds: 10 },
    })
    expect(err.code).toBe('CONFLICT')
    expect(err.correlationId).toBe('corr-1')
    expect(err.backpressure).toEqual({ queuePosition: 3, retryAfterSeconds: 10 })
  })

  it('gettery statusów zwracają true tylko dla swojego kodu', () => {
    expect(new ApiError({ status: 401, message: '' }).isUnauthorized).toBe(true)
    expect(new ApiError({ status: 404, message: '' }).isNotFound).toBe(true)
    expect(new ApiError({ status: 409, message: '' }).isConflict).toBe(true)
    expect(new ApiError({ status: 429, message: '' }).isRateLimited).toBe(true)
  })

  it('gettery są wzajemnie wykluczające', () => {
    const err = new ApiError({ status: 404, message: '' })
    expect(err.isNotFound).toBe(true)
    expect(err.isUnauthorized).toBe(false)
    expect(err.isConflict).toBe(false)
    expect(err.isRateLimited).toBe(false)
  })
})
