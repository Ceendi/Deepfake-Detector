import { X } from 'lucide-react'

import { Button } from '@/components/ui/Button/Button'
import { ProgressBar } from '@/components/ui/ProgressBar/ProgressBar'

import { isVideoFile } from '../upload-utils'
import type { ProgressBySource } from '../use-analysis-flow'
import styles from '../Upload.module.css'

interface AnalyzingStateProps {
  file: File // tylko do ustalenia źródeł (wideo → video+audio, audio → audio)
  bySource: ProgressBySource
  onCancel?: () => void // brak = faza `starting` (analiza jeszcze nieanulowalna)
}

const SOURCE_LABEL = { video: 'Wideo', audio: 'Audio' } as const

// Mapa stage (surowy string z SSE) → przyjazny tekst. Renderowana z prawej etykiety paska
// jako „{źródło} · {stageLabel}"; nieznany stage → fallback na sam label źródła (patrz niżej).
// Pokrywamy DWA zestawy nazw, bo na wire są oba:
//  • audio (faktyczne komunikaty z audio-detektora, message.txt) — opisowe nazwy,
//  • zamknięty zbiór z kontraktu (docs/contracts/amqp-messages.md §Progress) — emituje go
//    video-detektor (LOADING/INFERENCE) i committed audio-inference.
// UWAGA: audio-detektor łamie kontrakt (zbiór miał być zamknięty) — do uzgodnienia z Osobą 4
// (albo detektor wraca do zamkniętego zbioru, albo aktualizujemy kontrakt + ten komentarz).
const STAGE_LABELS: Record<string, string> = {
  // — zamknięty zbiór z kontraktu (video + ewentualnie audio po realignmencie) —
  LOADING: 'Wczytywanie',
  PREPROCESSING: 'Przetwarzanie wstępne',
  INFERENCE: 'Analiza',
  POSTPROCESSING: 'Finalizacja',
  // — audio-detektor (aktualne nazwy na wire) —
  EXTRACTING_AUDIO: 'Ekstrakcja audio',
  PREPROCESSING_AUDIO: 'Przetwarzanie wstępne',
  ANALYZING_SEGMENTS: 'Analiza fragmentów',
  GENERATING_HEATMAP: 'Generowanie mapy cieplnej',
  ANALYSIS_COMPLETED: 'Finalizacja',
}

// Sekcja postępu + stopka fazy „analyzing"/„starting". Media renderuje wspólna karta w Upload.tsx.
// Dla wideo dwa paski (video+audio), dla audio jeden; źródła znamy z typu pliku, więc paski są od razu.
export function AnalyzingState({ file, bySource, onCancel }: AnalyzingStateProps) {
  const sources = isVideoFile(file) ? (['video', 'audio'] as const) : (['audio'] as const)

  return (
    <>
      <div className={styles.progressSection}>
        {sources.map((source) => {
          const sp = bySource[source]
          const stageLabel = sp ? STAGE_LABELS[sp.stage] : undefined
          return (
            <ProgressBar
              key={source}
              value={sp?.progress} // brak danych → tryb nieokreślony
              label={stageLabel ? `${SOURCE_LABEL[source]} · ${stageLabel}` : SOURCE_LABEL[source]}
              showValue
            />
          )
        })}
      </div>

      <div className={styles.footer}>
        <span className={styles.footerNote}>
          Analiza w toku — to zwykle kilka–kilkanaście sekund.
        </span>
        {onCancel && (
          <div className={styles.actions}>
            <Button variant="ghost" size="md" leftIcon={X} onClick={onCancel}>
              Anuluj
            </Button>
          </div>
        )}
      </div>
    </>
  )
}
