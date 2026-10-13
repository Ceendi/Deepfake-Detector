import { useState } from 'react'

import { useNavigate } from 'react-router-dom'

import { Trash2 } from 'lucide-react'

import { deleteAnalysisRecord } from '@/api/analysis'
import { ApiError } from '@/api/errors'
import { deleteFile } from '@/api/files'
import type { Analysis } from '@/api/types'
import { Alert } from '@/components/ui/Alert/Alert'
import { Button } from '@/components/ui/Button/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog/ConfirmDialog'

// Zwolnienie pliku w kwocie użytkownika po usunięciu rekordu. 404 = plik już usunięty; inne błędy
// nie cofają usuniętego rekordu, więc są pomijane (best effort).
function releaseFile(fileId: string): Promise<void> {
  return deleteFile(fileId).catch(() => undefined)
}

// Trwałe usunięcie zakończonej analizy (COMPLETED/FAILED/CANCELLED) razem z jej plikiem.
export function DeleteAnalysisButton({ analysis }: { analysis: Pick<Analysis, 'id' | 'fileId'> }) {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function close() {
    if (deleting) return // nie zamykaj w trakcie żądania
    setOpen(false)
    setError(null)
  }

  async function handleDelete() {
    setDeleting(true)
    setError(null)
    try {
      // Rekord najpierw: 409 (analiza w toku) nie może zostawić działającej analizy bez pliku.
      await deleteAnalysisRecord(analysis.id)
      await releaseFile(analysis.fileId)
      navigate('/history', { replace: true }) // replace — „wstecz" nie wraca na usunięty zasób
    } catch (err) {
      // 404 = już usunięta (lub cudza) → efekt docelowy ten sam, ale plik nadal zwalniamy.
      if (err instanceof ApiError && err.isNotFound) {
        await releaseFile(analysis.fileId)
        navigate('/history', { replace: true })
        return
      }
      setError(
        err instanceof ApiError && err.isConflict
          ? 'Analiza jest jeszcze w toku — anuluj ją przed usunięciem.'
          : 'Nie udało się usunąć analizy. Spróbuj ponownie.',
      )
      setDeleting(false) // zostań w modalu, pozwól ponowić
    }
  }

  return (
    <>
      <Button variant="danger" size="md" leftIcon={Trash2} onClick={() => setOpen(true)}>
        Usuń analizę
      </Button>

      <ConfirmDialog
        open={open}
        title="Usunąć analizę?"
        confirmLabel="Usuń analizę"
        danger
        isLoading={deleting}
        confirmIcon={Trash2}
        onConfirm={handleDelete}
        onCancel={close}
      >
        <p>
          Ta operacja jest nieodwracalna — analiza zniknie z Twojej historii, a wraz z nią przesłany
          plik, raport i heatmapy Grad-CAM.
        </p>
        {error && (
          <Alert variant="danger" title="Nie udało się usunąć">
            {error}
          </Alert>
        )}
      </ConfirmDialog>
    </>
  )
}
