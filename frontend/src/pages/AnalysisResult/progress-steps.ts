import type { AnalysisType } from '@/api/types'

export type Source = 'video' | 'audio'
export type StepState = 'done' | 'active' | 'pending'

export interface SourceProgress {
  progress: number // 0..100
  stage: string
}

interface StepDef {
  key: string
  label: string
}

const VIDEO_STEPS: StepDef[] = [
  { key: 'LOADING', label: 'Wczytywanie modelu' },
  { key: 'PREPROCESSING', label: 'Przetwarzanie klatek' },
  { key: 'INFERENCE', label: 'Analiza modelu wideo' },
  { key: 'POSTPROCESSING', label: 'Generowanie map Grad-CAM' },
]
const AUDIO_STEPS: StepDef[] = [
  { key: 'LOADING', label: 'Wczytywanie modelu' },
  { key: 'PREPROCESSING', label: 'Przetwarzanie wstępne' },
  { key: 'INFERENCE', label: 'Analiza fragmentów' },
  { key: 'POSTPROCESSING', label: 'Finalizacja' },
]
const STEPS: Record<Source, StepDef[]> = { video: VIDEO_STEPS, audio: AUDIO_STEPS }

const STAGE_INDEX: Record<Source, Record<string, number>> = {
  video: { LOADING: 0, PREPROCESSING: 1, INFERENCE: 2, POSTPROCESSING: 3 },
  audio: {
    LOADING: 0,
    PREPROCESSING: 1,
    INFERENCE: 2,
    POSTPROCESSING: 3,
  },
}

const SOURCE_LABEL: Record<Source, string> = { video: 'Ścieżka wideo', audio: 'Ścieżka audio' }
export const sourceLabel = (s: Source): string => SOURCE_LABEL[s]

export function sourcesForType(type: AnalysisType): Source[] {
  if (type === 'AUDIO') return ['audio']
  if (type === 'VIDEO') return ['video']
  return ['video', 'audio']
}

function currentIndex(source: Source, sp: SourceProgress | undefined): number {
  if (!sp) return -1
  const known = STAGE_INDEX[source][sp.stage]
  if (known != null) return known
  const len = STEPS[source].length
  return Math.min(len - 1, Math.floor((sp.progress / 100) * len))
}

export interface Step {
  key: string
  label: string
  state: StepState
}

export function buildSteps(source: Source, sp: SourceProgress | undefined): Step[] {
  const cur = currentIndex(source, sp)
  const atFull = sp != null && sp.progress >= 100
  return STEPS[source].map((s, i) => {
    let state: StepState
    if (i < cur) state = 'done'
    else if (i === cur) state = atFull ? 'done' : 'active'
    else state = 'pending'
    return { key: s.key, label: s.label, state }
  })
}

export function overallProgress(
  sources: Source[],
  by: Partial<Record<Source, SourceProgress>>,
): number | null {
  if (!sources.some((s) => by[s] != null)) return null
  const sum = sources.reduce((acc, s) => acc + (by[s]?.progress ?? 0), 0)
  return Math.round(sum / sources.length)
}

export function activeStageLabel(groups: { steps: Step[] }[]): string | null {
  for (const g of groups) {
    const active = g.steps.find((s) => s.state === 'active')
    if (active) return active.label
  }
  return null
}
