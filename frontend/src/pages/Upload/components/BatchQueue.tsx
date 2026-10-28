import { Link } from 'react-router-dom'

import { ArrowRight, AudioLines, ExternalLink, Video, X } from 'lucide-react'
import { clsx } from 'clsx'

import { Badge } from '@/components/ui/Badge/Badge'
import { Button } from '@/components/ui/Button/Button'
import { ProgressBar } from '@/components/ui/ProgressBar/ProgressBar'

import { BATCH_LIMITS, formatMB, isVideoFile, pluralPl } from '../upload-utils'
import type { BatchItem } from '../use-batch-queue'
import styles from '../Upload.module.css'

// More names would push the queue itself off screen.
const SKIPPED_NAMES_SHOWN = 5

interface BatchQueueProps {
  items: BatchItem[]
  skipped: string[]
  started: boolean
  finished: boolean
  cancelling: boolean
  onStart: () => void
  onCancelAll: () => void
  onReset: () => void
}

function statusLabel(item: BatchItem): string {
  switch (item.status) {
    case 'pending':
      return 'Czeka'
    case 'uploading':
      return (item.progress ?? 0) < 100 ? 'Wysyłanie' : 'Przetwarzanie pliku'
    case 'queued':
      return 'W kolejce do analizy'
    case 'analyzing':
      return 'Analiza'
    case 'completed':
      return 'Gotowe'
    case 'failed':
      return 'Błąd'
    case 'cancelled':
      return 'Anulowano'
    case 'detached':
      return 'Analiza nadal trwa'
  }
}

function skippedNames(names: string[]): string {
  const shown = names.slice(0, SKIPPED_NAMES_SHOWN).join(', ')
  const rest = names.length - SKIPPED_NAMES_SHOWN
  return rest > 0 ? `${shown} i ${rest} ${pluralPl(rest, 'inny', 'inne', 'innych')}` : shown
}

function BatchRow({ item }: { item: BatchItem }) {
  const { file, status, progress } = item
  const Icon = isVideoFile(file) ? Video : AudioLines
  const active = status === 'uploading' || status === 'analyzing'
  const pct = active && progress !== undefined && progress < 100 ? Math.round(progress) : undefined

  return (
    <li className={styles.batchRow} aria-label={file.name}>
      <span className={styles.fileIcon} aria-hidden="true">
        <Icon size={20} strokeWidth={2} />
      </span>

      <div className={styles.fileMain}>
        <div className={styles.fileName} title={file.name}>
          {file.name}
        </div>
        <div className={styles.fileMeta}>{formatMB(file.size)} MB</div>
        {item.message && (
          <div className={clsx(styles.batchMessage, status === 'failed' && styles.batchError)}>
            {item.message}
          </div>
        )}
      </div>

      <div className={styles.batchStatus}>
        <span className={clsx(styles.batchLabel, status === 'failed' && styles.batchError)}>
          {statusLabel(item)}
          {pct !== undefined && <span className={styles.progressPct}> {pct}%</span>}
        </span>
        {status === 'completed' && item.verdict && (
          <Badge variant={item.verdict === 'FAKE' ? 'danger' : 'success'} size="sm" soft>
            {item.verdict}
          </Badge>
        )}
        {active && <ProgressBar className={styles.batchProgress} value={pct} />}
      </div>

      {item.analysisId && (
        // A new tab keeps this page, and with it the queue, alive.
        <Link
          to={`/analysis-result/${item.analysisId}`}
          target="_blank"
          rel="noreferrer"
          className={styles.batchLink}
          aria-label={`Wynik: ${file.name}`}
        >
          Wynik
          <ExternalLink size={14} strokeWidth={2.4} aria-hidden="true" />
        </Link>
      )}
    </li>
  )
}

export function BatchQueue({
  items,
  skipped,
  started,
  finished,
  cancelling,
  onStart,
  onCancelAll,
  onReset,
}: BatchQueueProps) {
  const ready = items.filter((item) => item.status === 'pending').length
  const completed = items.filter((item) => item.status === 'completed').length
  const failed = items.filter((item) => item.status === 'failed').length

  return (
    <div className={styles.previewCard}>
      <div className={styles.batchHead}>
        <h2 className={styles.batchTitle}>
          {items.length} {pluralPl(items.length, 'plik', 'pliki', 'plików')}
        </h2>
        <span className={styles.batchSummary} role="status">
          {completed}/{items.length} gotowe
          {failed > 0 && ` · ${failed} z błędem`}
        </span>
      </div>

      {skipped.length > 0 && (
        <p className={styles.batchNote}>
          Pominięto {skipped.length} {pluralPl(skipped.length, 'plik', 'pliki', 'plików')} spoza
          obsługiwanych formatów: {skippedNames(skipped)}
        </p>
      )}

      <ul className={styles.batchList}>
        {items.map((item) => (
          <BatchRow key={item.key} item={item} />
        ))}
      </ul>

      <div className={styles.footer}>
        <span className={styles.footerNote}>
          {!started
            ? `Pliki trafią do kolejki: ${BATCH_LIMITS.uploads} wysyłane naraz, do ${BATCH_LIMITS.analyses} analiz jednocześnie.`
            : finished
              ? 'Kolejka zakończona.'
              : 'Nie zamykaj karty do zakończenia kolejki.'}
        </span>
        <div className={styles.actions}>
          {!started ? (
            <>
              <Button variant="ghost" size="md" onClick={onReset}>
                Usuń / wybierz inne
              </Button>
              <Button
                variant="primary"
                size="md"
                rightIcon={ArrowRight}
                onClick={onStart}
                disabled={ready === 0}
              >
                Analizuj wszystkie ({ready})
              </Button>
            </>
          ) : finished ? (
            <Button variant="primary" size="md" onClick={onReset}>
              Wybierz kolejne pliki
            </Button>
          ) : (
            <Button
              variant="ghost"
              size="md"
              leftIcon={X}
              onClick={onCancelAll}
              isLoading={cancelling}
            >
              Anuluj wszystko
            </Button>
          )}
        </div>
      </div>
    </div>
  )
}
