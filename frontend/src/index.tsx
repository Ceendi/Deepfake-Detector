/* eslint-disable react-refresh/only-export-components -- application entry point */
import { StrictMode, Suspense, lazy } from 'react'
import { createRoot } from 'react-dom/client'

import { KcPage } from './keycloak-theme/kc.gen'

const AppEntrypoint = lazy(() => import('./main.app'))

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {window.kcContext ? (
      <KcPage kcContext={window.kcContext} />
    ) : (
      <Suspense>
        <AppEntrypoint />
      </Suspense>
    )}
  </StrictMode>,
)
