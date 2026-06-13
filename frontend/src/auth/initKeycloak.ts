// Jednorazowy bootstrap auth: init keycloak-js + wpięcie dostawcy tokena do client.ts.
// Wynik jest cache'owany w module — StrictMode (dev) montuje efekty 2×, a keycloak.init()
// rzuca przy drugim wywołaniu ("can only be initialized once"). Cache czyni to idempotentnym.
import { setTokenProvider } from '@/api/client'

import { getToken, keycloak } from './keycloak'

let initPromise: Promise<boolean> | null = null

export function initAuth(): Promise<boolean> {
  if (!initPromise) {
    // Odwrócenie zależności: warstwa API nie zna Keycloaka, dostaje tylko funkcję po token.
    setTokenProvider(getToken)

    initPromise = keycloak.init({
      // check-sso: jeśli istnieje aktywna sesja SSO, user wraca zalogowany bez klikania
      // (kluczowe dla "reload nie wylogowuje"). Brak sesji → po prostu niezalogowany, bez redirectu.
      onLoad: 'check-sso',
      silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
      pkceMethod: 'S256',
    })
  }
  return initPromise
}
