import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

import { AuthProvider } from './AuthContext'
import { useAuth } from './auth-context'
import { logout } from '@/auth/keycloak'

const kc = vi.hoisted(() => ({
  updateToken: vi.fn(),
  refreshToken: 'refresh' as string | undefined,
  refreshTokenParsed: undefined as { exp?: number } | undefined,
  timeSkew: 0 as number | null,
  onTokenExpired: undefined as (() => void) | undefined,
  onAuthSuccess: undefined as (() => void) | undefined,
  onAuthLogout: undefined as (() => void) | undefined,
}))

vi.mock('@/auth/keycloak', () => ({
  keycloak: kc,
  currentUser: () => ({ id: 'u-1', username: 'user' }),
  login: vi.fn(),
  logout: vi.fn(),
  register: vi.fn(),
}))
vi.mock('@/auth/initKeycloak', () => ({ initAuth: vi.fn().mockResolvedValue(true) }))

function Probe() {
  const { isAuthenticated, isLoading, user, logout } = useAuth()
  return (
    <>
      <span>{isLoading ? 'loading' : isAuthenticated ? 'in' : 'out'}</span>
      <span>{user?.username ?? 'no user'}</span>
      <button onClick={logout}>Log out</button>
    </>
  )
}

async function renderSignedIn() {
  render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  )
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
  expect(screen.getByText('in')).toBeInTheDocument()
}

beforeEach(() => {
  vi.useFakeTimers()
  kc.updateToken.mockReset()
  kc.refreshToken = 'refresh'
  kc.refreshTokenParsed = { exp: Math.floor(Date.now() / 1000) + 1800 }
  kc.timeSkew = 0
})
afterEach(() => vi.useRealTimers())

it('a transient refresh failure retries with backoff and keeps the user signed in', async () => {
  kc.updateToken
    .mockRejectedValueOnce(new TypeError('Network down'))
    .mockRejectedValueOnce(new TypeError('Network down'))
    .mockResolvedValue(true)
  await renderSignedIn()

  await act(async () => {
    kc.onTokenExpired?.()
    await vi.advanceTimersByTimeAsync(0)
  })
  expect(screen.getByText('in')).toBeInTheDocument()
  expect(kc.updateToken).toHaveBeenCalledTimes(1)

  await act(async () => {
    await vi.advanceTimersByTimeAsync(1000)
  })
  expect(kc.updateToken).toHaveBeenCalledTimes(2)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(2000)
  })
  expect(kc.updateToken).toHaveBeenCalledTimes(3)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(60000)
  })
  expect(kc.updateToken).toHaveBeenCalledTimes(3)
  expect(screen.getByText('in')).toBeInTheDocument()
})

it('signs out when the refresh token is gone', async () => {
  kc.updateToken.mockImplementation(async () => {
    kc.refreshToken = undefined
    throw new Error('Unable to update token, no refresh token available.')
  })
  await renderSignedIn()
  await act(async () => {
    kc.onTokenExpired?.()
    await vi.advanceTimersByTimeAsync(0)
  })
  expect(screen.getByText('out')).toBeInTheDocument()
})

it('signs out when the refresh token has expired', async () => {
  kc.refreshTokenParsed = { exp: Math.floor(Date.now() / 1000) - 1 }
  kc.updateToken.mockRejectedValue(new TypeError('Network down'))
  await renderSignedIn()
  await act(async () => {
    kc.onTokenExpired?.()
    await vi.advanceTimersByTimeAsync(0)
  })
  expect(screen.getByText('out')).toBeInTheDocument()
})

it('signs out on keycloak logout', async () => {
  await renderSignedIn()
  act(() => kc.onAuthLogout?.())
  expect(screen.getByText('out')).toBeInTheDocument()
})

it('unmount stops pending refresh retries', async () => {
  kc.updateToken.mockRejectedValue(new TypeError('Network down'))
  const view = render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  )
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
  await act(async () => {
    kc.onTokenExpired?.()
    await vi.advanceTimersByTimeAsync(0)
  })
  expect(kc.updateToken).toHaveBeenCalledTimes(1)
  view.unmount()
  await act(async () => {
    await vi.advanceTimersByTimeAsync(60000)
  })
  expect(kc.updateToken).toHaveBeenCalledTimes(1)
  expect(kc.onTokenExpired).toBeUndefined()
})

it('a fast local clock does not end a session the server still considers valid', async () => {
  // Local clock is 20 min ahead: locally expired, still valid on the server clock.
  kc.refreshTokenParsed = { exp: Math.floor(Date.now() / 1000) - 1 }
  kc.timeSkew = 1200
  kc.updateToken.mockRejectedValueOnce(new TypeError('Network down')).mockResolvedValue(true)
  await renderSignedIn()
  await act(async () => {
    kc.onTokenExpired?.()
    await vi.advanceTimersByTimeAsync(1000)
  })
  expect(screen.getByText('in')).toBeInTheDocument()
  expect(kc.updateToken).toHaveBeenCalledTimes(2)
})

it('explicit logout clears context even when Keycloak emits no logout event', async () => {
  await renderSignedIn()

  fireEvent.click(screen.getByRole('button', { name: 'Log out' }))

  expect(logout).toHaveBeenCalledOnce()
  expect(screen.getByText('out')).toBeInTheDocument()
  expect(screen.getByText('no user')).toBeInTheDocument()
})
