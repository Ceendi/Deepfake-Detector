import styles from './RadialGauge.module.css'

import { clsx } from 'clsx'

import type { CSSProperties, ReactNode } from 'react'

interface RadialGaugeProps {
  value: number // 0..100 — wypełnienie pierścienia (i wyświetlana liczba %)
  tone?: 'accent' | 'danger' | 'success'
  label?: ReactNode // podpis pod liczbą (np. „pewność werdyktu")
  size?: number // średnica w px
  thickness?: number // grubość pierścienia w px
}

// Kołowy wskaźnik (donut) na czystym CSS: conic-gradient = pierścień, ::before = wycięty środek.
// Siostra ProgressBar dla wartości 0..100; kolor steruje `tone`.
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
