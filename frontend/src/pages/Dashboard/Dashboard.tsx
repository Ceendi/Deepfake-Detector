import { useAuth } from '@/context/auth-context'
import { Button } from '@/components/ui/Button/Button'

// Minimalny Dashboard do testów auth (pełna wersja: KROK 13).
export default function Dashboard() {
  const { user, logout } = useAuth()

  return (
    <main>
      <h1>Dashboard — TODO</h1>
      <p>Zalogowany jako: {user?.username ?? user?.email ?? 'nieznany'}</p>
      <Button variant="ghost" size="md" onClick={logout}>
        Wyloguj się
      </Button>
    </main>
  )
}
