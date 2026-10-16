import Keycloak from 'keycloak-js'

import { env } from '@/config/env'

import { loginRedirectUri } from './redirect'

export const keycloak = new Keycloak({
  url: env.keycloakUrl,
  realm: env.keycloakRealm,
  clientId: env.keycloakClientId,
})

const MIN_TOKEN_VALIDITY_SECONDS = 30

export async function getToken(): Promise<string | undefined> {
  try {
    await keycloak.updateToken(MIN_TOKEN_VALIDITY_SECONDS)
  } catch {
    return undefined
  }
  return keycloak.token
}

export function login(redirectPath?: string): void {
  void keycloak.login({ redirectUri: loginRedirectUri(redirectPath) })
}

export function register(redirectPath?: string): void {
  void keycloak.login({ action: 'register', redirectUri: loginRedirectUri(redirectPath) })
}

export function logout(): void {
  // End the SSO session so check-sso cannot silently log the user back in.

  void keycloak.logout({ redirectUri: window.location.origin })
}

export function accountManagement(): void {
  void keycloak.accountManagement()
}

export interface AuthUser {
  id: string
  username?: string
  email?: string
  name?: string
}

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
