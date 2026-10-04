import { Video, AudioLines } from 'lucide-react'

import type { Analysis, Verdict } from '@/api/types'
import { Card, CardBody } from '@/components/ui/Card/Card'
import { Badge } from '@/components/ui/Badge/Badge'
import { ProgressBar } from '@/components/ui/ProgressBar/ProgressBar'

import { audioOutcome, hasAudioScoreContract, sourceOutcome } from '../result-utils'

import styles from '../AnalysisResult.module.css'

type Source = 'video' | 'audio'

// Opisy z makiety — placeholder zależny od werdyktu źródła; realne uzasadnienie dojdzie z detektora.
const DESC: Record<Source, Record<Verdict, string>> = {
  video: {
    FAKE: 'Obraz twarzy wykazuje cechy sztucznej modyfikacji, najsilniejsze w okolicy twarzy i ust.',
    REAL: 'Obraz twarzy nie wykazuje oznak modyfikacji i pozostaje spójny pomiędzy klatkami.',
  },
  audio: {
    FAKE: 'Ścieżka dźwiękowa wykazuje cechy mowy generowanej lub sklonowanej sztucznie.',
    REAL: 'Ścieżka dźwiękowa nie wykazuje oznak sztucznego generowania mowy.',
  },
}

function ModalityCard({
  source,
  prob,
  analysis,
}: {
  source: Source
  prob: number
  analysis: Analysis
}) {
  const isVideo = source === 'video'
  const Icon = isVideo ? Video : AudioLines
  const legacyAudio = !isVideo && !hasAudioScoreContract(analysis.details?.audio?.metadata)
  const outcome = isVideo ? sourceOutcome(prob) : audioOutcome(prob, analysis.details?.audio)
  const verdict = outcome?.verdict
  const isFake = verdict === 'FAKE'

  return (
    <Card>
      <CardBody>
        <div className={styles.modalityHead}>
          <span className={styles.modalityTitle}>
            <Icon size={18} strokeWidth={2} aria-hidden="true" />
            {isVideo ? 'Ścieżka wideo' : 'Ścieżka audio'}
          </span>
          <Badge variant={isFake ? 'danger' : 'success'} size="sm" soft>
            {verdict ?? 'Brak werdyktu źródła'}
          </Badge>
        </div>

        <ProgressBar
          label={
            isVideo
              ? 'Prawdopodobieństwo fake'
              : legacyAudio
                ? 'Surowy wynik audio (archiwalny)'
                : 'Wynik audio na wspólnej skali'
          }
          value={prob * 100}
          showValue
          tone={isFake ? 'danger' : 'success'}
        />

        {verdict && <p className={styles.modalityDesc}>{DESC[source][verdict]}</p>}
        {legacyAudio && (
          <p className={styles.modalityDesc}>
            Wynik archiwalny: zachowano pierwotne werdykty i wynik zbiorczy. Surowy wynik audio ma
            próg właściwy dla modelu; oś czasu na wspólnej skali jest niedostępna.
          </p>
        )}
      </CardBody>
    </Card>
  )
}

export function ModalitySection({ analysis }: { analysis: Analysis }) {
  const cards = []
  if (analysis.videoProb != null) {
    cards.push(
      <ModalityCard key="video" source="video" prob={analysis.videoProb} analysis={analysis} />,
    )
  }
  if (analysis.audioProb != null) {
    cards.push(
      <ModalityCard key="audio" source="audio" prob={analysis.audioProb} analysis={analysis} />,
    )
  }
  if (cards.length === 0) return null

  return (
    <section className={styles.section}>
      <span className={styles.sectionLabel}>Analiza modalności</span>
      <div className={styles.modalityGrid}>{cards}</div>
    </section>
  )
}
