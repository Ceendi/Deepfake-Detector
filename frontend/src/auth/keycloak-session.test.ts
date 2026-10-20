import { beforeEach, describe, expect, it, vi } from 'vitest'

const kc = vi.hoisted(() => ({
  token: undefined as string | undefined,
  tokenParsed: undefined as Record<string, string> | undefined,
  updateToken: vi.fn(),
  login: vi.fn(),
  accountManagement: vi.fn(),
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
vi.mock('./redirect', () => ({ loginRedirectUri: (path?: string) => `https://app${path ?? ''}` }))

import { accountManagement, currentUser, getToken, login, register } from './keycloak'

beforeEach(() => {
  vi.clearAllMocks()
  kc.token = undefined
  kc.tokenParsed = undefined
})

describe('keycloak session helpers', () => {
  it('refreshes the token before returning it', async () => {
    kc.token = 'fresh'
    kc.updateToken.mockResolvedValue(true)
    await expect(getToken()).resolves.toBe('fresh')
    expect(kc.updateToken).toHaveBeenCalledWith(30)
  })

  it('returns no token when the refresh fails', async () => {
    kc.token = 'stale'
    kc.updateToken.mockRejectedValue(new Error('expired'))
    await expect(getToken()).resolves.toBeUndefined()
  })

  it('starts login, registration and account management in Keycloak', () => {
    login('/history')
    expect(kc.login).toHaveBeenLastCalledWith({ redirectUri: 'https://app/history' })
    register()
    expect(kc.login).toHaveBeenLastCalledWith({ action: 'register', redirectUri: 'https://app' })
    accountManagement()
    expect(kc.accountManagement).toHaveBeenCalled()
  })

  it('maps the parsed token to the current user', () => {
    expect(currentUser()).toBeNull()
    kc.tokenParsed = { sub: 'u1', preferred_username: 'alice', email: 'a@x.test', name: 'Alice' }
    expect(currentUser()).toEqual({ id: 'u1', username: 'alice', email: 'a@x.test', name: 'Alice' })
    kc.tokenParsed = {}
    expect(currentUser()).toMatchObject({ id: '' })
  })
})
