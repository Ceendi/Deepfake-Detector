import { useEffect, useState, type ReactNode } from 'react'

import { Link, useParams } from 'react-router-dom'

import { getAnalysis } from '@/api/analysis'
import { ApiError } from '@/api/errors'
import type { Analysis } from '@/api/types'

import { Spinner } from '@/components/ui/Spinner/Spinner'
import { Alert } from '@/components/ui/Alert/Alert'
import { Badge } from '@/components/ui/Badge/Badge'

import styles from './AnalysisResult.module.css'

type LoadState = 'loading' | 'ready' | 'notfound' | 'error'

const pct = (v: number) => `${Math.round(v * 100)}%`

// MEGA bazowy podgląd wyniku — tylko surowe pola z GET /api/analysis/{id}. Pełna wizualizacja
// (werdykt, confidence bar, Grad-CAM, raport PDF, live-stream gdy PROCESSING) dochodzi osobno.
export default function AnalysisResult() {
  const { id } = useParams<{ id: string }>()
  const [state, setState] = useState<LoadState>('loading')
  const [analysis, setAnalysis] = useState<Analysis | null>(null)

  useEffect(() => {
    if (!id) return
    const controller = new AbortController()

    getAnalysis(id, controller.signal)
      .then((a) => {
        setAnalysis(a)
        setState('ready')
      })
      .catch((err) => {
        if (controller.signal.aborted) return
        setState(err instanceof ApiError && err.isNotFound ? 'notfound' : 'error')
      })

    return () => controller.abort()
  }, [id])

  // Trasa to /analysis-result/:id, więc w praktyce id zawsze jest — guard dla typu/edge-case.
  if (!id) {
    return (
      <Alert variant="warning" title="Brak identyfikatora analizy">
        <Link to="/history">Wróć do historii</Link>.
      </Alert>
    )
  }

  if (state === 'loading') {
    return (
      <div className={styles.center}>
        <Spinner size="lg" />
      </div>
    )
  }

  if (state === 'notfound') {
    return (
      <Alert variant="warning" title="Nie znaleziono analizy">
        Mogła zostać usunięta albo nie należy do Ciebie. <Link to="/history">Wróć do historii</Link>
        .
      </Alert>
    )
  }

  if (state === 'error' || !analysis) {
    return (
      <Alert variant="danger" title="Nie udało się wczytać analizy">
        Spróbuj odświeżyć stronę za chwilę.
      </Alert>
    )
  }

  return (
    <div className={styles.page}>
      <header className={styles.head}>
        <h1>Wynik analizy</h1>
        <p className={styles.subtitle}>
          Bazowy podgląd surowych danych — pełna wizualizacja (Grad-CAM, raport) w kolejnym kroku.
        </p>
      </header>

      <div className={styles.card}>
        <Row label="ID" value={<code>{analysis.id}</code>} />
        <Row label="Status" value={analysis.status} />
        <Row label="Typ" value={analysis.type} />
        {analysis.verdict && (
          <Row
            label="Werdykt"
            value={
              <Badge variant={analysis.verdict === 'FAKE' ? 'danger' : 'success'}>
                {analysis.verdict}
              </Badge>
            }
          />
        )}
        {analysis.confidence != null && <Row label="Pewność" value={pct(analysis.confidence)} />}
        {analysis.videoProb != null && (
          <Row label="Prawd. wideo (FAKE)" value={pct(analysis.videoProb)} />
        )}
        {analysis.audioProb != null && (
          <Row label="Prawd. audio (FAKE)" value={pct(analysis.audioProb)} />
        )}
        {analysis.errorMessage && <Row label="Błąd" value={analysis.errorMessage} />}
      </div>

      {/* tymczasowy zrzut całości — do wywalenia przy właściwej wizualizacji */}
      <pre className={styles.raw}>{JSON.stringify(analysis, null, 2)}</pre>

      <Link to="/history" className={styles.back}>
        ‹ Historia analiz
      </Link>
    </div>
  )
}

function Row({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className={styles.row}>
      <span className={styles.rowLabel}>{label}</span>
      <span className={styles.rowValue}>{value}</span>
    </div>
  )
}
