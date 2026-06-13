import { useEffect, useState } from 'react'

import { Link } from 'react-router-dom'

import { Plus, LineChart, ShieldAlert, ShieldCheck, BarChart3 } from 'lucide-react'

import { useAuth } from '@/context/auth-context'
import { getStats, listAnalyses } from '@/api/analysis'
import type { AnalysisSummary, UserStats } from '@/api/types'

import { Spinner } from '@/components/ui/Spinner/Spinner'
import { Alert } from '@/components/ui/Alert/Alert'
import { LinkButton } from '@/components/ui/LinkButton/LinkButton'
import { ProgressBar } from '@/components/ui/ProgressBar/ProgressBar'
// „Ostatnie analizy" reużywa wiersza z History — jedno źródło prawdy, identyczny render.
import { HistoryRow } from '@/pages/History/components/HistoryRow'

import { StatCard } from './components/StatCard'
import { DashboardEmpty } from './components/DashboardEmpty'

import styles from './Dashboard.module.css'

type LoadState = 'loading' | 'ready' | 'error'

export default function Dashboard() {
  const { user } = useAuth()

  const [state, setState] = useState<LoadState>('loading')
  const [items, setItems] = useState<AnalysisSummary[]>([])
  const [stats, setStats] = useState<UserStats | null>(null)

  // Agregaty (karty) + pierwsza strona listy (ostatnie analizy) lecą równolegle.
  // stats.total > 0 → dashboard z danymi; === 0 → onboarding (pusty stan).
  useEffect(() => {
    const controller = new AbortController()

    Promise.all([getStats(controller.signal), listAnalyses(0, 5, controller.signal)])
      .then(([userStats, paged]) => {
        setStats(userStats)
        setItems(paged.content)
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

  // state === 'ready' ⟹ stats != null; `|| !stats` zawęża typ i jest bezpiecznym fallbackiem.
  if (!stats || stats.total === 0) {
    return <DashboardEmpty firstName={firstName} />
  }

  // Odsetek FAKE wśród analiz, które dostały werdykt (fake + real == COMPLETED), nie wśród total —
  // PENDING/FAILED/CANCELLED nie mają werdyktu. Zero werdyktów → 0%, bez dzielenia przez zero.
  const verdictTotal = stats.verdicts.fake + stats.verdicts.real
  const fakePercent = verdictTotal > 0 ? Math.round((stats.verdicts.fake / verdictTotal) * 100) : 0
  const last7Meta =
    stats.last7Days > 0
      ? `↗ +${stats.last7Days} w ostatnich 7 dniach`
      : 'brak nowych w tym tygodniu'

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
          value={stats.total}
          iconTone="accent"
          meta={last7Meta}
        />
        <StatCard
          icon={ShieldAlert}
          label="Wykryte FAKE"
          value={stats.verdicts.fake}
          iconTone="danger"
          valueTone="danger"
          meta="manipulacje obrazu / dźwięku"
        />
        <StatCard
          icon={ShieldCheck}
          label="Potwierdzone REAL"
          value={stats.verdicts.real}
          iconTone="success"
          valueTone="success"
          meta="materiały autentyczne"
        />
        <StatCard
          icon={BarChart3}
          label="Odsetek FAKE"
          value={`${fakePercent}%`}
          iconTone="warning"
          meta={<ProgressBar value={fakePercent} tone="warning" />}
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
          <HistoryRow key={item.id} analysis={item} />
        ))}
      </section>
    </div>
  )
}
