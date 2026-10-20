import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth/keycloak', () => ({ accountManagement: vi.fn() }))

import { accountManagement } from '@/auth/keycloak'
import { AuthContext, type AuthState } from '@/context/auth-context'
import { Header } from './Header'

const listeners = new Set<(e: { matches: boolean }) => void>()
const logout = vi.fn()

function renderHeader(user: AuthState['user']) {
  const auth: AuthState = {
    isAuthenticated: true,
    isLoading: false,
    user,
    login: vi.fn(),
    logout,
    register: vi.fn(),
  }
  return render(
    <AuthContext value={auth}>
      <MemoryRouter initialEntries={['/history']}>
        <Header />
      </MemoryRouter>
    </AuthContext>,
  )
}

beforeEach(() => {
  listeners.clear()
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: false,
      addEventListener: (_: string, fn: (e: { matches: boolean }) => void) => listeners.add(fn),
      removeEventListener: (_: string, fn: (e: { matches: boolean }) => void) =>
        listeners.delete(fn),
    })),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('Header', () => {
  it('shows initials of the display name and marks the active route', () => {
    renderHeader({ name: 'Alice Smith', username: 'alice' } as AuthState['user'])
    expect(screen.getByText('AS')).toBeInTheDocument()
    expect(screen.getByText('Alice Smith')).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Główna nawigacja' })
    expect(nav.querySelector('a[aria-current="page"]')).toHaveTextContent('Historia')
  })

  it('falls back to username, email and a generic name', () => {
    const { unmount } = renderHeader({ username: 'bob' } as AuthState['user'])
    expect(screen.getByText('BO')).toBeInTheDocument()
    unmount()
    renderHeader(null)
    expect(screen.getByText('Użytkownik')).toBeInTheDocument()
  })

  it('opens the user menu, reaches account settings and logs out', () => {
    renderHeader({ email: 'carol@example.test' } as AuthState['user'])
    const toggle = screen.getByRole('button', { name: /carol@example.test/ })
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')

    fireEvent.click(screen.getByRole('menuitem', { name: /Ustawienia/ }))
    expect(accountManagement).toHaveBeenCalledOnce()
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()

    fireEvent.click(toggle)
    fireEvent.click(screen.getByRole('menuitem', { name: /Wyloguj/ }))
    expect(logout).toHaveBeenCalledOnce()
  })

  it('closes the user menu on an outside click or after choosing the profile', () => {
    renderHeader({ name: 'Alice' } as AuthState['user'])
    const toggle = screen.getByRole('button', { name: /Alice/ })
    fireEvent.click(toggle)
    fireEvent.mouseDown(screen.getByRole('menuitem', { name: /Profil/ }))
    expect(screen.getByRole('menu')).toBeInTheDocument()
    fireEvent.mouseDown(document.body)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()

    fireEvent.click(toggle)
    fireEvent.click(screen.getByRole('menuitem', { name: /Profil/ }))
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('opens the mobile menu, locks scrolling and closes on Escape, desktop width or backdrop', () => {
    const { container } = renderHeader({ name: 'Alice' } as AuthState['user'])
    const open = () => fireEvent.click(screen.getByLabelText('Otwórz menu'))
    const mobileNav = () => screen.getByLabelText('Główna nawigacja (mobilna)')

    open()
    expect(mobileNav()).toHaveAttribute('aria-hidden', 'false')
    expect(document.body.style.overflow).toBe('hidden')
    fireEvent.keyDown(document, { key: 'Enter' })
    expect(mobileNav()).toHaveAttribute('aria-hidden', 'false')
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(mobileNav()).toHaveAttribute('aria-hidden', 'true')
    expect(document.body.style.overflow).toBe('')

    open()
    act(() => listeners.forEach((fn) => fn({ matches: false })))
    expect(mobileNav()).toHaveAttribute('aria-hidden', 'false')
    act(() => listeners.forEach((fn) => fn({ matches: true })))
    expect(mobileNav()).toHaveAttribute('aria-hidden', 'true')

    open()
    fireEvent.click(container.querySelector('[aria-hidden="true"]:last-child') as Element)
    expect(mobileNav()).toHaveAttribute('aria-hidden', 'true')

    open()
    fireEvent.click(screen.getByLabelText('Zamknij menu'))
    expect(mobileNav()).toHaveAttribute('aria-hidden', 'true')

    open()
    fireEvent.click(mobileNav().querySelector('a') as Element)
    expect(mobileNav()).toHaveAttribute('aria-hidden', 'true')
    open()
    fireEvent.click(screen.getByText('DeepfakeDetector'))
    expect(mobileNav()).toHaveAttribute('aria-hidden', 'true')
  })
})
