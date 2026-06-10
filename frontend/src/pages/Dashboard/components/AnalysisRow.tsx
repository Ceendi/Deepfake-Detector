import { Video, AudioLines } from 'lucide-react'

import type { AnalysisSummary } from '@/api/types'

import { RowOutcome } from './RowOutcome'
import styles from '../Dashboard.module.css'

const dateFmt = new Intl.DateTimeFormat('pl-PL', {
  day: '2-digit',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
})

export function AnalysisRow({ analysis }: { analysis: AnalysisSummary }) {
  const isVideo = analysis.type !== 'AUDIO'
  const TypeIcon = isVideo ? Video : AudioLines

  return (
    <div className={styles.row}>
      <span className={styles.thumb} aria-hidden="true">
        <TypeIcon size={16} strokeWidth={2} />
        <span className={styles.thumbLabel}>{analysis.type}</span>
      </span>

      <div className={styles.rowMain}>
        {/* Lista nie zwraca nazwy pliku (lekka projekcja) — pokazujemy skrócone fileId.
            Pełna nazwa pochodzi z File Service (FileMetadata.name) i dojdzie przy wzbogaceniu. */}
        <div className={styles.rowTitle}>#{analysis.fileId.slice(0, 8)}</div>
        <div className={styles.rowMeta}>{dateFmt.format(new Date(analysis.createdAt))}</div>
      </div>

      <div className={styles.rowRight}>
        <RowOutcome analysis={analysis} />
      </div>
    </div>
  )
}
