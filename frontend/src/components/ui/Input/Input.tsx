import styles from './Input.module.css'

import type { ComponentPropsWithRef } from 'react'

import { clsx } from 'clsx'

import { useFieldControl } from '@/components/ui/FormField/fieldContext'

type InputProps = ComponentPropsWithRef<'input'>

export function Input({
  id,
  className,
  ref,
  'aria-invalid': ariaInvalid,
  'aria-describedby': ariaDescribedBy,
  ...rest
}: InputProps) {
  const field = useFieldControl()

  return (
    <input
      {...rest}
      ref={ref}
      id={id ?? field?.id}
      aria-invalid={ariaInvalid ?? field?.invalid}
      aria-describedby={ariaDescribedBy ?? field?.describedBy}
      className={clsx(styles.input, className)}
    />
  )
}
