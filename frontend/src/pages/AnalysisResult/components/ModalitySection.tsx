import { Video, AudioLines } from 'lucide-react'

import type { Analysis, Verdict } from '@/api/types'
import { Card, CardBody } from '@/components/ui/Card/Card'
import { Badge } from '@/components/ui/Badge/Badge'
import { ProgressBar } from '@/components/ui/ProgressBar/ProgressBar'

import { sourceOutcome } from '../result-utils'

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

function ModalityCard({ source, prob }: { source: Source; prob: number }) {
  const isVideo = source === 'video'
  const Icon = isVideo ? Video : AudioLines
  const { verdict, confidence } = sourceOutcome(prob)
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
            {verdict}
          </Badge>
        </div>

        <ProgressBar
          label="Pewność"
          value={confidence * 100}
          showValue
          tone={isFake ? 'danger' : 'success'}
        />

        <p className={styles.modalityDesc}>{DESC[source][verdict]}</p>
      </CardBody>
    </Card>
  )
}

export function ModalitySection({ analysis }: { analysis: Analysis }) {
  const cards = []
  if (analysis.videoProb != null) {
    cards.push(<ModalityCard key="video" source="video" prob={analysis.videoProb} />)
  }
  if (analysis.audioProb != null) {
    cards.push(<ModalityCard key="audio" source="audio" prob={analysis.audioProb} />)
  }
  if (cards.length === 0) return null

  return (
    <section className={styles.section}>
      <span className={styles.sectionLabel}>Analiza modalności</span>
      <div className={styles.modalityGrid}>{cards}</div>
    </section>
  )
}
