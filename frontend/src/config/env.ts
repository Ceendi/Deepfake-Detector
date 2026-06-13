function required(key: string): string {
  const v = import.meta.env[key]
  if (!v) throw new Error(`Brak zmiennej środowiskowej: ${key}`)
  return v as string
}

export const env = {
  apiBaseUrl: required('VITE_API_BASE_URL'),
  keycloakUrl: required('VITE_KEYCLOAK_URL'),
  keycloakRealm: required('VITE_KEYCLOAK_REALM'),
  keycloakClientId: required('VITE_KEYCLOAK_CLIENT_ID'),
} as const
