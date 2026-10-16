import { useEffect, useState } from 'react'

import { Link, useParams } from 'react-router-dom'

import { ChevronLeft } from 'lucide-react'

import { getAnalysis } from '@/api/analysis'
import { ApiError } from '@/api/errors'
import type { Analysis } from '@/api/types'

import { Spinner } from '@/components/ui/Spinner/Spinner'
import { Alert } from '@/components/ui/Alert/Alert'
import { LinkButton } from '@/components/ui/LinkButton/LinkButton'

import { ResultHeader } from './components/ResultHeader'
import { VerdictHero } from './components/VerdictHero'
import { MediaPreview } from './components/MediaPreview'
import { ModalitySection } from './components/ModalitySection'
import { GradcamSection } from './components/GradcamSection'
import { AudioTimeline } from './components/AudioTimeline'
import { SummarySection } from './components/SummarySection'
import { ReportPdfButton } from './components/ReportPdfButton'
import { AnalysisStatusState } from './components/AnalysisStatusState'
import { DeleteAnalysisButton } from './components/DeleteAnalysisButton'
import { LiveProgress } from './components/LiveProgress'

import styles from './AnalysisResult.module.css'

type LoadState = 'loading' | 'ready' | 'notfound' | 'error'

export default function AnalysisResultRoute() {
  const { id } = useParams<{ id: string }>()

  return <AnalysisResult key={id} id={id} />
}

function AnalysisResult({ id }: { id: string | undefined }) {
  const [state, setState] = useState<LoadState>('loading')
  const [analysis, setAnalysis] = useState<Analysis | null>(null)

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
              <DeleteAnalysisButton analysis={analysis} />
              <ReportPdfButton
                analysisId={analysis.id}
                status={analysis.status}
                variant="primary"
                label="Pobierz raport PDF"
              />
            </div>
          </footer>
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
        <AnalysisStatusState
          analysis={analysis}
          deleteAction={<DeleteAnalysisButton analysis={analysis} />}
        />
      )}
    </div>
  )
}
