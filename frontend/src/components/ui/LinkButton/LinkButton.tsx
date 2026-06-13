import { Link } from 'react-router-dom'
import type { LinkProps } from 'react-router-dom'

//Link Button beirze style z zwykłęgo Button'a
import styles from '@/components/ui/Button/Button.module.css'

import { clsx } from 'clsx'
import type { LucideIcon } from 'lucide-react'

interface LinkButtonProps extends LinkProps {
  variant: 'primary' | 'danger' | 'ghost'
  size: 'sm' | 'md'
  leftIcon?: LucideIcon
  rightIcon?: LucideIcon
}

export function LinkButton({
  variant,
  size,
  leftIcon: LeftIcon,
  rightIcon: RightIcon,
  children,
  className,
  ...rest
}: LinkButtonProps) {
  return (
    <Link {...rest} className={clsx(styles.button, styles[variant], styles[size], className)}>
      {LeftIcon && <LeftIcon size={16} strokeWidth={2.4} aria-hidden="true" />}
      {children}
      {RightIcon && <RightIcon size={16} strokeWidth={2.4} aria-hidden="true" />}
    </Link>
  )
}
