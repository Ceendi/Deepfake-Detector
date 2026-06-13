// Instancja keycloak-js (public client `deepfake-web`, PKCE S256) + niskopoziomowe helpery.
// Inicjalizacją i wpięciem w app zajmuje się initKeycloak.ts; tu trzymamy samą instancję
// i operacje, których używają AuthContext (login/logout/register/user) oraz client.ts (getToken).
import Keycloak from 'keycloak-js'

import { env } from '@/config/env'

export const keycloak = new Keycloak({
  url: env.keycloakUrl,
  realm: env.keycloakRealm,
  clientId: env.keycloakClientId,
})

// client.ts woła getToken() przed każdym żądaniem. updateToken(minValidity) odświeża token,
// jeśli wygaśnie w ciągu `minValidity` sekund — inaczej zwraca obecny bez sieciowego round-tripu.
const MIN_TOKEN_VALIDITY_SECONDS = 30

export async function getToken(): Promise<string | undefined> {
  try {
    await keycloak.updateToken(MIN_TOKEN_VALIDITY_SECONDS)
  } catch {
    // Brak/wygasła sesja SSO albo nieudany refresh → lecimy bez tokena.
    // Chroniony endpoint odpowie 401, a UI przekieruje na logowanie.
    return undefined
  }
  return keycloak.token
}

// Authorization Code + PKCE — Keycloak hostuje stronę logowania/rejestracji (redirect),
// nie budujemy własnego formularza (directAccessGrants jest wyłączony dla tego klienta).
export function login(): void {
  void keycloak.login()
}

export function register(): void {
  void keycloak.login({ action: 'register' })
}

export function logout(): void {
  void keycloak.logout({ redirectUri: window.location.origin })
}

// Link do Keycloak Account Console (edycja profilu / zmiana hasła — KROK 14 / V2).
export function accountManagement(): void {
  void keycloak.accountManagement()
}

export interface AuthUser {
  id: string
  username?: string
  email?: string
  name?: string
}

// Dane usera czytamy z access tokena (tokenParsed) — bez dodatkowego żądania /userinfo.
export function currentUser(): AuthUser | null {
  const token = keycloak.tokenParsed
  if (!token) return null
  return {
    id: token.sub ?? '',
    username: token.preferred_username,
    email: token.email,
    name: token.name,
  }
}
