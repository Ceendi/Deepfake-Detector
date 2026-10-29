import { useEffect, useState } from 'react'

import { listAllAnalyses } from '@/api/analysis-pages'
import type { AnalysisSummary } from '@/api/types'

type LoadState = 'loading' | 'ready' | 'error'

export function useHistory() {
  const [state, setState] = useState<LoadState>('loading')
  const [items, setItems] = useState<AnalysisSummary[]>([])

  useEffect(() => {
    const controller = new AbortController()

    listAllAnalyses(controller.signal)
      .then((all) => {
        if (controller.signal.aborted) return
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
