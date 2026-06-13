import { SearchX, X } from 'lucide-react'

import { EmptyState } from '@/components/ui/EmptyState'
import { Button } from '@/components/ui/Button/Button'

// Stan „brak wyników": użytkownik MA analizy, ale aktywne filtry nic nie zwracają. Dajemy wyjście.
export function HistoryNoResults({ onClear }: { onClear: () => void }) {
  return (
    <EmptyState
      icon={SearchX}
      variant="dropzone"
      title="Brak wyników dla tych filtrów"
      actions={
        <Button variant="ghost" size="md" leftIcon={X} onClick={onClear}>
          Wyczyść filtry
        </Button>
      }
    >
      Żadna analiza nie pasuje do wybranych kryteriów. Spróbuj poszerzyć zakres lub wyczyść filtry,
      aby zobaczyć wszystkie analizy.
    </EmptyState>
  )
}
