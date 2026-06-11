import { useState } from 'react'

import { Search, X, SlidersHorizontal } from 'lucide-react'
import { clsx } from 'clsx'

import { FilterSelect } from './FilterSelect'
import {
  STATUS_OPTIONS,
  TYPE_OPTIONS,
  VERDICT_OPTIONS,
  RANGE_OPTIONS,
  type Filters,
  type FilterKey,
  type FilterChip,
} from '../filters'
import styles from '../History.module.css'

interface HistoryToolbarProps {
  query: string
  onQueryChange: (value: string) => void
  filters: Filters
  onFilterChange: (key: FilterKey, value: string) => void
  activeFilters: FilterChip[]
  onRemoveFilter: (id: string) => void
  onClear: () => void
}

export function HistoryToolbar({
  query,
  onQueryChange,
  filters,
  onFilterChange,
  activeFilters,
  onRemoveFilter,
  onClear,
}: HistoryToolbarProps) {
  // Na mobile dropdowny chowają się za przyciskiem „Filtry"; na desktopie są zawsze widoczne (CSS).
  const [filtersOpen, setFiltersOpen] = useState(false)
  const hasActiveFilters = activeFilters.length > 0

  return (
    <div className={styles.toolbarWrap}>
      <div className={styles.toolbar}>
        <label className={styles.search}>
          <Search size={18} strokeWidth={2} aria-hidden="true" />
          <input
            type="search"
            className={styles.searchInput}
            placeholder="Szukaj po nazwie pliku…"
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            aria-label="Szukaj po nazwie pliku"
          />
        </label>

        <button
          type="button"
          className={clsx(styles.filtersToggle, hasActiveFilters && styles.filtersToggleActive)}
          onClick={() => setFiltersOpen((open) => !open)}
          aria-expanded={filtersOpen}
        >
          <SlidersHorizontal size={16} strokeWidth={2.4} aria-hidden="true" />
          Filtry{hasActiveFilters && ` (${activeFilters.length})`}
        </button>

        <div className={clsx(styles.filterGroup, filtersOpen && styles.filterGroupOpen)}>
          <FilterSelect
            label="Status"
            value={filters.status}
            options={STATUS_OPTIONS}
            onChange={(value) => onFilterChange('status', value)}
          />
          <FilterSelect
            label="Typ"
            value={filters.type}
            options={TYPE_OPTIONS}
            onChange={(value) => onFilterChange('type', value)}
          />
          <FilterSelect
            label="Werdykt"
            value={filters.verdict}
            options={VERDICT_OPTIONS}
            onChange={(value) => onFilterChange('verdict', value)}
          />
          <FilterSelect
            label="Zakres"
            value={filters.range}
            options={RANGE_OPTIONS}
            onChange={(value) => onFilterChange('range', value)}
          />

          <button
            type="button"
            className={styles.clear}
            onClick={onClear}
            disabled={!hasActiveFilters}
          >
            <X size={16} strokeWidth={2.4} aria-hidden="true" />
            Wyczyść
          </button>
        </div>
      </div>

      {hasActiveFilters && (
        <div className={styles.activeFilters}>
          <span className={styles.activeLabel}>Aktywne filtry:</span>
          {activeFilters.map((filter) => (
            <button
              key={filter.id}
              type="button"
              className={styles.chip}
              onClick={() => onRemoveFilter(filter.id)}
              aria-label={`Usuń filtr: ${filter.label}`}
            >
              {filter.label}
              <X size={14} strokeWidth={2.4} aria-hidden="true" />
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
