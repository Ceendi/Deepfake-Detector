import styles from './Alert.module.css'

import type { ComponentPropsWithoutRef } from 'react'

import { Check, TriangleAlert, CircleAlert, Info, X } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

import { clsx } from 'clsx'

interface AlertProps extends ComponentPropsWithoutRef<'div'> {
  variant: 'success' | 'danger' | 'warning' | 'info'
  title: string
  icon?: LucideIcon
  onClose?: () => void
}

const variantIcon = {
  success: Check,
  danger: TriangleAlert,
  warning: CircleAlert,
  info: Info,
} as const

export function Alert({ variant, title, icon, onClose, children, className, ...rest }: AlertProps) {
  const Icon = icon ?? variantIcon[variant]
  const role = variant === 'danger' ? 'alert' : 'status'

  return (
    <div {...rest} role={role} className={clsx(styles.alert, styles[variant], className)}>
      <Icon aria-hidden="true" size={16} strokeWidth={2.4} />
      <p className={styles.title}>{title}</p>
      <p className={styles.text}>{children}</p>
      {onClose && (
        <button type="button" className={styles.close} onClick={onClose} aria-label="Zamknij">
          <X aria-hidden="true" size={16} strokeWidth={2.4} />
        </button>
      )}
    </div>
  )
}
