import type { ReactNode } from 'react'

import type { AnalysisSummary } from '@/api/types'

import { HistoryRow } from './HistoryRow'
import styles from '../History.module.css'

interface HistoryListProps {
  items: AnalysisSummary[]
  footer?: ReactNode
}

// Karta listy: nagłówek kolumn (tylko desktop) + wiersze + stopka (licznik/paginacja) w jednej karcie.
// Na mobile nagłówek znika, a wiersze reflowują się w układ z mockupu.
export function HistoryList({ items, footer }: HistoryListProps) {
  return (
    <div className={styles.list}>
      <div className={styles.listHead} aria-hidden="true">
        <span />
        <span>Plik</span>
        <span>Data</span>
        <span>Werdykt</span>
        <span>Status</span>
        <span />
      </div>
      {items.map((analysis) => (
        <HistoryRow key={analysis.id} analysis={analysis} />
      ))}
      {footer && <div className={styles.listFooter}>{footer}</div>}
    </div>
  )
}
