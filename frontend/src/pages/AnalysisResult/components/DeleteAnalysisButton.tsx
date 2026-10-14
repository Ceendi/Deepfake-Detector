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

// Zwolnienie pliku w kwocie użytkownika po usunięciu rekordu. 404 = plik już usunięty (cel osiągnięty).
async function releaseFile(fileId: string): Promise<void> {
  try {
    await deleteFile(fileId)
  } catch (err) {
    if (!(err instanceof ApiError && err.isNotFound)) throw err
  }
}

// Trwałe usunięcie zakończonej analizy (COMPLETED/FAILED/CANCELLED) razem z jej plikiem.
export function DeleteAnalysisButton({ analysis }: { analysis: Pick<Analysis, 'id' | 'fileId'> }) {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Rekord już usunięty, ale plik nie — ponowienie zwalnia tylko plik (rekordu nie da się usunąć 2×).
  const [recordDeleted, setRecordDeleted] = useState(false)

  const leave = () => navigate('/history', { replace: true }) // „wstecz" nie wraca na usunięty zasób

  function close() {
    if (deleting) return // nie zamykaj w trakcie żądania
    if (recordDeleted) return leave() // analizy już nie ma — strona wyniku jest nieaktualna
    setOpen(false)
    setError(null)
  }

  async function handleDelete() {
    setDeleting(true)
    setError(null)
    if (!recordDeleted) {
      try {
        // Rekord najpierw: 409 (analiza w toku) nie może zostawić działającej analizy bez pliku.
        await deleteAnalysisRecord(analysis.id)
      } catch (err) {
        // 404 = już usunięta (lub cudza) → efekt docelowy ten sam, ale plik nadal zwalniamy.
        if (!(err instanceof ApiError && err.isNotFound)) {
          setError(
            err instanceof ApiError && err.isConflict
              ? 'Analiza jest jeszcze w toku — anuluj ją przed usunięciem.'
              : 'Nie udało się usunąć analizy. Spróbuj ponownie.',
          )
          setDeleting(false) // zostań w modalu, pozwól ponowić
          return
        }
      }
      setRecordDeleted(true)
    }
    try {
      await releaseFile(analysis.fileId)
      leave()
    } catch {
      setError(
        'Analiza została usunięta, ale nie udało się zwolnić miejsca zajmowanego przez plik. Spróbuj ponownie.',
      )
      setDeleting(false)
    }
  }

  return (
    <>
      <Button variant="danger" size="md" leftIcon={Trash2} onClick={() => setOpen(true)}>
        Usuń analizę
      </Button>

      <ConfirmDialog
        open={open}
        title={recordDeleted ? 'Zwolnić miejsce po pliku?' : 'Usunąć analizę?'}
        confirmLabel={recordDeleted ? 'Spróbuj ponownie' : 'Usuń analizę'}
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
