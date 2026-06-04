import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import '@/styles/reset.css'
import '@/styles/tokens.css'
import '@/styles/global.css'

import { AppRouter } from '@/routes/AppRouter'
import { ErrorBoundary } from '@/components/ErrorBoundary/ErrorBoundary'

import { AuthProvider } from './context/AuthContext'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <AuthProvider>
        <AppRouter />
      </AuthProvider>
    </ErrorBoundary>
  </StrictMode>,
)
