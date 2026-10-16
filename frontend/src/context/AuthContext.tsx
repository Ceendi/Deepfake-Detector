import { useEffect, useState, type ReactNode } from 'react'

import { currentUser, keycloak, login, logout, register, type AuthUser } from '@/auth/keycloak'
import { initAuth } from '@/auth/initKeycloak'
import { createTokenRefresher } from '@/auth/token-refresh'

import { AuthContext, type AuthState } from './auth-context'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false)
  const [isLoading, setIsLoading] = useState(true)
  const [user, setUser] = useState<AuthUser | null>(null)

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

  useEffect(() => {
    const refresher = createTokenRefresher(keycloak, () => {
      setIsAuthenticated(false)
      setUser(null)
    })
    keycloak.onTokenExpired = refresher.refresh
    keycloak.onAuthSuccess = () => {
      setIsAuthenticated(true)
      setUser(currentUser())
    }
    keycloak.onAuthLogout = () => {
      setIsAuthenticated(false)
      setUser(null)
    }
    return () => {
      refresher.stop()
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
    logout: () => {
      setIsAuthenticated(false)
      setUser(null)
      logout()
    },
    register,
  }

  return <AuthContext value={value}>{children}</AuthContext>
}
