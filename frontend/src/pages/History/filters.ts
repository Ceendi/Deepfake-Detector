// Definicje filtrów historii — opcje, typy i helpery. Współdzielone przez toolbar (dropdowny),
// chipy aktywnych filtrów i filtrowanie listy.
import type { AnalysisSummary } from '@/api/types'

export interface FilterOption {
  value: string
  label: string
}

export interface FilterChip {
  id: string
  label: string
}

export type FilterKey = 'status' | 'type' | 'verdict' | 'range'

export interface Filters {
  status: string
  type: string
  verdict: string
  range: string
}

// Pierwsza opcja w każdej liście = wartość domyślna ('all') → filtr nieaktywny, bez chipa.
export const STATUS_OPTIONS: FilterOption[] = [
  { value: 'all', label: 'wszystkie' },
  { value: 'COMPLETED', label: 'Zakończone' },
  { value: 'PROCESSING', label: 'W trakcie' },
  { value: 'FAILED', label: 'Nieudane' },
  { value: 'CANCELLED', label: 'Anulowane' },
]

export const TYPE_OPTIONS: FilterOption[] = [
  { value: 'all', label: 'wszystkie' },
  { value: 'VIDEO', label: 'Wideo' },
  { value: 'AUDIO', label: 'Audio' },
]

export const VERDICT_OPTIONS: FilterOption[] = [
  { value: 'all', label: 'wszystkie' },
  { value: 'FAKE', label: 'FAKE' },
  { value: 'REAL', label: 'REAL' },
]

export const RANGE_OPTIONS: FilterOption[] = [
  { value: 'all', label: 'dowolny' },
  { value: '1d', label: '1 dzień' },
  { value: '7d', label: '1 tydzień' },
  { value: '30d', label: '30 dni' },
]

export const DEFAULT_FILTERS: Filters = {
  status: 'all',
  type: 'all',
  verdict: 'all',
  range: 'all',
}

// Etykieta opcji po jej wartości (do chipów i przycisków). Fallback na surową wartość.
export function labelOf(options: FilterOption[], value: string): string {
  return options.find((option) => option.value === value)?.label ?? value
}

const DAY_MS = 24 * 60 * 60 * 1000
const RANGE_MS: Record<string, number> = { '1d': DAY_MS, '7d': 7 * DAY_MS, '30d': 30 * DAY_MS }

// Filtrowanie po stronie klienta. TODO(backend): gdy GET /api/analysis dostanie query params
// (status/type/verdict/from/to) + `name` w projekcji → przejść na server-side filter + paginację.
// Szukajka działa po `fileId` (lista nie zwraca nazwy pliku — patrz AnalysisSummary).
export function applyFilters(
  items: AnalysisSummary[],
  filters: Filters,
  query: string,
): AnalysisSummary[] {
  const q = query.trim().toLowerCase()
  const rangeMs = RANGE_MS[filters.range]
  const now = Date.now()

  return items.filter((it) => {
    if (filters.status !== 'all' && it.status !== filters.status) return false
    if (filters.type !== 'all' && it.type !== filters.type) return false
    if (filters.verdict !== 'all' && it.verdict !== filters.verdict) return false
    if (rangeMs && now - new Date(it.createdAt).getTime() > rangeMs) return false
    if (q && !it.fileId.toLowerCase().includes(q)) return false
    return true
  })
}
