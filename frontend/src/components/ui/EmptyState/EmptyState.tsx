import type { ReactNode } from 'react'

import { clsx } from 'clsx'
import type { LucideIcon } from 'lucide-react'

import styles from './EmptyState.module.css'

interface EmptyStateProps {
  icon: LucideIcon

  variant?: 'dropzone' | 'card'
  title: string
  children?: ReactNode
  actions?: ReactNode
  hint?: ReactNode
}

export function EmptyState({
  icon: Icon,
  variant = 'card',
  title,
  children,
  actions,
  hint,
}: EmptyStateProps) {
  const isDropzone = variant === 'dropzone'

  return (
    <div className={clsx(styles.empty, isDropzone ? styles.dropzone : styles.card)}>
      <span
        className={clsx(styles.icon, isDropzone ? styles.iconAccent : styles.iconNeutral)}
        aria-hidden="true"
      >
        <Icon size={36} strokeWidth={2} />
      </span>
      <h2 className={styles.title}>{title}</h2>
      {children && <p className={styles.text}>{children}</p>}
      {actions && <div className={styles.actions}>{actions}</div>}
      {hint && <p className={styles.hint}>{hint}</p>}
    </div>
  )
}
