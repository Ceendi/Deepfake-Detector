import type { ReactNode } from 'react'

import { TriangleAlert, Ban, RotateCcw, ChevronLeft } from 'lucide-react'

import { analysisFailureMessage, parseErrorMessage } from '@/api/error-codes'
import type { Analysis } from '@/api/types'
import { LinkButton } from '@/components/ui/LinkButton/LinkButton'
import { Spinner } from '@/components/ui/Spinner/Spinner'

import styles from '../AnalysisResult.module.css'

// Stany inne niż COMPLETED — duży placeholder zamiast cienkiego alertu: wypełnia przestrzeń karty
// i jasno komunikuje status. `deleteAction` — przycisk usunięcia rekordu (tylko stany terminalne).
export function AnalysisStatusState({
  analysis,
  deleteAction,
}: {
  analysis: Analysis
  deleteAction?: ReactNode
}) {
  if (analysis.status === 'FAILED') {
    // Pokazujemy tylko kod — treść errorMessage bywa techniczna (starsze rekordy, detektory).
    const code = parseErrorMessage(analysis.errorMessage).code
    return (
      <div className={styles.statusCard}>
        <span className={styles.statusIcon} data-tone="danger" aria-hidden="true">
          <TriangleAlert size={40} strokeWidth={2} />
        </span>
        <h2 className={styles.statusTitle}>Analiza nie powiodła się</h2>
        <p className={styles.statusText}>{analysisFailureMessage(analysis.errorMessage)}</p>
        {code && <p className={styles.statusDetail}>Kod błędu: {code}</p>}
        <div className={styles.statusActions}>
          <LinkButton to="/history" variant="ghost" size="md" leftIcon={ChevronLeft}>
            Wróć do historii
          </LinkButton>
          {deleteAction}
          {/* Brak endpointu re-run — „Spróbuj ponownie" = nowe zgłoszenie przez /upload */}
          <LinkButton to="/upload" variant="primary" size="md" leftIcon={RotateCcw}>
            Spróbuj ponownie
          </LinkButton>
        </div>
      </div>
    )
  }

  if (analysis.status === 'CANCELLED') {
    return (
      <div className={styles.statusCard}>
        <span className={styles.statusIcon} data-tone="warning" aria-hidden="true">
          <Ban size={40} strokeWidth={2} />
        </span>
        <h2 className={styles.statusTitle}>Analiza anulowana</h2>
        <p className={styles.statusText}>
          Przetwarzanie tego materiału zostało przerwane, więc nie ma wyniku. Spróbuj ponownie, aby
          przeanalizować plik od nowa.
        </p>
        <div className={styles.statusActions}>
          <LinkButton to="/history" variant="ghost" size="md" leftIcon={ChevronLeft}>
            Wróć do historii
          </LinkButton>
          {deleteAction}
          <LinkButton to="/upload" variant="primary" size="md" leftIcon={RotateCcw}>
            Spróbuj ponownie
          </LinkButton>
        </div>
      </div>
    )
  }

  // PENDING / PROCESSING
  return (
    <div className={styles.statusCard}>
      <span className={styles.statusIcon} data-tone="accent" aria-hidden="true">
        <Spinner size="md" />
      </span>
      <h2 className={styles.statusTitle}>Analiza w toku</h2>
      <p className={styles.statusText}>
        Trwa przetwarzanie materiału. Wynik pojawi się tutaj po zakończeniu — podgląd postępu na
        żywo dochodzi wkrótce.
      </p>
      <div className={styles.statusActions}>
        <LinkButton to="/history" variant="ghost" size="md" leftIcon={ChevronLeft}>
          Wróć do historii
        </LinkButton>
      </div>
    </div>
  )
}
