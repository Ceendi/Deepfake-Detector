import styles from './ProgressBar.module.css'

import { clsx } from 'clsx'

import type { ComponentPropsWithoutRef, CSSProperties } from 'react'

interface ProgressBarProps extends ComponentPropsWithoutRef<'div'> {
  value?: number
  tone?: 'accent' | 'danger'
  label?: string
  showValue?: boolean
}

export function ProgressBar({
  value,
  tone = 'accent',
  label,
  showValue = false,
  className,
  ...rest
}: ProgressBarProps) {
  const isIndeterminate = value == null
  const clamped = Math.min(100, Math.max(0, value ?? 0))
  const pct = Math.round(clamped)

  const showNum = showValue && !isIndeterminate
  const hasHead = label != null || showNum

  return (
    <div className={clsx(styles.progress, className)} {...rest}>
      {hasHead && (
        <div className={styles.head}>
          <span>{label}</span>
          {showNum && <span className={styles.num}>{pct}%</span>}
        </div>
      )}

      <div
        className={clsx(styles.track, styles[tone])}
        data-indeterminate={isIndeterminate || undefined}
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={isIndeterminate ? undefined : pct}
      >
        <div
          className={styles.fill}
          style={!isIndeterminate ? ({ '--value': `${pct}%` } as CSSProperties) : undefined}
        />
      </div>
    </div>
  )
}
