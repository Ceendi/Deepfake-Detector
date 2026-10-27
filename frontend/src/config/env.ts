export type AppEnv = Readonly<{
  apiBaseUrl: string
  keycloakUrl: string
  keycloakRealm: string
  keycloakClientId: string
}>

const BUILD_TIME_KEYS: Record<keyof AppEnv, string> = {
  apiBaseUrl: 'VITE_API_BASE_URL',
  keycloakUrl: 'VITE_KEYCLOAK_URL',
  keycloakRealm: 'VITE_KEYCLOAK_REALM',
  keycloakClientId: 'VITE_KEYCLOAK_CLIENT_ID',
}

declare global {
  interface Window {
    // Set by /config.js: the image renders it from container env at start (nginx template),
    // so one published image works with any Keycloak address. Empty under `npm run dev`.
    __APP_CONFIG__?: unknown
  }
}

function usable(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== ''
}

/** Runtime values win; build-time VITE_* values are the fallback (dev server, tests). */
export function resolveEnv(runtime: unknown, buildTime: Record<string, unknown>): AppEnv {
  const overrides: Record<string, unknown> =
    typeof runtime === 'object' && runtime !== null ? { ...runtime } : {}
  const resolved = {} as Record<keyof AppEnv, string>
  for (const [name, key] of Object.entries(BUILD_TIME_KEYS) as [keyof AppEnv, string][]) {
    const value = usable(overrides[name]) ? overrides[name] : buildTime[key]
    if (!usable(value)) throw new Error(`Brak zmiennej środowiskowej: ${key}`)
    resolved[name] = value
  }
  return Object.freeze(resolved)
}

export const env = resolveEnv(window.__APP_CONFIG__, import.meta.env)
