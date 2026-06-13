import { createContext, useContext } from 'react'

export interface FieldContextValue {
  id: string
  describedBy: string | undefined
  invalid: boolean
}

export const FieldContext = createContext<FieldContextValue | null>(null)

// Wiring dla kontrolki w środku FormField (Input, później Select/Textarea).
// Zwraca null, gdy kontrolka użyta poza FormField — wtedy działa standalone.
export function useFieldControl() {
  return useContext(FieldContext)
}
