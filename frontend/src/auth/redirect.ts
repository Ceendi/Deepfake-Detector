// Adres powrotu po logowaniu w Keycloaku (deep link). Tylko ścieżka w obrębie tej aplikacji —
// `//host` albo pełny URL mógłby przekierować na obcą domenę (open redirect).
export function loginRedirectUri(path: string | undefined, origin = window.location.origin) {
  if (!path || !path.startsWith('/') || path.startsWith('//') || path.startsWith('/\\')) {
    return undefined // keycloak-js wraca wtedy na bieżący adres
  }
  if (path === '/login' || path.startsWith('/login?') || path.startsWith('/login#'))
    return undefined
  return `${origin}${path}`
}
