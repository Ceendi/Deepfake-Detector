import { FolderPlus, UploadCloud } from 'lucide-react'

import { EmptyState } from '@/components/ui/EmptyState'
import { LinkButton } from '@/components/ui/LinkButton/LinkButton'

// Stan pusty: użytkownik nie ma jeszcze ŻADNEJ analizy — onboarding zamiast listy.
export function HistoryEmpty() {
  return (
    <EmptyState
      icon={FolderPlus}
      variant="dropzone"
      title="Brak analiz w historii"
      actions={
        <LinkButton to="/upload" variant="primary" size="md" leftIcon={UploadCloud}>
          Wgraj pierwszy plik
        </LinkButton>
      }
    >
      Nie wykonałeś jeszcze żadnej analizy. Wgraj pierwszy plik wideo lub audio, aby sprawdzić jego
      autentyczność.
    </EmptyState>
  )
}
