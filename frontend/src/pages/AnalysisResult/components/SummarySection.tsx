import { Check, TriangleAlert, Info } from 'lucide-react'
import { clsx } from 'clsx'

import type { Analysis, Verdict } from '@/api/types'
import { Card, CardBody } from '@/components/ui/Card/Card'

import { sourceOutcome } from '../result-utils'

import styles from '../AnalysisResult.module.css'

type FindingTone = 'success' | 'warning' | 'muted'
interface Finding {
  tone: FindingTone
  text: string
}

// Wnioski to placeholder — realne uzasadnienia dojdą z backendu. Trzymane PER ŹRÓDŁO, żeby analiza
// samego audio nie pokazywała wniosków o wideo (i odwrotnie); ton/treść wg werdyktu tego źródła.
const VIDEO_FINDING: Record<Verdict, Finding> = {
  FAKE: {
    tone: 'warning',
    text: 'Obraz: twarz wykazuje cechy sztucznej modyfikacji, najsilniejsze w okolicy twarzy i ust.',
  },
  REAL: { tone: 'success', text: 'Obraz: twarz nie wykazuje oznak modyfikacji.' },
}
const AUDIO_FINDING: Record<Verdict, Finding> = {
  FAKE: {
    tone: 'warning',
    text: 'Dźwięk: głos wykazuje cechy mowy generowanej lub sklonowanej sztucznie.',
  },
  REAL: { tone: 'success', text: 'Dźwięk: głos nie wykazuje oznak sztucznego generowania.' },
}
const RECOMMENDATION: Record<Verdict, Finding> = {
  FAKE: {
    tone: 'muted',
    text: 'Zaleca się weryfikację oryginalnego źródła przed publikacją lub dalszym udostępnianiem materiału.',
  },
  REAL: {
    tone: 'muted',
    text: 'W przypadku materiałów wrażliwych zaleca się dodatkowe potwierdzenie źródła.',
  },
}

const ICON: Record<FindingTone, typeof Check> = {
  success: Check,
  warning: TriangleAlert,
  muted: Info,
}

export function SummarySection({ analysis }: { analysis: Analysis }) {
  const { videoProb, audioProb } = analysis
  const verdict = analysis.verdict ?? 'REAL'

  // Wnioski tylko dla realnie przeanalizowanych źródeł (videoProb/audioProb != null);
  // rekomendacja zawsze, wg werdyktu ogólnego.
  const findings: Finding[] = []
  if (videoProb != null) findings.push(VIDEO_FINDING[sourceOutcome(videoProb).verdict])
  if (audioProb != null) findings.push(AUDIO_FINDING[sourceOutcome(audioProb).verdict])
  findings.push(RECOMMENDATION[verdict])

  // Modele i metadane — realne z details; każdy renderowany tylko dla obecnego źródła.
  const videoModel = analysis.details?.video?.modelVersion
  const audioModel = analysis.details?.audio?.modelVersion
  const frames = analysis.details?.video?.metadata?.frames_sampled
  const framesText = typeof frames === 'number' ? frames : null

  const sources: string[] = []
  if (videoProb != null) sources.push('wideo')
  if (audioProb != null) sources.push('audio')
  const sourcesText = sources.join(' i ') || 'mediów'

  return (
    <section className={styles.section}>
      <span className={styles.sectionLabel}>Podsumowanie</span>
      <Card>
        <CardBody>
          <p className={styles.summaryText}>
            Model ocenił materiał na podstawie ścieżki {sourcesText}. Poniżej najważniejsze sygnały,
            które wpłynęły na werdykt.
          </p>

          <ul className={styles.findings}>
            {findings.map((finding, i) => {
              const Icon = ICON[finding.tone]
              return (
                <li className={styles.finding} key={i}>
                  <Icon
                    className={clsx(styles.findingIcon, styles[`icon_${finding.tone}`])}
                    size={18}
                    strokeWidth={2.4}
                    aria-hidden="true"
                  />
                  <span>{finding.text}</span>
                </li>
              )
            })}
          </ul>

          <div className={styles.divider} />

          <div className={styles.summaryMeta}>
            {videoModel && <span>Model wideo: {videoModel}</span>}
            {audioModel && <span>Model audio: {audioModel}</span>}
            {framesText != null && <span>Klatki: {framesText}</span>}
            <span>ID analizy: {analysis.id.slice(0, 8)}</span>
          </div>
        </CardBody>
      </Card>
    </section>
  )
}
