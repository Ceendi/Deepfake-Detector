import { createContext, useContext } from 'react'

export interface AuthState {
  isAuthenticated: boolean
  isLoading: boolean
  login: () => void
  logout: () => void
}

export const AuthContext = createContext<AuthState | null>(null)

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
