import { ChevronLeft, Video, AudioLines } from 'lucide-react'

import type { Analysis } from '@/api/types'
import { LinkButton } from '@/components/ui/LinkButton/LinkButton'

import { ReportPdfButton } from './ReportPdfButton'
import {
  displayName,
  typeLabel,
  formatDateTime,
  analysisDuration,
  relativeTime,
} from '../result-utils'

import styles from '../AnalysisResult.module.css'

export function ResultHeader({ analysis }: { analysis: Analysis }) {
  const Icon = analysis.type === 'AUDIO' ? AudioLines : Video
  const inProgress = analysis.status === 'PENDING' || analysis.status === 'PROCESSING'
  // W toku nie ma jeszcze „czasu analizy" → pokaż kiedy ruszyła; po zakończeniu — ile trwała.
  const timeInfo = inProgress
    ? `rozpoczęto: ${relativeTime(analysis.createdAt)}`
    : `czas analizy: ${analysisDuration(analysis.createdAt, analysis.updatedAt)}`

  return (
    <header className={styles.header}>
      <div className={styles.headerMain}>
        <span className={styles.fileThumb} aria-hidden="true">
          <Icon size={22} strokeWidth={2} />
        </span>
        <div className={styles.headerText}>
          <h1 className={styles.headerTitle}>{displayName(analysis.fileKey)}</h1>
          <div className={styles.meta}>
            <span className={styles.typeChip}>{typeLabel(analysis.type)}</span>
            <span className={styles.metaText}>{formatDateTime(analysis.createdAt)}</span>
            <span className={styles.metaDot} aria-hidden="true">
              ·
            </span>
            <span className={styles.metaText}>{timeInfo}</span>
          </div>
        </div>
      </div>

      <div className={styles.headerActions}>
        <LinkButton to="/history" variant="ghost" size="md" leftIcon={ChevronLeft}>
          Historia
        </LinkButton>
        <ReportPdfButton variant="ghost" label="Pobierz PDF" />
      </div>
    </header>
  )
}
