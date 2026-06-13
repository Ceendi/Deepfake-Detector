import { Fragment, useState } from 'react'

import { Video, AudioLines, Check, Circle, LoaderCircle } from 'lucide-react'
import { clsx } from 'clsx'

import { cancelAnalysis } from '@/api/analysis'
import type { Analysis } from '@/api/types'
import { Button } from '@/components/ui/Button/Button'
import { ProgressBar } from '@/components/ui/ProgressBar/ProgressBar'

import { useAnalysisStream } from '../use-analysis-stream'
import {
  sourcesForType,
  buildSteps,
  overallProgress,
  activeStageLabel,
  sourceLabel,
  type Step,
} from '../progress-steps'

import styles from '../AnalysisResult.module.css'

// Ekran „w toku" na żywo (PENDING/PROCESSING). Odwzorowuje makietę: badge NA ŻYWO → tytuł → linia
// bieżącego etapu → pasek całości + % → checklista kroków → „Anuluj analizę". Dla FULL kroki są
// rozbite na tor wideo i audio; dla VIDEO/AUDIO to jedna lista. `onSettled` = refetch w rodzicu.
export function LiveProgress({
  analysis,
  onSettled,
}: {
  analysis: Analysis
  onSettled: () => void
}) {
  const bySource = useAnalysisStream(analysis.id, () => onSettled())
  const [cancelling, setCancelling] = useState(false)

  const sources = sourcesForType(analysis.type)
  const isSplit = sources.length > 1
  const groups = sources.map((source) => ({ source, steps: buildSteps(source, bySource[source]) }))

  const overall = overallProgress(sources, bySource)
  const subtitle =
    activeStageLabel(groups) ?? (overall == null ? 'Oczekiwanie w kolejce…' : 'Kończenie analizy…')

  async function handleCancel() {
    setCancelling(true)
    try {
      await cancelAnalysis(analysis.id) // DELETE → CANCELLED; 409 = już skończona (ignoruj)
    } catch {
      /* idempotentne / już terminalna */
    }
    onSettled() // refetch → strona pokaże ekran „anulowana"
  }

  return (
    <div className={styles.liveCard}>
      <span className={styles.liveBadge}>
        <span className={styles.liveDot} aria-hidden="true" />
        Na żywo
      </span>

      <h2 className={styles.liveTitle}>Analiza w toku…</h2>
      <p className={styles.liveStage}>{subtitle}</p>

      <div className={styles.liveBarWrap}>
        <ProgressBar value={overall ?? undefined} aria-label="Postęp analizy" />
        {overall != null && <span className={styles.livePercent}>{overall}%</span>}
      </div>

      <div className={styles.steps} role="list" aria-label="Etapy analizy">
        {groups.map((g) => (
          <Fragment key={g.source}>
            {isSplit && (
              <p className={styles.stepGroup}>
                {g.source === 'video' ? (
                  <Video size={14} strokeWidth={2} aria-hidden="true" />
                ) : (
                  <AudioLines size={14} strokeWidth={2} aria-hidden="true" />
                )}
                {sourceLabel(g.source)}
              </p>
            )}
            {g.steps.map((step) => (
              <StepRow key={step.key} step={step} />
            ))}
          </Fragment>
        ))}
      </div>

      <div className={styles.liveActions}>
        <Button variant="ghost" size="md" onClick={handleCancel} isLoading={cancelling}>
          Anuluj analizę
        </Button>
      </div>
    </div>
  )
}

function StepRow({ step }: { step: Step }) {
  return (
    <div
      role="listitem"
      data-state={step.state}
      aria-current={step.state === 'active' ? 'step' : undefined}
      className={clsx(styles.step, step.state === 'active' && styles.stepActive)}
    >
      <span className={styles.stepIcon} data-state={step.state} aria-hidden="true">
        {step.state === 'done' ? (
          <Check size={14} strokeWidth={3} />
        ) : step.state === 'active' ? (
          <LoaderCircle size={20} strokeWidth={2.25} className={styles.spin} />
        ) : (
          <Circle size={20} strokeWidth={2} />
        )}
      </span>
      <span className={styles.stepLabel}>{step.label}</span>
    </div>
  )
}
