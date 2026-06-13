import { Navigate, useLocation } from 'react-router-dom'

import { LogIn, UserPlus, ShieldCheck } from 'lucide-react'

import { useAuth } from '@/context/auth-context'
import { Button } from '@/components/ui/Button/Button'
import { Spinner } from '@/components/ui/Spinner/Spinner'

import styles from './Login.module.css'

// Pełnoekranowy ekran wejścia (renderowany poza Layoutem/Headerem). Logowanie
// i rejestracja delegowane do Keycloaka (redirect PKCE) — nie budujemy własnego
// formularza, tylko dwa wejścia w hostowany flow.
export default function Login() {
  const { isAuthenticated, isLoading, login, register } = useAuth()
  const location = useLocation()

  // Sprawdzanie sesji — spinner wyśrodkowany na tym samym tle co karta.
  if (isLoading) {
    return (
      <div className={styles.screen}>
        <Spinner label="Sprawdzanie sesji…" />
      </div>
    )
  }

  // Już zalogowany → wracamy tam, skąd ProtectedRoute przekierował (albo na dashboard).
  if (isAuthenticated) {
    const from = (location.state as { from?: Location } | null)?.from?.pathname ?? '/'
    return <Navigate to={from} replace />
  }

  return (
    <main className={styles.screen}>
      <section className={styles.card}>
        <div className={styles.brand}>
          <span className={styles.brandMark} aria-hidden="true">
            <ShieldCheck size={28} strokeWidth={2.4} />
          </span>
          <h1 className={styles.title}>DeepfakeDetector</h1>
        </div>

        <p className={styles.subtitle}>
          Wykrywanie deepfake&apos;ów w wideo i audio. Zaloguj się, aby przesłać i przeanalizować
          plik.
        </p>

        <div className={styles.actions}>
          <Button
            variant="primary"
            size="md"
            leftIcon={LogIn}
            className={styles.actionBtn}
            onClick={login}
          >
            Zaloguj się
          </Button>
          <Button
            variant="ghost"
            size="md"
            leftIcon={UserPlus}
            className={styles.actionBtn}
            onClick={register}
          >
            Załóż konto
          </Button>
        </div>

        <div className={styles.footer}>
          <p className={styles.note}>
            <ShieldCheck size={14} strokeWidth={2.4} aria-hidden="true" />
            <span>Bezpieczne logowanie przez Keycloak</span>
          </p>
        </div>
      </section>
    </main>
  )
}
