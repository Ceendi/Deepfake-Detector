import { useEffect, useState } from 'react'

import { Link, useParams, useNavigate } from 'react-router-dom'

import { ChevronLeft, Trash2 } from 'lucide-react'

import { getAnalysis, deleteAnalysisRecord } from '@/api/analysis'
import { ApiError } from '@/api/errors'
import type { Analysis } from '@/api/types'

import { Spinner } from '@/components/ui/Spinner/Spinner'
import { Alert } from '@/components/ui/Alert/Alert'
import { Button } from '@/components/ui/Button/Button'
import { LinkButton } from '@/components/ui/LinkButton/LinkButton'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog/ConfirmDialog'

import { ResultHeader } from './components/ResultHeader'
import { VerdictHero } from './components/VerdictHero'
import { MediaPreview } from './components/MediaPreview'
import { ModalitySection } from './components/ModalitySection'
import { GradcamSection } from './components/GradcamSection'
import { AudioTimeline } from './components/AudioTimeline'
import { SummarySection } from './components/SummarySection'
import { ReportPdfButton } from './components/ReportPdfButton'
import { AnalysisStatusState } from './components/AnalysisStatusState'
import { LiveProgress } from './components/LiveProgress'

import styles from './AnalysisResult.module.css'

type LoadState = 'loading' | 'ready' | 'notfound' | 'error'

// Strona wyniku analizy. COMPLETED → werdykt + Grad-CAM (realne) + placeholdery (wnioski/model);
// PENDING/PROCESSING → LiveProgress (postęp na żywo z SSE); FAILED/CANCELLED → AnalysisStatusState.
// Po `result`/anulowaniu LiveProgress woła onSettled → refetch przełącza widok. Raport PDF osobno.
export default function AnalysisResult() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [state, setState] = useState<LoadState>('loading')
  const [analysis, setAnalysis] = useState<Analysis | null>(null)
  // Trwałe usunięcie zakończonej analizy: modal potwierdzenia + stan żądania + ewentualny błąd.
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  // Bump po SSE `result` / anulowaniu (LiveProgress.onSettled) → efekt poniżej przeładowuje pełny
  // zasób, przełączając ekran „w toku" na werdykt / FAILED / CANCELLED. setState w callbackach
  // promisy (nie synchronicznie w efekcie) — wymóg react-hooks/set-state-in-effect.
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    if (!id) return
    const controller = new AbortController()
    getAnalysis(id, controller.signal).then(
      (a) => {
        setAnalysis(a)
        setState('ready')
      },
      (err) => {
        if (controller.signal.aborted) return
        setState(err instanceof ApiError && err.isNotFound ? 'notfound' : 'error')
      },
    )
    return () => controller.abort()
  }, [id, reloadKey])

  // Trasa to /analysis-result/:id, więc w praktyce id zawsze jest — guard dla typu/edge-case.
  if (!id) {
    return (
      <Alert variant="warning" title="Brak identyfikatora analizy">
        <Link to="/history">Wróć do historii</Link>.
      </Alert>
    )
  }

  if (state === 'loading') {
    return (
      <div className={styles.center}>
        <Spinner size="lg" />
      </div>
    )
  }

  if (state === 'notfound') {
    return (
      <Alert variant="warning" title="Nie znaleziono analizy">
        Mogła zostać usunięta albo nie należy do Ciebie. <Link to="/history">Wróć do historii</Link>
        .
      </Alert>
    )
  }

  if (state === 'error' || !analysis) {
    return (
      <Alert variant="danger" title="Nie udało się wczytać analizy">
        Spróbuj odświeżyć stronę za chwilę.
      </Alert>
    )
  }

  const isCompleted =
    analysis.status === 'COMPLETED' && analysis.verdict != null && analysis.confidence != null
  const isInProgress = analysis.status === 'PENDING' || analysis.status === 'PROCESSING'

  function closeDeleteConfirm() {
    if (deleting) return // nie zamykaj w trakcie żądania
    setConfirmDelete(false)
    setDeleteError(null)
  }

  async function handleDelete() {
    setDeleting(true)
    setDeleteError(null)
    try {
      await deleteAnalysisRecord(id!) // hard delete; replace, by „wstecz" nie wracał na usunięty zasób
      navigate('/history', { replace: true })
    } catch (err) {
      // 404 = już usunięta (lub cudza) → efekt docelowy ten sam, więc też przechodzimy do historii.
      if (err instanceof ApiError && err.isNotFound) {
        navigate('/history', { replace: true })
        return
      }
      setDeleteError(
        err instanceof ApiError ? err.message : 'Nie udało się usunąć analizy. Spróbuj ponownie.',
      )
      setDeleting(false) // zostań w modalu, pozwól ponowić
    }
  }

  return (
    <div className={styles.page}>
      <Link to="/history" className={styles.backLink}>
        <ChevronLeft size={16} strokeWidth={2.4} aria-hidden="true" />
        Wróć do historii
      </Link>

      <ResultHeader analysis={analysis} />

      {isCompleted && analysis.verdict != null && analysis.confidence != null ? (
        <>
          <VerdictHero verdict={analysis.verdict} confidence={analysis.confidence} />
          <MediaPreview fileId={analysis.fileId} type={analysis.type} />
          <ModalitySection analysis={analysis} />
          <GradcamSection details={analysis.details} />
          <AudioTimeline metadata={analysis.details?.audio?.metadata} />
          <SummarySection analysis={analysis} />

          <footer className={styles.footer}>
            <LinkButton to="/history" variant="ghost" size="md" leftIcon={ChevronLeft}>
              Wróć do historii
            </LinkButton>
            <div className={styles.footerEnd}>
              <Button
                variant="danger"
                size="md"
                leftIcon={Trash2}
                onClick={() => setConfirmDelete(true)}
              >
                Usuń analizę
              </Button>
              <ReportPdfButton variant="primary" label="Pobierz raport PDF" />
            </div>
          </footer>

          <ConfirmDialog
            open={confirmDelete}
            title="Usunąć analizę?"
            confirmLabel="Usuń analizę"
            danger
            isLoading={deleting}
            confirmIcon={Trash2}
            onConfirm={handleDelete}
            onCancel={closeDeleteConfirm}
          >
            <p>
              Ta operacja jest nieodwracalna — analiza zniknie z Twojej historii, a wraz z nią
              raport i heatmapy Grad-CAM.
            </p>
            {deleteError && (
              <Alert variant="danger" title="Nie udało się usunąć">
                {deleteError}
              </Alert>
            )}
          </ConfirmDialog>
        </>
      ) : isInProgress ? (
        <LiveProgress
          key={analysis.id}
          analysis={analysis}
          onSettled={(terminal) => {
            // Recovery already loaded the full terminal resource. Avoid another stale GET.
            if (terminal) setAnalysis(terminal)
            else setReloadKey((k) => k + 1)
          }}
        />
      ) : (
        <AnalysisStatusState analysis={analysis} />
      )}
    </div>
  )
}
