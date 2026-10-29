import { useState } from 'react'

import { useNavigate } from 'react-router-dom'

import { Trash2 } from 'lucide-react'

import { deleteAnalysisRecord } from '@/api/analysis'
import { ApiError } from '@/api/errors'
import type { Analysis } from '@/api/types'
import { Alert } from '@/components/ui/Alert/Alert'
import { Button } from '@/components/ui/Button/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog/ConfirmDialog'

export function DeleteAnalysisButton({ analysis }: { analysis: Pick<Analysis, 'id' | 'fileId'> }) {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const leave = () => navigate('/history', { replace: true })

  function close() {
    if (deleting) return
    setOpen(false)
    setError(null)
  }

  async function handleDelete() {
    setDeleting(true)
    setError(null)
    try {
      await deleteAnalysisRecord(analysis.id)
      leave()
    } catch (err) {
      if (err instanceof ApiError && err.isNotFound) {
        leave()
        return
      }
      setError(
        err instanceof ApiError && err.isConflict
          ? 'Analiza jest jeszcze w toku — anuluj ją przed usunięciem.'
          : 'Nie udało się usunąć analizy. Spróbuj ponownie.',
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
        title="Usunąć analizę?"
        confirmLabel="Usuń analizę"
        danger
        isLoading={deleting}
        confirmIcon={Trash2}
        onConfirm={handleDelete}
        onCancel={close}
      >
        <p>
          Ta operacja jest nieodwracalna — analiza, raport i heatmapy Grad-CAM znikną z Twojej
          historii. Przesłany plik zostanie zwolniony, gdy nie korzysta z niego żadna inna analiza.
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
