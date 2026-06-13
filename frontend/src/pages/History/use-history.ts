import { useEffect, useState } from 'react'

import { listAnalyses } from '@/api/analysis'
import type { AnalysisSummary } from '@/api/types'

type LoadState = 'loading' | 'ready' | 'error'

// Pobiera CAŁĄ historię (wszystkie strony) — filtrowanie i paginacja dzieją się po stronie klienta,
// bo GET /api/analysis nie ma jeszcze parametrów filtra. OK dla skali pracy dyplomowej; gdy backend
// dostanie query params, ten hook zwęża się do jednej strony serwera. Patrz applyFilters w filters.ts.
const FETCH_PAGE_SIZE = 100

export function useHistory() {
  const [state, setState] = useState<LoadState>('loading')
  const [items, setItems] = useState<AnalysisSummary[]>([])

  useEffect(() => {
    const controller = new AbortController()

    fetchAllAnalyses(controller.signal)
      .then((all) => {
        setItems(all)
        setState('ready')
      })
      .catch((err) => {
        if (controller.signal.aborted || (err instanceof DOMException && err.name === 'AbortError'))
          return
        setState('error')
      })

    return () => controller.abort()
  }, [])

  return { state, items }
}

async function fetchAllAnalyses(signal: AbortSignal): Promise<AnalysisSummary[]> {
  const first = await listAnalyses(0, FETCH_PAGE_SIZE, signal)
  const totalPages = first.page.totalPages
  if (totalPages <= 1) return first.content

  // Pozostałe strony równolegle (zwykle 1–2 przy tej skali).
  const rest = await Promise.all(
    Array.from({ length: totalPages - 1 }, (_, i) => listAnalyses(i + 1, FETCH_PAGE_SIZE, signal)),
  )
  return [first.content, ...rest.map((page) => page.content)].flat()
}
