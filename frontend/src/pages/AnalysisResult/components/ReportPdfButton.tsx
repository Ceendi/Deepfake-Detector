import { useEffect, useRef, useState } from 'react'

import { Download } from 'lucide-react'

import { fetchReportPdf } from '@/api/analysis'
import { ApiError } from '@/api/errors'
import type { AnalysisStatus } from '@/api/types'
import { Button } from '@/components/ui/Button/Button'

import styles from '../AnalysisResult.module.css'

function reportErrorMessage(err: unknown): string {
  if (err instanceof ApiError && err.isConflict) return 'Raport jeszcze niegotowy.'
  if (err instanceof ApiError && err.isNotFound) return 'Nie znaleziono analizy.'
  return 'Nie udało się pobrać raportu. Spróbuj ponownie.'
}

// Pobranie raportu PDF: endpoint wymaga Bearer, więc fetch → Blob → tymczasowy <a download>.
// Raport istnieje tylko dla COMPLETED; dla analizy w toku przycisk jest wyłączony.
export function ReportPdfButton({
  analysisId,
  status,
  variant,
  label,
}: {
  analysisId: string
  status: AnalysisStatus
  variant: 'primary' | 'ghost'
  label: string
}) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const ctrl = useRef<AbortController | null>(null)

  useEffect(() => () => ctrl.current?.abort(), [])

  const ready = status === 'COMPLETED'

  async function download() {
    ctrl.current?.abort()
    const controller = new AbortController()
    ctrl.current = controller
    setLoading(true)
    setError(null)
    try {
      const { blob, filename } = await fetchReportPdf(analysisId, controller.signal)
      if (controller.signal.aborted) return
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = filename
      document.body.appendChild(link)
      link.click()
      link.remove()
      // Revoke po bieżącym tasku — część przeglądarek startuje pobieranie asynchronicznie.
      setTimeout(() => URL.revokeObjectURL(url), 0)
    } catch (err) {
      if (controller.signal.aborted) return
      setError(reportErrorMessage(err))
    } finally {
      if (!controller.signal.aborted) setLoading(false)
    }
  }

  return (
    <span className={styles.pdfWrap}>
      <Button
        variant={variant}
        size="md"
        leftIcon={Download}
        isLoading={loading}
        disabled={!ready}
        title={ready ? undefined : 'Raport będzie dostępny po zakończeniu analizy'}
        onClick={() => void download()}
      >
        {label}
      </Button>
      {error && (
        <span className={styles.pdfError} role="alert">
          {error}
        </span>
      )}
    </span>
  )
}
