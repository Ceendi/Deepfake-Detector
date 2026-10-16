import { ArrowRight, CircleAlert, X } from 'lucide-react'

import { Button } from '@/components/ui/Button/Button'
import { LinkButton } from '@/components/ui/LinkButton/LinkButton'
import { ProgressBar } from '@/components/ui/ProgressBar/ProgressBar'

import type { AnalysisType } from '@/api/types'

import { isVideoFile } from '../upload-utils'
import type { ProgressBySource } from '../use-analysis-flow'
import styles from '../Upload.module.css'

interface AnalyzingStateProps {
  file: File
  analysisType?: AnalysisType | null
  bySource: ProgressBySource
  onCancel?: () => void
  cancelling?: boolean
  cancelError?: string | null

  stillRunningId?: string
  timedOut?: boolean
  watching?: boolean
}

const SOURCE_LABEL = { video: 'Wideo', audio: 'Audio' } as const

const STAGE_LABELS: Record<string, string> = {
  LOADING: 'Wczytywanie',
  PREPROCESSING: 'Przetwarzanie wstępne',
  INFERENCE: 'Analiza',
  POSTPROCESSING: 'Finalizacja',

  EXTRACTING_AUDIO: 'Ekstrakcja audio',
  PREPROCESSING_AUDIO: 'Przetwarzanie wstępne',
  ANALYZING_SEGMENTS: 'Analiza fragmentów',
  GENERATING_HEATMAP: 'Generowanie mapy cieplnej',
  ANALYSIS_COMPLETED: 'Finalizacja',
}

export function AnalyzingState({
  file,
  analysisType,
  bySource,
  onCancel,
  cancelling = false,
  cancelError = null,
  stillRunningId,
  watching = true,
  timedOut = false,
}: AnalyzingStateProps) {
  const audioOnly = analysisType ? analysisType === 'AUDIO' : !isVideoFile(file)
  const sources = audioOnly ? (['audio'] as const) : (['video', 'audio'] as const)

  return (
    <>
      <div className={styles.progressSection}>
        {sources.map((source) => {
          const sp = bySource[source]
          const stageLabel = sp ? STAGE_LABELS[sp.stage] : undefined
          return (
            <ProgressBar
              key={source}
              value={sp?.progress}
              label={stageLabel ? `${SOURCE_LABEL[source]} · ${stageLabel}` : SOURCE_LABEL[source]}
              showValue
            />
          )
        })}
      </div>

      {cancelError && (
        <p className={styles.inlineError} role="alert">
          <CircleAlert size={16} strokeWidth={2.4} aria-hidden="true" />
          <span>{cancelError}</span>
        </p>
      )}

      <div className={styles.footer}>
        {stillRunningId ? (
          <span className={styles.footerNote} role="status">
            {timedOut
              ? 'Przekroczono czas oczekiwania na analizę. Sprawdź jej stan na stronie analizy.'
              : watching
                ? 'Analiza nadal trwa… Wynik pojawi się tutaj albo na stronie analizy.'
                : 'Analiza nadal trwa… Sprawdź jej stan na stronie analizy.'}
          </span>
        ) : (
          <span className={styles.footerNote}>
            Analiza w toku — to zwykle kilka–kilkanaście sekund.
          </span>
        )}
        {(onCancel || stillRunningId) && (
          <div className={styles.actions}>
            {onCancel && (
              <Button
                variant="ghost"
                size="md"
                leftIcon={X}
                onClick={onCancel}
                isLoading={cancelling}
              >
                Anuluj
              </Button>
            )}
            {stillRunningId && (
              <LinkButton
                to={`/analysis-result/${stillRunningId}`}
                variant="primary"
                size="md"
                rightIcon={ArrowRight}
              >
                Przejdź do analizy
              </LinkButton>
            )}
          </div>
        )}
      </div>
    </>
  )
}
