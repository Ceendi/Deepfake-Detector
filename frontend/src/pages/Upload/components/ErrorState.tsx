import { TriangleAlert, CircleAlert } from 'lucide-react'

import { Button } from '@/components/ui/Button/Button'

import styles from '../Upload.module.css'

interface ErrorStateProps {
  message: string
  onPick: () => void
}

// Stan błędu walidacji — komunikat + ponowna próba (otwiera ten sam picker co Dropzone).
export function ErrorState({ message, onPick }: ErrorStateProps) {
  return (
    <div className={styles.errorCard}>
      <div className={styles.errorIconTile}>
        <TriangleAlert size={36} strokeWidth={2} aria-hidden="true" />
      </div>
      <h2 className={styles.stateTitle}>Nie udało się dodać pliku</h2>
      <p className={styles.errorBox} role="alert">
        <CircleAlert size={18} strokeWidth={2.4} aria-hidden="true" />
        <span>{message}</span>
      </p>
      <Button variant="primary" size="md" onClick={onPick}>
        Wybierz inny plik
      </Button>
    </div>
  )
}
