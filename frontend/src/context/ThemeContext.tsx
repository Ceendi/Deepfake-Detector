import { useEffect, useState, type ReactNode } from 'react'

import { ThemeContext, type Theme, type ThemeState } from './theme-context'

// Keep this key aligned with the pre-paint script in index.html.
export const THEME_STORAGE_KEY = 'deepfake-theme'

function readInitialTheme(): Theme {
  const fromDom = document.documentElement.dataset.theme
  if (fromDom === 'light' || fromDom === 'dark') return fromDom

  const stored = localStorage.getItem(THEME_STORAGE_KEY)
  if (stored === 'light' || stored === 'dark') return stored
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useState<Theme>(readInitialTheme)

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    try {
      localStorage.setItem(THEME_STORAGE_KEY, theme)
    } catch {
      // Storage may be unavailable; the current theme still works.
    }
  }, [theme])

  const value: ThemeState = {
    theme,
    setTheme,
    toggleTheme: () => setTheme((prev) => (prev === 'dark' ? 'light' : 'dark')),
  }

  return <ThemeContext value={value}>{children}</ThemeContext>
}
