import { createContext, useContext } from 'react'

export interface FieldContextValue {
  id: string
  describedBy: string | undefined
  invalid: boolean
}

export const FieldContext = createContext<FieldContextValue | null>(null)

export function useFieldControl() {
  return useContext(FieldContext)
}
