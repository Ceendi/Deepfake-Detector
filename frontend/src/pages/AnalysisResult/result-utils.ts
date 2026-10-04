import type { AnalysisType, AudioSegmentPrediction, SourceDetails, Verdict } from '@/api/types'

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

// Czas względny od `iso` do teraz ('przed chwilą' / '3 min temu' / '2 godz. temu'); starsze niż doba
// → pełna data. Używane w nagłówku dla analiz w toku ('rozpoczęto: …'). Liczone przy renderze.
export function relativeTime(iso: string): string {
  const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000))
  if (s < 45) return 'przed chwilą'
  const m = Math.round(s / 60)
  if (m < 60) return `${m} min temu`
  const h = Math.round(m / 60)
  if (h < 24) return `${h} godz. temu`
  return formatDateTime(iso)
}

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

// Shared decision rule and confidence match the persisted orchestrator result.
export interface SourceOutcome {
  verdict: Verdict
  confidence: number
}
export function sourceOutcome(prob: number): SourceOutcome {
  return {
    verdict: prob > 0.5 ? 'FAKE' : 'REAL',
    confidence: Number((Math.abs(prob - 0.5) * 2).toFixed(4)),
  }
}

export function hasAudioScoreContract(metadata: Record<string, unknown> | undefined): boolean {
  return metadata?.score_contract === 'audio-threshold-v1'
}

export function audioOutcome(
  prob: number,
  details: SourceDetails | undefined,
): SourceOutcome | null {
  if (hasAudioScoreContract(details?.metadata)) return sourceOutcome(prob)
  // Historical scores used model thresholds. Preserve the recorded source verdict;
  // never infer one by comparing an unversioned raw score to the shared boundary.
  if (details?.verdict === 'FAKE' || details?.verdict === 'REAL') {
    return { verdict: details.verdict, confidence: details.confidence ?? 0 }
  }
  return null
}

// Wyciąga segmenty audio z surowego, wolnoformatowego `metadata`. Defensywnie: bierze tylko wpisy
// z poprawnymi liczbami i sortuje po start_time. Zwraca [] gdy brak / zły kształt.
export function parseAudioSegments(
  metadata: Record<string, unknown> | undefined,
): AudioSegmentPrediction[] {
  if (!hasAudioScoreContract(metadata)) return []
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
