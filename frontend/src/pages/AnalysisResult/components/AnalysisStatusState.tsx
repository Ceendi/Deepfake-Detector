import { TriangleAlert, Ban, Plus, ChevronLeft } from 'lucide-react'

import type { Analysis } from '@/api/types'
import { LinkButton } from '@/components/ui/LinkButton/LinkButton'
import { Spinner } from '@/components/ui/Spinner/Spinner'

import styles from '../AnalysisResult.module.css'

// Stany inne niż COMPLETED — duży placeholder zamiast cienkiego alertu: wypełnia przestrzeń karty
// i jasno komunikuje status. Podgląd postępu na żywo (SSE) dla „w toku" dojdzie osobno.
export function AnalysisStatusState({ analysis }: { analysis: Analysis }) {
  if (analysis.status === 'FAILED') {
    return (
      <div className={styles.statusCard}>
        <span className={styles.statusIcon} data-tone="danger" aria-hidden="true">
          <TriangleAlert size={40} strokeWidth={2} />
        </span>
        <h2 className={styles.statusTitle}>Analiza zakończona błędem</h2>
        <p className={styles.statusText}>
          Nie udało się dokończyć przetwarzania tego materiału. Możesz spróbować ponownie z nowym
          zgłoszeniem.
        </p>
        {analysis.errorMessage && <p className={styles.statusDetail}>{analysis.errorMessage}</p>}
        <div className={styles.statusActions}>
          <LinkButton to="/upload" variant="primary" size="md" leftIcon={Plus}>
            Nowa analiza
          </LinkButton>
          <LinkButton to="/history" variant="ghost" size="md" leftIcon={ChevronLeft}>
            Wróć do historii
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
          Przetwarzanie tego materiału zostało przerwane, więc nie ma wyniku. Wgraj plik ponownie,
          aby przeanalizować go od nowa.
        </p>
        <div className={styles.statusActions}>
          <LinkButton to="/upload" variant="primary" size="md" leftIcon={Plus}>
            Nowa analiza
          </LinkButton>
          <LinkButton to="/history" variant="ghost" size="md" leftIcon={ChevronLeft}>
            Wróć do historii
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
