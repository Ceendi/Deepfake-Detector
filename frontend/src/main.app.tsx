import '@/styles/reset.css'
import '@/styles/tokens.css'
import '@/styles/global.css'

import { AppRouter } from '@/routes/AppRouter'
import { ErrorBoundary } from '@/components/ErrorBoundary/ErrorBoundary'

import { ThemeProvider } from './context/ThemeContext'
import { AuthProvider } from './context/AuthContext'

// Właściwe drzewo aplikacji SPA. Lazy-ładowane z src/index.tsx tylko wtedy, gdy NIE
// renderujemy theme'u Keycloacka (czyli przy normalnym wejściu na apkę, nie na stronie KC).
export default function AppEntrypoint() {
  return (
    <ErrorBoundary>
      <ThemeProvider>
        <AuthProvider>
          <AppRouter />
        </AuthProvider>
      </ThemeProvider>
    </ErrorBoundary>
  )
}
