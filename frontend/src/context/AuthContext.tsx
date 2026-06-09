import { useEffect, useState, type ReactNode } from 'react'

import { currentUser, keycloak, login, logout, register, type AuthUser } from '@/auth/keycloak'
import { initAuth } from '@/auth/initKeycloak'

import { AuthContext, type AuthState } from './auth-context'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false)
  const [isLoading, setIsLoading] = useState(true)
  const [user, setUser] = useState<AuthUser | null>(null)

  // Bootstrap: check-sso ustala, czy mamy aktywną sesję. `active` zabezpiecza przed
  // setState po odmontowaniu (StrictMode montuje 2×; initAuth i tak jest idempotentne).
  useEffect(() => {
    let active = true
    initAuth()
      .then((authenticated) => {
        if (!active) return
        setIsAuthenticated(authenticated)
        setUser(authenticated ? currentUser() : null)
      })
      .catch(() => {
        if (active) {
          setIsAuthenticated(false)
          setUser(null)
        }
      })
      .finally(() => {
        if (active) setIsLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  // Reakcja na zdarzenia keycloak-js: idle-refresh tokena oraz utrata sesji.
  // (Aktywne żądania i tak odświeżają token przez getToken() w client.ts.)
  useEffect(() => {
    keycloak.onTokenExpired = () => {
      void keycloak.updateToken(30).catch(() => {
        setIsAuthenticated(false)
        setUser(null)
      })
    }
    keycloak.onAuthSuccess = () => {
      setIsAuthenticated(true)
      setUser(currentUser())
    }
    keycloak.onAuthLogout = () => {
      setIsAuthenticated(false)
      setUser(null)
    }
    return () => {
      keycloak.onTokenExpired = undefined
      keycloak.onAuthSuccess = undefined
      keycloak.onAuthLogout = undefined
    }
  }, [])

  const value: AuthState = {
    isAuthenticated,
    isLoading,
    user,
    login,
    logout,
    register,
  }

  return <AuthContext value={value}>{children}</AuthContext>
}
