const HASH_KEY = 'deepfake-login-hash'

// OAuth redirect URIs cannot contain fragments; keep the anchor in this tab only.
export function loginRedirectUri(path: string | undefined, origin = window.location.origin) {
  try {
    sessionStorage.removeItem(HASH_KEY)
  } catch {
    /* Storage can be disabled in private browsing. */
  }
  if (!path || !path.startsWith('/') || path.startsWith('//') || path.startsWith('/\\')) {
    return undefined
  }
  const url = new URL(path, origin)
  if (url.origin !== origin || url.pathname === '/login') return undefined
  if (url.hash) {
    try {
      sessionStorage.setItem(
        HASH_KEY,
        JSON.stringify({ path: url.pathname + url.search, hash: url.hash }),
      )
    } catch {
      /* Losing an anchor must not prevent login. */
    }
  }
  url.hash = ''
  return url.href
}

export function restoreLoginHash(): void {
  try {
    const saved = sessionStorage.getItem(HASH_KEY)
    sessionStorage.removeItem(HASH_KEY)
    if (!saved) return
    const { path, hash } = JSON.parse(saved) as { path?: unknown; hash?: unknown }
    if (
      path !== window.location.pathname + window.location.search ||
      typeof hash !== 'string' ||
      !hash.startsWith('#') ||
      window.location.hash
    )
      return
    window.history.replaceState(window.history.state, '', path + hash)
    // The router may already be mounted while Keycloak finishes initialization.
    window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }))
  } catch {
    /* A malformed or unavailable saved anchor must not block authentication. */
  }
}
