import { ChevronLeft, Video, AudioLines } from 'lucide-react'

import type { Analysis } from '@/api/types'
import { LinkButton } from '@/components/ui/LinkButton/LinkButton'

import { ReportPdfButton } from './ReportPdfButton'
import { displayName, typeLabel, formatDateTime, analysisDuration } from '../result-utils'

import styles from '../AnalysisResult.module.css'

export function ResultHeader({ analysis }: { analysis: Analysis }) {
  const Icon = analysis.type === 'AUDIO' ? AudioLines : Video
  const duration = analysisDuration(analysis.createdAt, analysis.updatedAt)

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
            <span className={styles.metaText}>czas analizy: {duration}</span>
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
