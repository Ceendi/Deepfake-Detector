import { describe, it, expect } from 'vitest'
import { newCorrelationId } from './correlationId'

// Format UUID v4 (wariant 8/9/a/b) — to gwarantuje crypto.randomUUID().
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

describe('newCorrelationId', () => {
  it('zwraca UUID v4', () => {
    expect(newCorrelationId()).toMatch(UUID_V4)
  })

  it('generuje unikalne wartości', () => {
    const ids = new Set(Array.from({ length: 100 }, () => newCorrelationId()))
    expect(ids.size).toBe(100)
  })
})
