import type { ComponentPropsWithRef } from 'react'

import styles from './Button.module.css'

import { clsx } from 'clsx'
import type { LucideIcon } from 'lucide-react'

import { Spinner } from '@/components/ui/Spinner/Spinner'

interface ButtonProps extends ComponentPropsWithRef<'button'> {
  variant: 'primary' | 'danger' | 'ghost'
  size: 'sm' | 'md'
  isLoading?: boolean
  leftIcon?: LucideIcon
  rightIcon?: LucideIcon
}

export function Button({
  variant,
  size,
  isLoading = false,
  type = 'button',
  disabled,
  leftIcon: LeftIcon,
  rightIcon: RightIcon,
  children,
  className,
  ref,
  ...rest
}: ButtonProps) {
  return (
    <button
      {...rest}
      ref={ref}
      type={type}
      disabled={disabled || isLoading}
      aria-busy={isLoading}
      className={clsx(styles.button, styles[variant], styles[size], className)}
    >
      {isLoading ? (
        <Spinner size="sm" />
      ) : (
        LeftIcon && <LeftIcon size={16} strokeWidth={2.4} aria-hidden="true" />
      )}
      {children}
      {!isLoading && RightIcon && <RightIcon size={16} strokeWidth={2.4} aria-hidden="true" />}
    </button>
  )
}
