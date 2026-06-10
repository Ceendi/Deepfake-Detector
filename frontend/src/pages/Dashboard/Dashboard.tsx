import { useEffect, useState } from 'react'

import { Link } from 'react-router-dom'

import { Plus, LineChart, ShieldAlert, ShieldCheck, BarChart3 } from 'lucide-react'

import { useAuth } from '@/context/auth-context'
import { listAnalyses } from '@/api/analysis'
import type { AnalysisSummary } from '@/api/types'

import { Spinner } from '@/components/ui/Spinner/Spinner'
import { Alert } from '@/components/ui/Alert/Alert'
import { LinkButton } from '@/components/ui/LinkButton/LinkButton'
import { ProgressBar } from '@/components/ui/ProgressBar/ProgressBar'

import { StatCard } from './components/StatCard'
import { AnalysisRow } from './components/AnalysisRow'
import { DashboardEmpty } from './components/DashboardEmpty'

import styles from './Dashboard.module.css'

// TODO(backend): statystyki podsumowujące nie mają jeszcze endpointu (np. GET /api/analysis/stats).
// Na razie dane zmyślone — podmień, gdy Orchestrator wystawi agregaty.
const FAKE_STATS = {
  total: 128,
  totalDelta: 12,
  fake: 37,
  real: 91,
}
const fakePercent = Math.round((FAKE_STATS.fake / FAKE_STATS.total) * 100)

type LoadState = 'loading' | 'ready' | 'error'

// TODO: tymczasowy podgląd widoku „z danymi" zanim ruszy backend. Ustaw na false (i usuń
// MOCK_ANALYSES) gdy lista z Orchestratora działa. Włączone → pomija fetch, używa mocka.
const USE_MOCK = false
const MOCK_ANALYSES: AnalysisSummary[] = [
  {
    id: '1',
    fileId: 'a1b2c3d4e5f6',
    type: 'VIDEO',
    status: 'COMPLETED',
    verdict: 'FAKE',
    confidence: 0.94,
    createdAt: '2026-06-10T14:32:00Z',
    updatedAt: '2026-06-10T14:33:00Z',
  },
  {
    id: '2',
    fileId: 'b2c3d4e5f6a1',
    type: 'AUDIO',
    status: 'COMPLETED',
    verdict: 'REAL',
    confidence: 0.88,
    createdAt: '2026-06-10T11:08:00Z',
    updatedAt: '2026-06-10T11:09:00Z',
  },
  {
    id: '3',
    fileId: 'c3d4e5f6a1b2',
    type: 'VIDEO',
    status: 'PROCESSING',
    verdict: null,
    confidence: null,
    createdAt: '2026-06-10T09:15:00Z',
    updatedAt: '2026-06-10T09:15:00Z',
  },
  {
    id: '4',
    fileId: 'd4e5f6a1b2c3',
    type: 'VIDEO',
    status: 'COMPLETED',
    verdict: 'REAL',
    confidence: 0.91,
    createdAt: '2026-06-09T18:40:00Z',
    updatedAt: '2026-06-09T18:42:00Z',
  },
  {
    id: '5',
    fileId: 'e5f6a1b2c3d4',
    type: 'AUDIO',
    status: 'FAILED',
    verdict: null,
    confidence: null,
    createdAt: '2026-06-08T20:12:00Z',
    updatedAt: '2026-06-08T20:12:00Z',
  },
]

export default function Dashboard() {
  const { user } = useAuth()

  const [state, setState] = useState<LoadState>(USE_MOCK ? 'ready' : 'loading')
  const [items, setItems] = useState<AnalysisSummary[]>(USE_MOCK ? MOCK_ANALYSES : [])
  const [total, setTotal] = useState(USE_MOCK ? MOCK_ANALYSES.length : 0)

  // Stan „ma analizy / nie ma" ustalamy po pierwszej stronie listy z Orchestratora.
  // totalElements > 0 → pokazujemy dashboard z danymi; === 0 → onboarding (pusty stan).
  useEffect(() => {
    if (USE_MOCK) return // podgląd z mocka — nie wołamy backendu
    const controller = new AbortController()

    listAnalyses(0, 5, controller.signal)
      .then((paged) => {
        setItems(paged.content)
        setTotal(paged.page.totalElements)
        setState('ready')
      })
      .catch(() => {
        if (controller.signal.aborted) return
        setState('error')
      })

    return () => controller.abort()
  }, [])

  const firstName = (user?.name ?? user?.username ?? '').split(' ')[0] || 'użytkowniku'

  if (state === 'loading') {
    return (
      <div className={styles.center}>
        <Spinner size="lg" />
      </div>
    )
  }

  if (state === 'error') {
    return (
      <Alert variant="danger" title="Nie udało się wczytać analiz">
        Spróbuj odświeżyć stronę za chwilę.
      </Alert>
    )
  }

  if (total === 0) {
    return <DashboardEmpty firstName={firstName} />
  }

  // --- Stan z danymi ----------------------------------------------------------
  return (
    <div className={styles.page}>
      <div className={styles.head}>
        <div className={styles.greeting}>
          <h1>Cześć, {firstName}.</h1>
          <p className={styles.subtitle}>Oto przegląd Twoich analiz.</p>
        </div>
        <LinkButton to="/upload" variant="primary" size="md" leftIcon={Plus}>
          Nowa analiza
        </LinkButton>
      </div>

      <div className={styles.stats}>
        <StatCard
          icon={LineChart}
          label="Łączna liczba analiz"
          value={FAKE_STATS.total}
          iconTone="accent"
          meta={`↗ +${FAKE_STATS.totalDelta} w tym tygodniu`}
        />
        <StatCard
          icon={ShieldAlert}
          label="Wykryte FAKE"
          value={FAKE_STATS.fake}
          iconTone="danger"
          valueTone="danger"
          meta="manipulacje obrazu / dźwięku"
        />
        <StatCard
          icon={ShieldCheck}
          label="Potwierdzone REAL"
          value={FAKE_STATS.real}
          iconTone="success"
          valueTone="success"
          meta="materiały autentyczne"
        />
        <StatCard
          icon={BarChart3}
          label="Odsetek FAKE"
          value={`${fakePercent}%`}
          iconTone="warning"
          meta={<ProgressBar value={fakePercent} tone="danger" />}
        />
      </div>

      <section className={styles.panel}>
        <div className={styles.panelHead}>
          <h2>Ostatnie analizy</h2>
          <Link to="/history" className={styles.viewAll}>
            Zobacz wszystkie ›
          </Link>
        </div>
        {items.map((item) => (
          <AnalysisRow key={item.id} analysis={item} />
        ))}
      </section>
    </div>
  )
}
