import { TriangleAlert, ShieldCheck } from 'lucide-react'

import type { Verdict } from '@/api/types'
import { RadialGauge } from '@/components/ui/RadialGauge/RadialGauge'

import styles from '../AnalysisResult.module.css'

// Treść narracyjna z makiety — placeholder per werdykt; realne uzasadnienie dojdzie z backendu.
const COPY: Record<Verdict, { title: string; text: string }> = {
  FAKE: {
    title: 'Wykryto manipulację',
    text: 'Materiał wykazuje cechy sztucznej ingerencji w obraz lub dźwięk. Zaleca się ostrożność oraz weryfikację źródła przed dalszą publikacją.',
  },
  REAL: {
    title: 'Nie wykryto manipulacji',
    text: 'Nie stwierdzono oznak przeróbki ani sztucznego generowania materiału. W przypadku materiałów wrażliwych zaleca się dodatkowe potwierdzenie źródła.',
  },
}

export function VerdictHero({ verdict, confidence }: { verdict: Verdict; confidence: number }) {
  const isFake = verdict === 'FAKE'
  const copy = COPY[verdict]

  return (
    <section className={styles.hero} data-tone={isFake ? 'fake' : 'real'}>
      <div className={styles.heroMain}>
        <span className={styles.verdictPill}>
          {isFake ? (
            <TriangleAlert size={26} strokeWidth={2.4} aria-hidden="true" />
          ) : (
            <ShieldCheck size={26} strokeWidth={2.4} aria-hidden="true" />
          )}
          {verdict}
        </span>
        <h2 className={styles.heroTitle}>{copy.title}</h2>
        <p className={styles.heroText}>{copy.text}</p>
      </div>

      <div className={styles.heroAside}>
        <RadialGauge
          value={confidence * 100}
          tone={isFake ? 'danger' : 'success'}
          label="pewność werdyktu"
          size={150}
        />
      </div>
    </section>
  )
}
