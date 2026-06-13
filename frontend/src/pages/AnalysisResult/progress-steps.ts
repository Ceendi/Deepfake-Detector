// Model kroków ekranu „w toku" (LiveProgress) — czysta logika, bez Reacta (stąd testowalna).
// SSE daje per źródło tylko { progress 0..100, stage }. Z tego wyliczamy listę kroków pipeline'u
// z jednego z trzech stanów (done/active/pending) oraz postęp całości. UWAGA: liczby klatek NIE ma
// — nasz AnalysisProgressEvent jej nie niesie (świadomie, kontrakt: amqp-messages.md §Progress).
import type { AnalysisType } from '@/api/types'

export type Source = 'video' | 'audio'
export type StepState = 'done' | 'active' | 'pending'

// Snapshot ostatniego progressu źródła (z agregacji SSE).
export interface SourceProgress {
  progress: number // 0..100
  stage: string // surowy stage z detektora
}

interface StepDef {
  key: string
  label: string
}

// Kroki per źródło = realne stage'e naszych detektorów (NIE wymyślone „klatki/twarze" z makiety).
// Kolejność = kolejność emisji. Etykiety opisowe, bo na ekranie stoją obok siebie (video + audio).
const VIDEO_STEPS: StepDef[] = [
  { key: 'LOADING', label: 'Wczytywanie modelu' },
  { key: 'PREPROCESSING', label: 'Przetwarzanie klatek' },
  { key: 'INFERENCE', label: 'Analiza modelu wideo' },
  { key: 'POSTPROCESSING', label: 'Generowanie map Grad-CAM' },
]
const AUDIO_STEPS: StepDef[] = [
  { key: 'EXTRACTING_AUDIO', label: 'Ekstrakcja ścieżki audio' },
  { key: 'PREPROCESSING_AUDIO', label: 'Przetwarzanie wstępne' },
  { key: 'ANALYZING_SEGMENTS', label: 'Analiza fragmentów' },
  { key: 'GENERATING_HEATMAP', label: 'Generowanie mapy cieplnej' },
  { key: 'ANALYSIS_COMPLETED', label: 'Finalizacja' },
]
const STEPS: Record<Source, StepDef[]> = { video: VIDEO_STEPS, audio: AUDIO_STEPS }

// Surowy stage → indeks kroku. Tolerancyjny na DWA słowniki: audio-detektor łamie zamknięty zbiór
// z kontraktu (opisowe nazwy), ale committed audio-inference potrafi emitować też zamknięty zbiór
// (LOADING/…/POSTPROCESSING). Mapujemy oba na te same kroki, żeby pasek nie „gubił" pozycji.
const STAGE_INDEX: Record<Source, Record<string, number>> = {
  video: { LOADING: 0, PREPROCESSING: 1, INFERENCE: 2, POSTPROCESSING: 3 },
  audio: {
    EXTRACTING_AUDIO: 0,
    PREPROCESSING_AUDIO: 1,
    ANALYZING_SEGMENTS: 2,
    GENERATING_HEATMAP: 3,
    ANALYSIS_COMPLETED: 4,
    // awaryjnie — gdyby audio jechało zamkniętym zbiorem:
    LOADING: 0,
    PREPROCESSING: 1,
    INFERENCE: 2,
    POSTPROCESSING: 3,
  },
}

const SOURCE_LABEL: Record<Source, string> = { video: 'Ścieżka wideo', audio: 'Ścieżka audio' }
export const sourceLabel = (s: Source): string => SOURCE_LABEL[s]

// Które tory pokazać. FULL → rozbicie na wideo + audio; VIDEO/AUDIO → pojedynczy tor.
export function sourcesForType(type: AnalysisType): Source[] {
  if (type === 'AUDIO') return ['audio']
  if (type === 'VIDEO') return ['video']
  return ['video', 'audio']
}

// Indeks aktualnego kroku źródła; -1 gdy brak jeszcze zdarzenia. Nieznany stage → szacujemy z progresu
// (fail-safe, gdyby detektor dorzucił nazwę spoza obu słowników).
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

// Lista kroków źródła ze stanami. Krok przed bieżącym = done; bieżący = active (lub done przy 100%);
// dalsze = pending. Brak zdarzenia → wszystko pending.
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

// Postęp całości = średnia ze źródeł danego typu (brak zdarzenia liczy się jako 0%). null dopóki nic
// nie ruszyło (PENDING bez progressu) → pasek nieokreślony, bez procentu.
export function overallProgress(
  sources: Source[],
  by: Partial<Record<Source, SourceProgress>>,
): number | null {
  if (!sources.some((s) => by[s] != null)) return null
  const sum = sources.reduce((acc, s) => acc + (by[s]?.progress ?? 0), 0)
  return Math.round(sum / sources.length)
}

// Etykieta bieżącego kroku (linia mono pod tytułem) — pierwszy aktywny krok, wideo przed audio.
export function activeStageLabel(groups: { steps: Step[] }[]): string | null {
  for (const g of groups) {
    const active = g.steps.find((s) => s.state === 'active')
    if (active) return active.label
  }
  return null
}
