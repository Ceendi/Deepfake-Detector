import styles from './Badge.module.css'
import type { ComponentPropsWithoutRef, ReactNode } from 'react'
import { clsx } from 'clsx'

import { Check, TriangleAlert, CircleAlert } from 'lucide-react'

type BadgeVariant = 'success' | 'danger' | 'warning'
type BadgeSize = 'sm' | 'md'

interface BadgeProps extends ComponentPropsWithoutRef<'span'> {
  variant: BadgeVariant
  size?: BadgeSize
  soft?: boolean
  children: ReactNode
}

const variantIcon = {
  success: Check,
  danger: TriangleAlert,
  warning: CircleAlert,
} as const

const iconSize: Record<BadgeSize, number> = { sm: 14, md: 16 }

export function Badge({
  variant,
  size = 'md',
  soft = false,
  children,
  className,
  ...rest
}: BadgeProps) {
  const Icon = variantIcon[variant]

  return (
    <span
      {...rest}
      className={clsx(styles.badge, styles[variant], styles[size], soft && styles.soft, className)}
    >
      <Icon size={iconSize[size]} strokeWidth={2.4} aria-hidden="true" />
      {children}
    </span>
  )
}
