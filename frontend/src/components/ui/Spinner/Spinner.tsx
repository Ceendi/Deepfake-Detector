import type { ComponentPropsWithoutRef } from 'react'

import { clsx } from 'clsx'

import styles from './Spinner.module.css'

type SpinnerSize = 'sm' | 'md' | 'lg'

interface SpinnerProps extends ComponentPropsWithoutRef<'span'> {
  size?: SpinnerSize
  label?: string
}

export function Spinner({ size = 'md', label = 'Ładowanie', className, ...rest }: SpinnerProps) {
  return (
    <span role="status" className={clsx(styles.spinner, styles[size], className)} {...rest}>
      <span aria-hidden="true" className={styles.ring}>
        <span />
        <span />
        <span />
        <span />
      </span>
      <span className="visually-hidden">{label}</span>
    </span>
  )
}
