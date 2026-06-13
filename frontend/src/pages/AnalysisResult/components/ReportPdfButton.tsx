import { Download } from 'lucide-react'

import { Button } from '@/components/ui/Button/Button'

import styles from '../AnalysisResult.module.css'

// Raport PDF dochodzi w V2 (endpoint /report.pdf istnieje, ale UI pobierania robimy później).
// Na razie przycisk `disabled` z plakietką „V2", żeby odwzorować makietę.
export function ReportPdfButton({
  variant,
  label,
}: {
  variant: 'primary' | 'ghost'
  label: string
}) {
  return (
    <span className={styles.pdfWrap}>
      <Button variant={variant} size="md" leftIcon={Download} disabled title="Dostępne w wersji V2">
        {label}
      </Button>
      <span className={styles.v2Badge} aria-hidden="true">
        V2
      </span>
    </span>
  )
}
