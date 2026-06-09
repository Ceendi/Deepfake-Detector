import styles from './FormField.module.css'

import { useId } from 'react'
import type { ReactNode } from 'react'

import { clsx } from 'clsx'

import { FieldContext } from './fieldContext'

interface FormFieldProps {
  label: string
  error?: string
  className?: string
  children: ReactNode
}

export function FormField({ label, error, className, children }: FormFieldProps) {
  const id = useId()
  const errorId = `${id}-error`

  return (
    <div className={clsx(styles.field, className)}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <FieldContext.Provider
        value={{ id, describedBy: error ? errorId : undefined, invalid: !!error }}
      >
        {children}
      </FieldContext.Provider>
      {error && (
        <p id={errorId} role="alert" className={styles.error}>
          {error}
        </p>
      )}
    </div>
  )
}
