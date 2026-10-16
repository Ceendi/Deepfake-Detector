import { useMemo, useState } from 'react'

import { Plus } from 'lucide-react'

import { LinkButton } from '@/components/ui/LinkButton/LinkButton'
import { Spinner } from '@/components/ui/Spinner/Spinner'
import { Alert } from '@/components/ui/Alert/Alert'

import { useFileNames } from '@/api/file-meta'
import { useHistory } from './use-history'
import { HistoryToolbar } from './components/HistoryToolbar'
import { HistoryEmpty } from './components/HistoryEmpty'
import { HistoryNoResults } from './components/HistoryNoResults'
import { HistoryList } from './components/HistoryList'
import { Pagination } from './components/Pagination'
import {
  applyFilters,
  DEFAULT_FILTERS,
  STATUS_OPTIONS,
  TYPE_OPTIONS,
  VERDICT_OPTIONS,
  RANGE_OPTIONS,
  labelOf,
  type Filters,
  type FilterKey,
  type FilterChip,
} from './filters'

import styles from './History.module.css'

const PAGE_SIZE = 10

export default function History() {
  const { state, items } = useHistory()

  const [query, setQuery] = useState('')
  const [filters, setFilters] = useState<Filters>(DEFAULT_FILTERS)
  const [page, setPage] = useState(0)

  function setFilter(key: FilterKey, value: string) {
    setFilters((prev) => ({ ...prev, [key]: value }))
    setPage(0)
  }
  function changeQuery(value: string) {
    setQuery(value)
    setPage(0)
  }
  function clearAll() {
    setQuery('')
    setFilters(DEFAULT_FILTERS)
    setPage(0)
  }
  function removeFilter(id: string) {
    if (id === 'query') changeQuery('')
    else setFilter(id as FilterKey, 'all')
  }

  const activeFilters: FilterChip[] = []
  if (filters.status !== 'all')
    activeFilters.push({
      id: 'status',
      label: `Status: ${labelOf(STATUS_OPTIONS, filters.status)}`,
    })
  if (filters.type !== 'all')
    activeFilters.push({ id: 'type', label: `Typ: ${labelOf(TYPE_OPTIONS, filters.type)}` })
  if (filters.verdict !== 'all')
    activeFilters.push({
      id: 'verdict',
      label: `Werdykt: ${labelOf(VERDICT_OPTIONS, filters.verdict)}`,
    })
  if (filters.range !== 'all')
    activeFilters.push({ id: 'range', label: `Zakres: ${labelOf(RANGE_OPTIONS, filters.range)}` })
  if (query.trim() !== '') activeFilters.push({ id: 'query', label: `„${query.trim()}”` })

  const searching = query.trim() !== ''
  const fileIds = useMemo(() => items.map((it) => it.fileId), [items])
  const { names: fileNames, loading: namesLoading, incomplete } = useFileNames(fileIds, searching)
  const filtered = useMemo(
    () => applyFilters(items, filters, query, fileNames),
    [items, filters, query, fileNames],
  )

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const safePage = Math.min(page, totalPages - 1)
  const pageItems = filtered.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE)
  const from = filtered.length === 0 ? 0 : safePage * PAGE_SIZE + 1
  const to = Math.min((safePage + 1) * PAGE_SIZE, filtered.length)

  return (
    <div className={styles.page}>
      <div className={styles.head}>
        <div>
          <h1>Historia analiz</h1>
          <p className={styles.subtitle}>Wszystkie Twoje analizy w jednym miejscu.</p>
        </div>
        <LinkButton to="/upload" variant="primary" size="md" leftIcon={Plus}>
          Nowa analiza
        </LinkButton>
      </div>

      {state === 'loading' && (
        <div className={styles.center}>
          <Spinner size="lg" />
        </div>
      )}

      {state === 'error' && (
        <Alert variant="danger" title="Nie udało się wczytać historii">
          Spróbuj odświeżyć stronę za chwilę.
        </Alert>
      )}

      {state === 'ready' && items.length === 0 && <HistoryEmpty />}

      {state === 'ready' && items.length > 0 && (
        <>
          <HistoryToolbar
            query={query}
            onQueryChange={changeQuery}
            filters={filters}
            onFilterChange={setFilter}
            activeFilters={activeFilters}
            onRemoveFilter={removeFilter}
            onClear={clearAll}
          />

          {searching && incomplete && (
            <Alert variant="warning" title="Wyniki mogą być niepełne">
              Nie udało się pobrać wszystkich nazw plików. Spróbuj ponownie za chwilę.
            </Alert>
          )}

          {filtered.length === 0 && searching && namesLoading ? (
            <div className={styles.center}>
              <Spinner label="Szukam po nazwach plików…" />
            </div>
          ) : filtered.length === 0 ? (
            <HistoryNoResults onClear={clearAll} />
          ) : (
            <HistoryList
              items={pageItems}
              footer={
                <>
                  <span className={styles.count}>
                    Wyświetlasz{' '}
                    <strong>
                      {from}–{to}
                    </strong>{' '}
                    z <strong>{filtered.length}</strong> analiz
                  </span>
                  <Pagination page={safePage} totalPages={totalPages} onChange={setPage} />
                </>
              }
            />
          )}
        </>
      )}
    </div>
  )
}
