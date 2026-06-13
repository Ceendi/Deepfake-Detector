import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { AnalysisSummary } from '@/api/types'
import { applyFilters, labelOf, DEFAULT_FILTERS, STATUS_OPTIONS, type Filters } from './filters'

// Stały „teraz" dla testów zależnych od czasu (filtr range). 2026-06-13 12:00 UTC.
const NOW = new Date('2026-06-13T12:00:00.000Z').getTime()
const HOUR = 60 * 60 * 1000
const DAY = 24 * HOUR

// Fabryka fixture z sensownymi domyślnymi — w teście nadpisujemy tylko istotne pole.
function summary(overrides: Partial<AnalysisSummary> = {}): AnalysisSummary {
  return {
    id: 'id-1',
    fileId: 'file-abc',
    type: 'VIDEO',
    status: 'COMPLETED',
    verdict: 'REAL',
    confidence: 0.9,
    createdAt: new Date(NOW - HOUR).toISOString(),
    updatedAt: new Date(NOW - HOUR).toISOString(),
    ...overrides,
  }
}

function filters(overrides: Partial<Filters> = {}): Filters {
  return { ...DEFAULT_FILTERS, ...overrides }
}

describe('labelOf', () => {
  it('zwraca etykietę dla znanej wartości', () => {
    expect(labelOf(STATUS_OPTIONS, 'COMPLETED')).toBe('Zakończone')
  })

  it('fallback na surową wartość gdy brak opcji', () => {
    expect(labelOf(STATUS_OPTIONS, 'UNKNOWN')).toBe('UNKNOWN')
  })
})

describe('applyFilters', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('domyślne filtry + pusta szukajka → przepuszcza wszystko', () => {
    const items = [summary({ id: 'a' }), summary({ id: 'b' })]
    expect(applyFilters(items, DEFAULT_FILTERS, '')).toHaveLength(2)
  })

  it('filtruje po statusie', () => {
    const items = [
      summary({ id: 'a', status: 'COMPLETED' }),
      summary({ id: 'b', status: 'FAILED' }),
    ]
    const out = applyFilters(items, filters({ status: 'FAILED' }), '')
    expect(out.map((i) => i.id)).toEqual(['b'])
  })

  it('filtruje po typie', () => {
    const items = [summary({ id: 'a', type: 'VIDEO' }), summary({ id: 'b', type: 'AUDIO' })]
    const out = applyFilters(items, filters({ type: 'AUDIO' }), '')
    expect(out.map((i) => i.id)).toEqual(['b'])
  })

  it('filtruje po werdykcie', () => {
    const items = [summary({ id: 'a', verdict: 'REAL' }), summary({ id: 'b', verdict: 'FAKE' })]
    const out = applyFilters(items, filters({ verdict: 'FAKE' }), '')
    expect(out.map((i) => i.id)).toEqual(['b'])
  })

  it('filtruje po oknie czasowym (range=1d) względem „teraz"', () => {
    const items = [
      summary({ id: 'recent', createdAt: new Date(NOW - 2 * HOUR).toISOString() }),
      summary({ id: 'old', createdAt: new Date(NOW - 2 * DAY).toISOString() }),
    ]
    const out = applyFilters(items, filters({ range: '1d' }), '')
    expect(out.map((i) => i.id)).toEqual(['recent'])
  })

  it('szuka po fileId (case-insensitive, z trim)', () => {
    const items = [
      summary({ id: 'a', fileId: 'file-ABC-123' }),
      summary({ id: 'b', fileId: 'other-999' }),
    ]
    const out = applyFilters(items, DEFAULT_FILTERS, '  abc  ')
    expect(out.map((i) => i.id)).toEqual(['a'])
  })

  it('łączy filtry koniunkcją (AND)', () => {
    const items = [
      summary({ id: 'match', type: 'AUDIO', verdict: 'FAKE' }),
      summary({ id: 'wrong-verdict', type: 'AUDIO', verdict: 'REAL' }),
      summary({ id: 'wrong-type', type: 'VIDEO', verdict: 'FAKE' }),
    ]
    const out = applyFilters(items, filters({ type: 'AUDIO', verdict: 'FAKE' }), '')
    expect(out.map((i) => i.id)).toEqual(['match'])
  })

  it('brak dopasowań → pusta tablica', () => {
    const items = [summary({ status: 'COMPLETED' })]
    expect(applyFilters(items, filters({ status: 'CANCELLED' }), '')).toEqual([])
  })
})
