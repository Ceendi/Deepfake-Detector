import '@/styles/reset.css'
import '@/styles/tokens.css'
import '@/styles/global.css'

import { AppRouter } from '@/routes/AppRouter'
import { ErrorBoundary } from '@/components/ErrorBoundary/ErrorBoundary'

import { ThemeProvider } from './context/ThemeContext'
import { AuthProvider } from './context/AuthContext'

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
