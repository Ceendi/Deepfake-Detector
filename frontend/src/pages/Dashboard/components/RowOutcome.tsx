import { clsx } from 'clsx'

import type { AnalysisSummary } from '@/api/types'

import { Badge } from '@/components/ui/Badge/Badge'
import { ProgressBar } from '@/components/ui/ProgressBar/ProgressBar'

import styles from '../Dashboard.module.css'

// Werdykt + status zależą od `status` — różne gałęzie renderu dla każdego stanu cyklu życia.
export function RowOutcome({ analysis }: { analysis: AnalysisSummary }) {
  const { status, verdict, confidence } = analysis

  if (status === 'PENDING' || status === 'PROCESSING') {
    return (
      <>
        <ProgressBar className={styles.rowProgress} />
        <span className={styles.status}>
          <span className={clsx(styles.dot, styles.dotAccent)} />
          Analiza w toku
        </span>
      </>
    )
  }

  if (status === 'FAILED') {
    return (
      <span className={clsx(styles.status, styles.statusDanger)}>
        <span className={clsx(styles.dot, styles.dotDanger)} />
        Błąd analizy
      </span>
    )
  }

  if (status === 'CANCELLED') {
    return (
      <span className={styles.status}>
        <span className={clsx(styles.dot, styles.dotMuted)} />
        Anulowana
      </span>
    )
  }

  // COMPLETED — werdykt + pewność.
  return (
    <>
      <span className={styles.verdictGroup}>
        {verdict === 'FAKE' ? (
          <Badge variant="danger" size="sm" soft>
            FAKE
          </Badge>
        ) : (
          <Badge variant="success" size="sm" soft>
            REAL
          </Badge>
        )}
        {confidence != null && <span className={styles.confidence}>{confidence.toFixed(2)}</span>}
      </span>
      <span className={styles.status}>
        <span className={clsx(styles.dot, styles.dotSuccess)} />
        Zakończona
      </span>
    </>
  )
}
