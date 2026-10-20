import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { THEME_STORAGE_KEY, ThemeProvider } from './ThemeContext'
import { useTheme } from './theme-context'

function Probe() {
  const { theme, toggleTheme, setTheme } = useTheme()
  return (
    <>
      <span data-testid="theme">{theme}</span>
      <button onClick={toggleTheme}>toggle</button>
      <button onClick={() => setTheme('light')}>light</button>
    </>
  )
}

function stubPrefersDark(matches: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches })),
  )
}

beforeEach(() => {
  delete document.documentElement.dataset.theme
  localStorage.clear()
})

afterEach(() => vi.unstubAllGlobals())

describe('ThemeProvider', () => {
  it('prefers the theme already set on the document by the pre-paint script', () => {
    document.documentElement.dataset.theme = 'dark'
    localStorage.setItem(THEME_STORAGE_KEY, 'light')
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    )
    expect(screen.getByTestId('theme')).toHaveTextContent('dark')
  })

  it('falls back to the stored theme, then to the system preference', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'light')
    stubPrefersDark(true)
    const { unmount } = render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    )
    expect(screen.getByTestId('theme')).toHaveTextContent('light')
    unmount()

    delete document.documentElement.dataset.theme
    localStorage.clear()
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    )
    expect(screen.getByTestId('theme')).toHaveTextContent('dark')
  })

  it('toggles, persists and applies the theme to the document', () => {
    stubPrefersDark(false)
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    )
    expect(screen.getByTestId('theme')).toHaveTextContent('light')

    act(() => screen.getByText('toggle').click())
    expect(screen.getByTestId('theme')).toHaveTextContent('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')

    act(() => screen.getByText('light').click())
    expect(document.documentElement.dataset.theme).toBe('light')
  })

  it('keeps working when storage is unavailable', () => {
    stubPrefersDark(false)
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota')
    })
    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    )
    act(() => screen.getByText('toggle').click())
    expect(document.documentElement.dataset.theme).toBe('dark')
    setItem.mockRestore()
  })

  it('requires a provider', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() => render(<Probe />)).toThrow(/ThemeProvider/)
  })
})
