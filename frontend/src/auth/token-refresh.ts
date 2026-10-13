// Idle token refresh for keycloak-js. A failed refresh is usually transient (network, Keycloak
// restart), so it retries with backoff; only a real session end is reported to the UI.
import type Keycloak from 'keycloak-js'

const MIN_TOKEN_VALIDITY_SECONDS = 30
export const REFRESH_RETRY_DELAYS_MS = [1000, 2000, 4000, 8000, 16000, 30000]

type RefreshClient = Pick<Keycloak, 'updateToken' | 'refreshToken' | 'refreshTokenParsed'>

// keycloak-js clears tokens (and fires onAuthLogout) when Keycloak rejects the refresh token.
export function sessionEnded(kc: RefreshClient, now = Date.now()): boolean {
  if (!kc.refreshToken) return true
  const exp = kc.refreshTokenParsed?.exp
  return exp !== undefined && exp > 0 && exp * 1000 <= now
}

/** Returns `refresh` for onTokenExpired and `stop` for cleanup. */
export function createTokenRefresher(kc: RefreshClient, onSessionEnded: () => void) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let attempt = 0
  let stopped = false

  function refresh(): void {
    clearTimeout(timer)
    if (stopped) return
    kc.updateToken(MIN_TOKEN_VALIDITY_SECONDS).then(
      () => {
        attempt = 0
      },
      () => {
        if (stopped) return
        if (sessionEnded(kc)) {
          onSessionEnded()
          return
        }
        const delay = REFRESH_RETRY_DELAYS_MS[Math.min(attempt, REFRESH_RETRY_DELAYS_MS.length - 1)]
        attempt += 1
        timer = setTimeout(refresh, delay)
      },
    )
  }

  function stop(): void {
    stopped = true
    clearTimeout(timer)
  }

  return { refresh, stop }
}
