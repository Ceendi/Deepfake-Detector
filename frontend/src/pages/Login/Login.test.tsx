import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

import { AuthContext, type AuthState } from '@/context/auth-context'

import Login from './Login'

function renderLogin(state: unknown) {
  const auth: AuthState = {
    isAuthenticated: false,
    isLoading: false,
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    register: vi.fn(),
  }
  render(
    <AuthContext value={auth}>
      <MemoryRouter initialEntries={[{ pathname: '/login', state }]}>
        <Login />
      </MemoryRouter>
    </AuthContext>,
  )
  return auth
}

describe('Login', () => {
  it('przekazuje deep link (ścieżka + query + hash) do logowania i rejestracji', () => {
    const auth = renderLogin({
      from: { pathname: '/analysis-result/abc', search: '?tab=1', hash: '#gradcam' },
    })

    fireEvent.click(screen.getByRole('button', { name: /Zaloguj się/ }))
    fireEvent.click(screen.getByRole('button', { name: /Załóż konto/ }))

    expect(auth.login).toHaveBeenCalledWith('/analysis-result/abc?tab=1#gradcam')
    expect(auth.register).toHaveBeenCalledWith('/analysis-result/abc?tab=1#gradcam')
  })

  it('bez zapisanej strony wraca na dashboard', () => {
    const auth = renderLogin(null)
    fireEvent.click(screen.getByRole('button', { name: /Zaloguj się/ }))
    expect(auth.login).toHaveBeenCalledWith('/')
  })
})
