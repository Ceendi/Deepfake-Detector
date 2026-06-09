import styles from './Badge.module.css'
import type { ComponentPropsWithoutRef, ReactNode } from 'react'
import { clsx } from 'clsx'

import { Check, TriangleAlert, CircleAlert } from 'lucide-react'

type BadgeVariant = 'success' | 'danger' | 'warning'

interface BadgeProps extends ComponentPropsWithoutRef<'span'> {
  variant: BadgeVariant
  soft?: boolean
  children: ReactNode
}

const variantIcon = {
  success: Check,
  danger: TriangleAlert,
  warning: CircleAlert,
} as const

export function Badge({ variant, soft = false, children, className, ...rest }: BadgeProps) {
  const Icon = variantIcon[variant]

  return (
    <span {...rest} className={clsx(styles.badge, styles[variant], soft && styles.soft, className)}>
      <Icon size={16} strokeWidth={2.4} aria-hidden="true" />
      {children}
    </span>
  )
}
