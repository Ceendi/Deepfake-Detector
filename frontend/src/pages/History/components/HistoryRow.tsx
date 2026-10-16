import { Link } from 'react-router-dom'

import { Video, AudioLines, ChevronRight } from 'lucide-react'
import { clsx } from 'clsx'

import type { AnalysisSummary, AnalysisStatus } from '@/api/types'
import { Badge } from '@/components/ui/Badge/Badge'
import { ProgressBar } from '@/components/ui/ProgressBar/ProgressBar'

import { useFileMeta } from '@/api/file-meta'
import styles from '../History.module.css'

const dayFmt = new Intl.DateTimeFormat('pl-PL', { day: 'numeric', month: 'long' })
const timeFmt = new Intl.DateTimeFormat('pl-PL', { hour: '2-digit', minute: '2-digit' })

function formatDateTime(date: Date): string {
  return `${dayFmt.format(date)}, ${timeFmt.format(date)}`
}

function formatDuration(seconds: number): string {
  const total = Math.round(seconds)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(h)}:${pad(m)}:${pad(s)}`
}

const STATUS_META: Record<AnalysisStatus, { label: string; dot: string; danger?: boolean }> = {
  COMPLETED: { label: 'Zakończona', dot: styles.dotSuccess },
  PROCESSING: { label: 'W toku', dot: styles.dotAccent },
  PENDING: { label: 'W toku', dot: styles.dotAccent },
  FAILED: { label: 'Błąd', dot: styles.dotDanger, danger: true },
  CANCELLED: { label: 'Anulowana', dot: styles.dotMuted },
}

export function HistoryRow({ analysis }: { analysis: AnalysisSummary }) {
  const meta = useFileMeta(analysis.fileId)
  const isVideo = analysis.type !== 'AUDIO'
  const TypeIcon = isVideo ? Video : AudioLines
  const status = STATUS_META[analysis.status]
  const inProgress = analysis.status === 'PENDING' || analysis.status === 'PROCESSING'

  const title = meta?.name ?? `#${analysis.fileId.slice(0, 8)}`
  const created = formatDateTime(new Date(analysis.createdAt))
  const durationStr = meta?.duration != null ? formatDuration(meta.duration) : null

  return (
    <Link to={`/analysis-result/${analysis.id}`} className={styles.row}>
      <span className={styles.thumb} aria-hidden="true">
        <TypeIcon size={20} strokeWidth={2} />
        <span className={styles.thumbLabel}>{analysis.type}</span>
      </span>

      <span className={styles.name} title={title}>
        {title}
      </span>
      <span className={styles.sub}>
        <span className={styles.subDate}>{created}</span>
        {durationStr && (
          <span className={styles.subDot} aria-hidden="true">
            ·
          </span>
        )}
        {durationStr && <span className={styles.subDuration}>{durationStr}</span>}
      </span>

      <span className={styles.date}>{created}</span>

      <span className={styles.verdict}>
        {analysis.status === 'COMPLETED' ? (
          <Badge variant={analysis.verdict === 'FAKE' ? 'danger' : 'success'} size="sm" soft>
            {analysis.verdict}
          </Badge>
        ) : inProgress ? (
          <ProgressBar className={styles.miniProgress} />
        ) : (
          <span className={styles.dash}>—</span>
        )}
      </span>

      <span className={clsx(styles.status, status.danger && styles.statusDanger)}>
        <span className={clsx(styles.dot, status.dot)} />
        {status.label}
      </span>

      <ChevronRight className={styles.chevron} size={18} strokeWidth={2} aria-hidden="true" />
    </Link>
  )
}
