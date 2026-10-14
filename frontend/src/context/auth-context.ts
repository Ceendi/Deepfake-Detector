import { createContext, useContext } from 'react'

import type { AuthUser } from '@/auth/keycloak'

export interface AuthState {
  isAuthenticated: boolean
  isLoading: boolean
  user: AuthUser | null
  login: (redirectPath?: string) => void
  logout: () => void
  register: (redirectPath?: string) => void
}

export const AuthContext = createContext<AuthState | null>(null)

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
