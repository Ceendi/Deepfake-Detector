import type { AnalysisSummary, AnalysisStatus, AnalysisType } from '@/api/types'

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

export function labelOf(options: FilterOption[], value: string): string {
  return options.find((option) => option.value === value)?.label ?? value
}

const DAY_MS = 24 * 60 * 60 * 1000
const RANGE_MS: Record<string, number> = { '1d': DAY_MS, '7d': 7 * DAY_MS, '30d': 30 * DAY_MS }

function statusMatches(filter: string, status: AnalysisStatus): boolean {
  if (filter === 'all') return true
  if (filter === 'PROCESSING') return status === 'PENDING' || status === 'PROCESSING'
  return status === filter
}

function typeMatches(filter: string, type: AnalysisType): boolean {
  if (filter === 'all') return true
  if (filter === 'VIDEO') return type === 'VIDEO' || type === 'FULL'
  return type === filter
}

export function applyFilters(
  items: AnalysisSummary[],
  filters: Filters,
  query: string,
  fileNames: ReadonlyMap<string, string> = new Map(),
): AnalysisSummary[] {
  const q = query.trim().toLowerCase()
  const rangeMs = RANGE_MS[filters.range]
  const now = Date.now()

  return items.filter((it) => {
    if (!statusMatches(filters.status, it.status)) return false
    if (!typeMatches(filters.type, it.type)) return false
    if (filters.verdict !== 'all' && it.verdict !== filters.verdict) return false
    if (rangeMs && now - new Date(it.createdAt).getTime() > rangeMs) return false
    if (q && !matchesQuery(it.fileId, fileNames.get(it.fileId), q)) return false
    return true
  })
}

function matchesQuery(fileId: string, name: string | undefined, q: string): boolean {
  return (name?.toLowerCase().includes(q) ?? false) || fileId.toLowerCase().includes(q)
}
