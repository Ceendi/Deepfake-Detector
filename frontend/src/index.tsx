/* eslint-disable react-refresh/only-export-components -- to jest entry point, nie moduł komponentu */
import { StrictMode, Suspense, lazy } from 'react'
import { createRoot } from 'react-dom/client'

import { KcPage } from './keycloak-theme/kc.gen'

// Keycloakify wstrzykuje `window.kcContext` w HTML stron logowania/rejestracji serwowanych
// przez Keycloacka. Jeśli jest → renderujemy theme KC (React). W przeciwnym razie (normalne
// wejście na :5173) → lazy-ładujemy całą aplikację SPA. Dzięki temu kod KC nie wchodzi do
// głównego bundla apki i odwrotnie.
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
