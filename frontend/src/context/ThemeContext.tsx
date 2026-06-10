import { useEffect, useState, type ReactNode } from 'react'

import { ThemeContext, type Theme, type ThemeState } from './theme-context'

// UWAGA: ten sam klucz jest użyty w inline-script w index.html (anty-FOUC). Zmiana → sync obu miejsc.
export const THEME_STORAGE_KEY = 'deepfake-theme'

function readInitialTheme(): Theme {
  // data-theme jest ustawiony przez inline-script jeszcze przed renderem — to nasze źródło prawdy.
  const fromDom = document.documentElement.dataset.theme
  if (fromDom === 'light' || fromDom === 'dark') return fromDom

  // Fallback (gdyby skryptu zabrakło): localStorage → preferencja systemowa.
  const stored = localStorage.getItem(THEME_STORAGE_KEY)
  if (stored === 'light' || stored === 'dark') return stored
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useState<Theme>(readInitialTheme)

  // Jedyny efekt uboczny: atrybut na <html> (tokeny reagują na [data-theme]) + zapis preferencji.
  useEffect(() => {
    document.documentElement.dataset.theme = theme
    try {
      localStorage.setItem(THEME_STORAGE_KEY, theme)
    } catch {
      // Tryb prywatny / brak dostępu do storage — motyw zadziała, ale nie przetrwa odświeżenia.
    }
  }, [theme])

  const value: ThemeState = {
    theme,
    setTheme,
    toggleTheme: () => setTheme((prev) => (prev === 'dark' ? 'light' : 'dark')),
  }

  return <ThemeContext value={value}>{children}</ThemeContext>
}
