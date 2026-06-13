import type { ReactNode } from 'react'

import { clsx } from 'clsx'
import type { LucideIcon } from 'lucide-react'

import styles from '../Dashboard.module.css'

type Tone = 'accent' | 'danger' | 'success' | 'warning'

const ICON_TONE_CLASS: Record<Tone, string> = {
  accent: styles.statIconAccent,
  danger: styles.statIconDanger,
  success: styles.statIconSuccess,
  warning: styles.statIconWarning,
}

export interface StatCardProps {
  icon: LucideIcon
  label: string
  value: string | number
  iconTone: Tone
  valueTone?: 'danger' | 'success'
  meta: ReactNode
}

export function StatCard({ icon: Icon, label, value, iconTone, valueTone, meta }: StatCardProps) {
  return (
    <div className={styles.statCard}>
      <div className={styles.statTop}>
        <span className={styles.statLabel}>{label}</span>
        <span className={clsx(styles.statIcon, ICON_TONE_CLASS[iconTone])} aria-hidden="true">
          <Icon size={18} strokeWidth={2} />
        </span>
      </div>
      <span
        className={clsx(
          styles.statValue,
          valueTone === 'danger' && styles.statValueDanger,
          valueTone === 'success' && styles.statValueSuccess,
        )}
      >
        {value}
      </span>
      <div className={styles.statMeta}>{meta}</div>
    </div>
  )
}
