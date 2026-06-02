import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import '@/styles/reset.css'
import '@/styles/tokens.css'
import '@/styles/global.css'

import { AppRouter } from '@/routes/AppRouter'

import { AuthProvider } from './context/AuthContext'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider>
      <AppRouter />
    </AuthProvider>
  </StrictMode>,
)
