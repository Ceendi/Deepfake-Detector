import styles from './RadialGauge.module.css'

import { clsx } from 'clsx'

import type { CSSProperties, ReactNode } from 'react'

interface RadialGaugeProps {
  value: number
  tone?: 'accent' | 'danger' | 'success'
  label?: ReactNode
  size?: number
  thickness?: number
}

export function RadialGauge({
  value,
  tone = 'accent',
  label,
  size = 160,
  thickness = 16,
}: RadialGaugeProps) {
  const clamped = Math.min(100, Math.max(0, value))
  const pct = Math.round(clamped)

  const cssVars = {
    '--value': clamped,
    '--size': `${size}px`,
    '--thickness': `${thickness}px`,
  } as CSSProperties

  return (
    <div
      className={clsx(styles.gauge, styles[tone])}
      style={cssVars}
      role="meter"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={pct}
    >
      <span className={styles.inner}>
        <span className={styles.value}>{pct}%</span>
        {label && <span className={styles.label}>{label}</span>}
      </span>
    </div>
  )
}
