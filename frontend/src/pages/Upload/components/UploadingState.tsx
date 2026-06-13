import { X } from 'lucide-react'

import { Button } from '@/components/ui/Button/Button'
import { Spinner } from '@/components/ui/Spinner/Spinner'
import { ProgressBar } from '@/components/ui/ProgressBar/ProgressBar'

import styles from '../Upload.module.css'

interface UploadingStateProps {
  progress: number // 0..100 (bajty wysłane)
  onCancel: () => void
}

// Sekcja postępu + stopka fazy „uploading". Media renderuje wspólna karta w Upload.tsx.
export function UploadingState({ progress, onCancel }: UploadingStateProps) {
  // 100% = całe ciało wysłane; teraz serwer odbiera + waliduje (ffprobe) + zapisuje, zanim odpowie.
  // Nie zostawiamy martwego 100% — pasek idzie w tryb nieokreślony z etykietą „Przetwarzanie…".
  const sending = progress < 100

  return (
    <>
      <div className={styles.progressSection}>
        <div className={styles.progressRow}>
          <span className={styles.progressLabel}>
            <Spinner size="sm" />
            {sending ? 'Wgrywanie pliku…' : 'Przetwarzanie pliku na serwerze…'}
          </span>
          {sending && <span className={styles.progressPct}>{Math.round(progress)}%</span>}
        </div>
        <ProgressBar value={sending ? progress : undefined} />
      </div>

      <div className={styles.footer}>
        <span className={styles.footerNote}>Nie zamykaj karty do zakończenia wgrywania.</span>
        <div className={styles.actions}>
          <Button variant="ghost" size="md" leftIcon={X} onClick={onCancel}>
            Anuluj
          </Button>
        </div>
      </div>
    </>
  )
}
