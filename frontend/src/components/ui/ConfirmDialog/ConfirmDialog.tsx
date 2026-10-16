import { useEffect, useId, useRef } from 'react'
import type { MouseEvent, ReactNode, SyntheticEvent } from 'react'

import type { LucideIcon } from 'lucide-react'

import { Button } from '@/components/ui/Button/Button'

import styles from './ConfirmDialog.module.css'

interface ConfirmDialogProps {
  open: boolean
  title: string
  confirmLabel: string
  cancelLabel?: string

  danger?: boolean

  isLoading?: boolean
  confirmIcon?: LucideIcon
  onConfirm: () => void
  onCancel: () => void
  children: ReactNode
}

export function ConfirmDialog({
  open,
  title,
  confirmLabel,
  cancelLabel = 'Anuluj',
  danger = false,
  isLoading = false,
  confirmIcon,
  onConfirm,
  onCancel,
  children,
}: ConfirmDialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const bodyId = useId()

  useEffect(() => {
    const dlg = ref.current
    if (!dlg) return
    if (open && !dlg.open) dlg.showModal()
    else if (!open && dlg.open) dlg.close()
  }, [open])

  function handleCancelEvent(e: SyntheticEvent<HTMLDialogElement>) {
    e.preventDefault()
    if (!isLoading) onCancel()
  }

  function handleBackdropClick(e: MouseEvent<HTMLDialogElement>) {
    if (e.target === ref.current && !isLoading) onCancel()
  }

  return (
    <dialog
      ref={ref}
      className={styles.dialog}
      aria-labelledby={titleId}
      aria-describedby={bodyId}
      onCancel={handleCancelEvent}
      onClick={handleBackdropClick}
    >
      <div className={styles.content}>
        <h2 id={titleId} className={styles.title}>
          {title}
        </h2>
        <div id={bodyId} className={styles.body}>
          {children}
        </div>
        <div className={styles.actions}>
          <Button variant="ghost" size="md" onClick={onCancel} disabled={isLoading} autoFocus>
            {cancelLabel}
          </Button>
          <Button
            variant={danger ? 'danger' : 'primary'}
            size="md"
            onClick={onConfirm}
            isLoading={isLoading}
            leftIcon={confirmIcon}
          >
            {confirmLabel}
          </Button>
        </div>
      </div>
    </dialog>
  )
}
