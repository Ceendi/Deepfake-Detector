import type { CSSProperties } from 'react'

import type { AudioSegmentPrediction } from '@/api/types'
import { Card, CardBody } from '@/components/ui/Card/Card'

import { parseAudioSegments, riskColor } from '../result-utils'

import styles from '../AnalysisResult.module.css'

// Ciągły przebieg mowy (segmenty stykające się / nachodzące) → jeden pasek z PŁYNNYM gradientem.
// Cisza (luka czasowa) rozbija przebieg na osobne paski, więc kolory nie mieszają się przez przerwy.
interface Run {
  startPct: number
  widthPct: number
  background: string
}

function buildRuns(segments: AudioSegmentPrediction[], duration: number): Run[] {
  const runs: Run[] = []
  let group: AudioSegmentPrediction[] = []
  let groupEnd = Number.NEGATIVE_INFINITY

  const flush = () => {
    if (group.length === 0) return
    const start = group[0].start_time
    const end = Math.max(...group.map((s) => s.end_time))
    const span = end - start || 1

    // Stop koloru w środku każdego segmentu (lokalne % grupy); CSS interpoluje między nimi gładko.
    const background =
      group.length === 1
        ? riskColor(group[0].prob_fake)
        : `linear-gradient(90deg, ${group
            .map((s) => {
              const mid = (s.start_time + s.end_time) / 2
              const local = ((mid - start) / span) * 100
              return `${riskColor(s.prob_fake)} ${local.toFixed(1)}%`
            })
            .join(', ')})`

    runs.push({
      startPct: (start / duration) * 100,
      widthPct: (span / duration) * 100,
      background,
    })
    group = []
    groupEnd = Number.NEGATIVE_INFINITY
  }

  for (const s of segments) {
    if (group.length > 0 && s.start_time > groupEnd) flush() // luka = cisza → nowy pasek
    group.push(s)
    groupEnd = Math.max(groupEnd, s.end_time)
  }
  flush()
  return runs
}

// Oś czasu ryzyka audio — pasek-heatmapa z `details.audio.metadata.segment_predictions`.
// X = czas, kolor = prob_fake (zielony→czerwony), płynnie przechodzący w obrębie ciągłej mowy.
export function AudioTimeline({ metadata }: { metadata: Record<string, unknown> | undefined }) {
  const segments = parseAudioSegments(metadata)
  if (segments.length === 0) return null

  const durationMeta =
    typeof metadata?.duration_seconds === 'number' ? metadata.duration_seconds : 0
  const duration = Math.max(durationMeta, ...segments.map((s) => s.end_time))
  if (duration <= 0) return null

  const runs = buildRuns(segments, duration)

  // Fragment o najwyższym ryzyku — wyróżniony w podpisie, bo przy materiale REAL kolory są niemal
  // jednolicie zielone i pojedynczy pik trudno wypatrzeć samym okiem.
  const peak = segments.reduce((max, s) => (s.prob_fake > max.prob_fake ? s : max), segments[0])
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => f * duration)

  return (
    <section className={styles.section}>
      <span className={styles.sectionLabel}>Ryzyko audio w czasie</span>
      <Card>
        <CardBody>
          <p className={styles.blockText}>
            Każdy fragment mowy oceniany jest osobno — kolor odpowiada prawdopodobieństwu, że dany
            fragment został wygenerowany sztucznie. Przerwy oznaczają fragmenty bez mowy.
          </p>

          <div className={styles.legend}>
            <span className={styles.legendText}>niskie ryzyko</span>
            <span className={styles.riskLegendBar} aria-hidden="true" />
            <span className={styles.legendText}>wysokie ryzyko</span>
          </div>

          <div
            className={styles.audioTrack}
            role="img"
            aria-label={`Oś czasu ryzyka audio: ${segments.length} fragmentów, najwyższe ryzyko ${Math.round(
              peak.prob_fake * 100,
            )}% w ${peak.start_time}–${peak.end_time} s`}
          >
            {/* Warstwa kolorów — ujawniana lewo→prawo przy wejściu */}
            <div className={styles.audioRuns}>
              {runs.map((run, i) => (
                <span
                  key={i}
                  className={styles.audioRun}
                  style={
                    {
                      left: `${run.startPct}%`,
                      width: `${run.widthPct}%`,
                      background: run.background,
                    } as CSSProperties
                  }
                />
              ))}
            </div>

            {/* Warstwa interakcji — przezroczyste pola per segment, dają tooltip z czasem i % */}
            <div className={styles.audioHits}>
              {segments.map((s, i) => (
                <span
                  key={i}
                  className={styles.audioHit}
                  style={{
                    left: `${(s.start_time / duration) * 100}%`,
                    width: `${((s.end_time - s.start_time) / duration) * 100}%`,
                  }}
                  title={`${s.start_time}–${s.end_time} s · ${Math.round(s.prob_fake * 100)}% ryzyka`}
                />
              ))}
            </div>
          </div>

          <div className={styles.audioAxis} aria-hidden="true">
            {ticks.map((t, i) => (
              <span
                key={i}
                className={styles.audioTick}
                style={{ left: `${(t / duration) * 100}%` }}
              >
                {Math.round(t)} s
              </span>
            ))}
          </div>

          <p className={styles.audioPeak}>
            <span
              className={styles.peakDot}
              style={{ background: riskColor(peak.prob_fake) }}
              aria-hidden="true"
            />
            <span>Największe ryzyko fragmentu:</span>
            <strong className={styles.peakValue}>{Math.round(peak.prob_fake * 100)}%</strong>
            <span>{`w ${peak.start_time}–${peak.end_time} s`}</span>
          </p>
        </CardBody>
      </Card>
    </section>
  )
}
