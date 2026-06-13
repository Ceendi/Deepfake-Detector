import type { AnalysisType, AudioSegmentPrediction, Verdict } from '@/api/types'

const dayFmt = new Intl.DateTimeFormat('pl-PL', { day: 'numeric', month: 'long', year: 'numeric' })
const timeFmt = new Intl.DateTimeFormat('pl-PL', { hour: '2-digit', minute: '2-digit' })

export function formatDateTime(iso: string): string {
  const d = new Date(iso)
  return `${dayFmt.format(d)}, ${timeFmt.format(d)}`
}

// Nazwa do wyświetlenia — fileKey ma postać '{uuid}_oryginalna-nazwa.mp4'; obcinamy prefiks UUID.
export function displayName(fileKey: string): string {
  return fileKey.replace(/^[0-9a-fA-F-]{36}_/, '') || fileKey
}

const TYPE_LABEL: Record<AnalysisType, string> = {
  VIDEO: 'VIDEO',
  AUDIO: 'AUDIO',
  FULL: 'VIDEO + AUDIO',
}
export const typeLabel = (type: AnalysisType): string => TYPE_LABEL[type]

// Czas analizy = updatedAt − createdAt (sekundy → '30 s' / '1 min 5 s'). Przybliżenie do realnego
// czasu wykonania; dokładny pomiar dojdzie z backendu.
export function analysisDuration(createdAt: string, updatedAt: string): string {
  const ms = new Date(updatedAt).getTime() - new Date(createdAt).getTime()
  const s = Math.max(0, Math.round(ms / 1000))
  if (s < 60) return `${s} s`
  const m = Math.floor(s / 60)
  const rem = s % 60
  return rem ? `${m} min ${rem} s` : `${m} min`
}

// Per-źródło: videoProb/audioProb to P(FAKE). Werdykt i „pewność" liczymy względem progu 0.5
// (pewność zawsze W KIERUNKU werdyktu: dla REAL pokazujemy 1 − prob).
export interface SourceOutcome {
  verdict: Verdict
  confidence: number // 0..1
}
export function sourceOutcome(prob: number): SourceOutcome {
  const verdict: Verdict = prob >= 0.5 ? 'FAKE' : 'REAL'
  return { verdict, confidence: verdict === 'FAKE' ? prob : 1 - prob }
}

// Wyciąga segmenty audio z surowego, wolnoformatowego `metadata`. Defensywnie: bierze tylko wpisy
// z poprawnymi liczbami i sortuje po start_time. Zwraca [] gdy brak / zły kształt.
export function parseAudioSegments(
  metadata: Record<string, unknown> | undefined,
): AudioSegmentPrediction[] {
  const raw = metadata?.segment_predictions
  if (!Array.isArray(raw)) return []

  const segments: AudioSegmentPrediction[] = []
  for (const item of raw) {
    if (item && typeof item === 'object') {
      const o = item as Record<string, unknown>
      if (
        typeof o.start_time === 'number' &&
        typeof o.end_time === 'number' &&
        typeof o.prob_fake === 'number'
      ) {
        segments.push({ start_time: o.start_time, end_time: o.end_time, prob_fake: o.prob_fake })
      }
    }
  }
  return segments.sort((a, b) => a.start_time - b.start_time)
}

// Skala ryzyka 0..1 → kolor (HSL): zielony (niskie) → bursztyn → czerwony (wysokie). Liczona w JS,
// bo to kolor sterowany danymi (poza tokenami, jak gradient legendy Grad-CAM).
export function riskColor(prob: number): string {
  const clamped = Math.min(1, Math.max(0, prob))
  const hue = Math.round(130 - clamped * 130) // 130 = zielony, 0 = czerwony
  return `hsl(${hue} 70% 45%)`
}
