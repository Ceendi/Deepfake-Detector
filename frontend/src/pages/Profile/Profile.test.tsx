import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

vi.mock('@/auth/keycloak', () => ({ accountManagement: vi.fn() }))

import { accountManagement } from '@/auth/keycloak'
import { AuthContext, type AuthState } from '@/context/auth-context'
import { ThemeContext, type ThemeState } from '@/context/theme-context'
import Profile from './Profile'

function renderProfile(user: AuthState['user'], theme: ThemeState['theme'] = 'light') {
  const auth = { user, logout: vi.fn() } as unknown as AuthState
  const themeState: ThemeState = { theme, setTheme: vi.fn(), toggleTheme: vi.fn() }
  render(
    <AuthContext value={auth}>
      <ThemeContext value={themeState}>
        <Profile />
      </ThemeContext>
    </AuthContext>,
  )
  return { auth, themeState }
}

describe('Profile', () => {
  it('shows the account identity and opens Keycloak account management', () => {
    renderProfile({ name: 'Alice Smith', email: 'alice@example.test' } as AuthState['user'])
    expect(screen.getByRole('heading', { name: 'Alice Smith' })).toBeInTheDocument()
    expect(screen.getByText('AS')).toBeInTheDocument()
    expect(screen.getByText('alice@example.test')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Edytuj profil/ }))
    expect(accountManagement).toHaveBeenCalled()
  })

  it('falls back to the username or a generic name and hides a missing email', () => {
    renderProfile({ username: 'bob' } as AuthState['user'])
    expect(screen.getByRole('heading', { name: 'bob' })).toBeInTheDocument()
    expect(screen.getByText('BO')).toBeInTheDocument()
  })

  it('switches the theme and logs out', () => {
    const { auth, themeState } = renderProfile(null, 'dark')
    expect(screen.getByRole('heading', { name: 'Użytkownik' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Ciemny/ })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: /Jasny/ }))
    expect(themeState.setTheme).toHaveBeenCalledWith('light')
    fireEvent.click(screen.getByRole('button', { name: /Ciemny/ }))
    expect(themeState.setTheme).toHaveBeenCalledWith('dark')
    fireEvent.click(screen.getByRole('button', { name: /Wyloguj się/ }))
    expect(auth.logout).toHaveBeenCalled()
  })
})
