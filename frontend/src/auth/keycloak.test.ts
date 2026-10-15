import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const kc = vi.hoisted(() => ({
  idToken: undefined as string | undefined,
  logout: vi.fn(),
  clearToken: vi.fn(),
}))

vi.mock('keycloak-js', () => ({
  default: class {
    constructor() {
      return kc
    }
  },
}))
vi.mock('@/config/env', () => ({
  env: { keycloakUrl: 'http://kc', keycloakRealm: 'r', keycloakClientId: 'c' },
}))

import { logout } from './keycloak'

const assign = vi.fn()

beforeEach(() => {
  vi.resetAllMocks()
  kc.idToken = undefined
  vi.stubGlobal('location', { origin: 'https://app.example', assign })
})
afterEach(() => vi.unstubAllGlobals())

describe('logout', () => {
  it.each(['id-token', undefined, ''])(
    'ends the Keycloak SSO session even without an ID token (%s)',
    (token) => {
      kc.idToken = token

      logout()

      // A local-only logout would be undone by check-sso on the next load.
      expect(kc.logout).toHaveBeenCalledExactlyOnceWith({ redirectUri: 'https://app.example' })
      expect(kc.clearToken).not.toHaveBeenCalled()
      expect(assign).not.toHaveBeenCalled()
    },
  )
})
