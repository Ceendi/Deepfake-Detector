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
  // danger → czerwony przycisk potwierdzenia (akcja destrukcyjna, np. usunięcie).
  danger?: boolean
  // Trwa żądanie potwierdzenia → spinner na przycisku + blokada zamknięcia (ESC/tło/anuluj).
  isLoading?: boolean
  confirmIcon?: LucideIcon
  onConfirm: () => void
  onCancel: () => void
  children: ReactNode
}

// Generyczny dialog potwierdzenia oparty na natywnym <dialog> (showModal): top-layer bez wojen
// z z-index, ::backdrop, focus trap, ESC i powrót focusu na element wyzwalający — wszystko za darmo.
// Sterujemy nim imperatywnie z propa `open`; rodzic trzyma stan i podaje onConfirm/onCancel.
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

  // open → showModal() (rzuca, jeśli już otwarty → guard na dlg.open); !open → close().
  useEffect(() => {
    const dlg = ref.current
    if (!dlg) return
    if (open && !dlg.open) dlg.showModal()
    else if (!open && dlg.open) dlg.close()
  }, [open])

  // Natywny `cancel` (ESC). preventDefault → nie zamykamy natywnie, tylko przez zmianę `open`
  // w rodzicu (jedno źródło prawdy). W trakcie żądania ESC ignorujemy, by nie porzucić akcji.
  function handleCancelEvent(e: SyntheticEvent<HTMLDialogElement>) {
    e.preventDefault()
    if (!isLoading) onCancel()
  }

  // Klik w tło: backdrop nie jest osobnym elementem — trafia w sam <dialog> (padding ma .content).
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
          {/* autoFocus na „Anuluj" — bezpieczny domyślny focus; Enter/ESC nie wykona destrukcyjnej akcji */}
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
