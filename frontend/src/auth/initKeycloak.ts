import { setTokenProvider } from '@/api/client'

import { getToken, keycloak } from './keycloak'
import { restoreLoginHash } from './redirect'

// StrictMode mounts twice, but keycloak-js permits initialization only once.
let initPromise: Promise<boolean> | null = null

export function initAuth(): Promise<boolean> {
  if (!initPromise) {
    setTokenProvider(getToken)

    initPromise = keycloak
      .init({
        onLoad: 'check-sso',
        silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
        pkceMethod: 'S256',
      })
      .then((authenticated) => {
        if (authenticated) restoreLoginHash()
        return authenticated
      })
  }
  return initPromise
}
