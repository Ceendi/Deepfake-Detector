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
  it('uses Keycloak logout while the ID token can identify the SSO session', () => {
    kc.idToken = 'id-token'

    logout()

    expect(kc.logout).toHaveBeenCalledExactlyOnceWith({ redirectUri: 'https://app.example' })
    expect(kc.clearToken).not.toHaveBeenCalled()
    expect(assign).not.toHaveBeenCalled()
  })

  it.each([undefined, ''])(
    'clears local tokens and returns home without an ID token (%s)',
    (token) => {
      kc.idToken = token

      logout()

      expect(kc.clearToken).toHaveBeenCalledOnce()
      expect(assign).toHaveBeenCalledExactlyOnceWith('https://app.example')
      expect(kc.clearToken.mock.invocationCallOrder[0]).toBeLessThan(
        assign.mock.invocationCallOrder[0],
      )
      expect(kc.logout).not.toHaveBeenCalled()
    },
  )
})
