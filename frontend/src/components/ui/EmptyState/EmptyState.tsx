import type { ReactNode } from 'react'

import { clsx } from 'clsx'
import type { LucideIcon } from 'lucide-react'

import styles from './EmptyState.module.css'

interface EmptyStateProps {
  icon: LucideIcon
  // dropzone = przerywana obwódka jak strefa uploadu (akcent na hover) + brandowy kafelek ikony;
  // card = zwykła pełna karta + neutralny kafelek (np. „brak wyników").
  variant?: 'dropzone' | 'card'
  title: string
  children?: ReactNode // opis pod tytułem
  actions?: ReactNode // przyciski (LinkButton / Button)
  hint?: ReactNode // opcjonalny drobny mono-tekst pod akcją
}

// Prezentacyjny pusty stan: kafelek z ikoną → tytuł → opis → akcje. Treść wstrzykiwana przez
// propsy, więc obsługuje onboarding (brak danych) i stany „nic nie znaleziono".
export function EmptyState({
  icon: Icon,
  variant = 'card',
  title,
  children,
  actions,
  hint,
}: EmptyStateProps) {
  const isDropzone = variant === 'dropzone'

  return (
    <div className={clsx(styles.empty, isDropzone ? styles.dropzone : styles.card)}>
      <span
        className={clsx(styles.icon, isDropzone ? styles.iconAccent : styles.iconNeutral)}
        aria-hidden="true"
      >
        <Icon size={36} strokeWidth={2} />
      </span>
      <h2 className={styles.title}>{title}</h2>
      {children && <p className={styles.text}>{children}</p>}
      {actions && <div className={styles.actions}>{actions}</div>}
      {hint && <p className={styles.hint}>{hint}</p>}
    </div>
  )
}
