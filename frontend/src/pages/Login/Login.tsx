import { Navigate, useLocation } from 'react-router-dom'

import { useAuth } from '@/context/auth-context'
import { Button } from '@/components/ui/Button/Button'
import { Spinner } from '@/components/ui/Spinner/Spinner'

// Minimalny ekran wejścia (pełny branding/layout: KROK 8). Logowanie i rejestracja są
// delegowane do Keycloaka (redirect PKCE) — nie budujemy własnego formularza.
export default function Login() {
  const { isAuthenticated, isLoading, login, register } = useAuth()
  const location = useLocation()

  if (isLoading) return <Spinner label="Sprawdzanie sesji…" />

  // Już zalogowany → wracamy tam, skąd ProtectedRoute przekierował (albo na dashboard).
  if (isAuthenticated) {
    const from = (location.state as { from?: Location } | null)?.from?.pathname ?? '/'
    return <Navigate to={from} replace />
  }

  return (
    <main>
      <h1>DeepfakeDetector</h1>
      <Button variant="primary" size="md" onClick={login}>
        Zaloguj się
      </Button>
      <Button variant="ghost" size="md" onClick={register}>
        Załóż konto
      </Button>
    </main>
  )
}
