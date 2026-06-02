import { type ReactNode } from 'react'
import { AuthContext, type AuthState } from './auth-context'

export function AuthProvider({ children }: { children: ReactNode }) {
  const value: AuthState = {
    isAuthenticated: true,
    isLoading: false,
    login: () => {},
    logout: () => {},
  }

  return <AuthContext value={value}>{children}</AuthContext>
}
