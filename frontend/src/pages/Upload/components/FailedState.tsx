import { TriangleAlert, CircleAlert, RotateCcw } from 'lucide-react'

import { Button } from '@/components/ui/Button/Button'

import styles from '../Upload.module.css'

interface FailedStateProps {
  message: string
  onRetry: () => void
}

// Faza „failed" — błąd uploadu/startu/analizy. „Spróbuj ponownie" wraca do podglądu (plik zostaje).
export function FailedState({ message, onRetry }: FailedStateProps) {
  return (
    <div className={styles.errorCard}>
      <div className={styles.errorIconTile}>
        <TriangleAlert size={36} strokeWidth={2} aria-hidden="true" />
      </div>
      <h2 className={styles.stateTitle}>Analiza się nie powiodła</h2>
      <p className={styles.errorBox} role="alert">
        <CircleAlert size={18} strokeWidth={2.4} aria-hidden="true" />
        <span>{message}</span>
      </p>
      <Button variant="primary" size="md" leftIcon={RotateCcw} onClick={onRetry}>
        Spróbuj ponownie
      </Button>
    </div>
  )
}
