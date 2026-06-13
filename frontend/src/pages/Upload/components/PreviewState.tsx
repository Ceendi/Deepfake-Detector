import { Check, ArrowRight } from 'lucide-react'

import { Button } from '@/components/ui/Button/Button'

import styles from '../Upload.module.css'

interface PreviewStateProps {
  onReset: () => void
  onAnalyze: () => void
}

// Stopka stanu „plik gotowy". Media (podgląd) renderuje wspólna karta w Upload.tsx — dzięki temu
// <video> ma stałą tożsamość między fazami i nie przeładowuje się przy zmianie stanu.
export function PreviewState({ onReset, onAnalyze }: PreviewStateProps) {
  return (
    <div className={styles.footer}>
      <span className={styles.ready}>
        <Check size={16} strokeWidth={2.4} aria-hidden="true" />
        Plik gotowy do analizy
      </span>
      <div className={styles.actions}>
        <Button variant="ghost" size="md" onClick={onReset}>
          Usuń / wybierz inny
        </Button>
        <Button variant="primary" size="md" rightIcon={ArrowRight} onClick={onAnalyze}>
          Analizuj plik
        </Button>
      </div>
    </div>
  )
}
