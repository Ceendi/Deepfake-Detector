import { useEffect, useRef, useState } from 'react'

import { cancelAnalysis, startAnalysis } from '@/api/analysis'
import { CANCEL_FAILED_MESSAGE, cancelAndSettle } from '@/api/cancel-analysis'
import { analysisFailureMessage } from '@/api/error-codes'
import { ApiError } from '@/api/errors'
import { uploadFileWithProgress } from '@/api/files'
import type { Analysis, AnalysisProgressEvent, AnalysisType, Verdict } from '@/api/types'
import { AnalysisAgeLimitError, watchAnalysis } from '@/api/watch-analysis'

import { probeDuration } from './media-duration'
import {
  BATCH_LIMITS,
  messageForError,
  pickAnalysisType,
  validateDuration,
  validateFile,
} from './upload-utils'

// For 429/503 without Retry-After (e.g. the gateway's 503 while its Redis is down).
const BACKOFF_MS = [5000, 10000, 20000, 40000, 60000]

export type BatchStatus =
  | 'pending'
  | 'uploading'
  | 'queued' // uploaded, waiting for an analysis slot
  | 'analyzing'
  | 'completed'
  | 'failed'
  | 'cancelled'
  | 'detached' // still running on the server, no longer watched here

export interface BatchItem {
  key: string
  file: File
  status: BatchStatus
  progress?: number // upload or analysis, 0..100
  analysisId?: string
  verdict?: Verdict | null
  message?: string
}

type Step = 'upload' | 'analysis'
type Source = AnalysisProgressEvent['source']

interface Job {
  durationChecked?: boolean
  uploaded?: { fileId: string; type: AnalysisType }
  controller?: AbortController
  bySource: Partial<Record<Source, number>>
}

const TERMINAL: ReadonlySet<BatchStatus> = new Set(['completed', 'failed', 'cancelled', 'detached'])

export function isTerminal(status: BatchStatus): boolean {
  return TERMINAL.has(status)
}

function waitMessage(ms: number): string {
  return `Serwer jest zajęty — ponowna próba za ${Math.ceil(ms / 1000)} s.`
}

function sourcesOf(type: AnalysisType | undefined): Source[] {
  if (type === 'AUDIO') return ['audio']
  if (type === 'VIDEO') return ['video']
  return ['video', 'audio']
}

/**
 * Uploads and analyses many files with at most BATCH_LIMITS in flight. A 429/503 pauses that step
 * for Retry-After (or a capped backoff) and retries the same file; other errors fail only that file.
 */
export function useBatchQueue() {
  const [items, setItems] = useState<BatchItem[]>([])
  const [skipped, setSkipped] = useState<string[]>([])
  const [started, setStarted] = useState(false)
  const [cancelling, setCancelling] = useState(0)

  const itemsRef = useRef<BatchItem[]>([])
  const jobsRef = useRef(new Map<string, Job>())
  // One controller per run; unmount aborts it without cancelling analyses (they stay in history).
  const runRef = useRef<AbortController | null>(null)
  const cancelledRef = useRef(false)
  const pausedRef = useRef<Record<Step, boolean>>({ upload: false, analysis: false })
  const attemptsRef = useRef<Record<Step, number>>({ upload: 0, analysis: 0 })
  const timersRef = useRef(new Set<ReturnType<typeof setTimeout>>())

  useEffect(() => {
    const timers = timersRef.current
    return () => {
      runRef.current?.abort()
      timers.forEach(clearTimeout)
    }
  }, [])

  function commit(next: BatchItem[]) {
    itemsRef.current = next
    setItems(next)
  }

  function update(key: string, patch: Partial<BatchItem>) {
    commit(itemsRef.current.map((item) => (item.key === key ? { ...item, ...patch } : item)))
  }

  function fail(key: string, message: string) {
    update(key, { status: 'failed', progress: undefined, message })
  }

  function jobOf(key: string): Job {
    let job = jobsRef.current.get(key)
    if (!job) {
      job = { bySource: {} }
      jobsRef.current.set(key, job)
    }
    return job
  }

  function stop() {
    runRef.current?.abort()
    runRef.current = null
    timersRef.current.forEach(clearTimeout)
    timersRef.current.clear()
  }

  function add(files: File[], skippedNames: string[] = []) {
    stop()
    jobsRef.current.clear()
    commit(
      files.map((file, index): BatchItem => {
        const message = validateFile(file)
        return message
          ? { key: `${index}:${file.name}`, file, status: 'failed', message }
          : { key: `${index}:${file.name}`, file, status: 'pending' }
      }),
    )
    setSkipped(skippedNames)
    setStarted(false)
  }

  function reset() {
    add([])
  }

  function start() {
    if (runRef.current) return
    runRef.current = new AbortController()
    cancelledRef.current = false
    pausedRef.current = { upload: false, analysis: false }
    attemptsRef.current = { upload: 0, analysis: 0 }
    setStarted(true)
    pump()
  }

  // Fill free slots in list order; called whenever a slot frees or a pause ends.
  function pump() {
    const run = runRef.current
    if (!run || run.signal.aborted || cancelledRef.current) return
    const list = itemsRef.current
    const count = (status: BatchStatus) => list.filter((item) => item.status === status).length
    let analyses = count('analyzing')
    // Uploaded files not yet analysed hold quota and become orphans on cancel or leaving the page,
    // so uploads run at most one analysis batch ahead.
    let backlog = count('uploading') + count('queued')
    let uploads = count('uploading')

    for (const item of list) {
      if (item.status !== 'queued' || analyses >= BATCH_LIMITS.analyses) continue
      if (pausedRef.current.analysis) break
      analyses += 1
      backlog -= 1
      void runAnalysis(item.key, run.signal)
    }
    for (const item of list) {
      if (item.status !== 'pending') continue
      if (
        uploads >= BATCH_LIMITS.uploads ||
        backlog >= BATCH_LIMITS.analyses ||
        pausedRef.current.upload
      ) {
        break
      }
      uploads += 1
      backlog += 1
      void runUpload(item, run.signal)
    }
  }

  function resumeAfter(step: Step, ms: number) {
    pausedRef.current[step] = true
    const timer = setTimeout(() => {
      timersRef.current.delete(timer)
      pausedRef.current[step] = false
      pump()
    }, ms)
    timersRef.current.add(timer)
  }

  // Returns the wait for a temporary rejection, or null when the error is final for this file.
  function pauseFor(step: Step, error: unknown): number | null {
    if (!(error instanceof ApiError) || (error.status !== 429 && error.status !== 503)) return null
    const seconds = error.retryAfterSeconds ?? error.backpressure?.retryAfterSeconds
    const attempt = attemptsRef.current[step]++
    const ms =
      seconds !== undefined
        ? Math.max(seconds, 1) * 1000
        : BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)]
    // A parallel rejection of the same step joins the wait that is already running.
    if (!pausedRef.current[step]) resumeAfter(step, ms)
    return ms
  }

  async function runUpload(item: BatchItem, runSignal: AbortSignal) {
    const { key, file } = item
    const job = jobOf(key)
    const controller = new AbortController()
    job.controller = controller
    const signal = AbortSignal.any([runSignal, controller.signal])
    update(key, { status: 'uploading', progress: 0, message: undefined })

    try {
      if (!job.durationChecked) {
        const seconds = await probeDuration(file, signal)
        signal.throwIfAborted()
        const tooLong = seconds === null ? null : validateDuration(seconds)
        if (tooLong) return fail(key, tooLong)
        job.durationChecked = true
      }

      const { fileId, mimetype } = await uploadFileWithProgress(file, {
        signal,
        onProgress: (pct) => {
          if (!signal.aborted) update(key, { progress: pct })
        },
      })
      signal.throwIfAborted()
      attemptsRef.current.upload = 0
      job.uploaded = { fileId, type: pickAnalysisType(mimetype) }
      update(key, { status: 'queued', progress: undefined })
    } catch (error) {
      if (signal.aborted) return // "cancel all" or unmount already decided the item's state
      const wait = pauseFor('upload', error)
      if (wait === null) fail(key, messageForError(error))
      else update(key, { status: 'pending', progress: undefined, message: waitMessage(wait) })
    } finally {
      pump()
    }
  }

  async function runAnalysis(key: string, runSignal: AbortSignal) {
    const job = jobOf(key)
    const uploaded = job.uploaded
    if (!uploaded) return
    const controller = new AbortController()
    job.controller = controller
    job.bySource = {}
    const signal = AbortSignal.any([runSignal, controller.signal])
    update(key, { status: 'analyzing', progress: undefined, message: undefined })

    let analysis: Analysis
    try {
      analysis = await startAnalysis(uploaded)
    } catch (error) {
      if (signal.aborted) return
      const wait = pauseFor('analysis', error)
      if (wait === null) fail(key, messageForError(error))
      else update(key, { status: 'queued', message: waitMessage(wait) })
      pump()
      return
    }
    if (signal.aborted) {
      // The POST raced "cancel all"; on unmount the analysis just stays in history.
      if (cancelledRef.current) void cancelAnalysis(analysis.id).catch(() => {})
      return
    }
    attemptsRef.current.analysis = 0
    update(key, { analysisId: analysis.id })

    const sources = sourcesOf(uploaded.type)
    void watchAnalysis(
      analysis.id,
      {
        onProgress: (event) => {
          job.bySource[event.source] = event.progress
          const sum = sources.reduce((total, source) => total + (job.bySource[source] ?? 0), 0)
          update(key, { progress: sum / sources.length })
        },
        onResult: (result) => {
          if (!signal.aborted) settle(key, result)
        },
      },
      signal,
      { createdAt: analysis.createdAt },
    ).catch((error) => {
      if (signal.aborted) return
      if (error instanceof ApiError && error.isNotFound) {
        fail(key, messageForError(error))
      } else {
        // Frees the client slot; a server still at its limit answers 429, which is waited out.
        update(key, {
          status: 'detached',
          progress: undefined,
          message:
            error instanceof AnalysisAgeLimitError
              ? 'Przekroczono czas oczekiwania. Sprawdź stan na stronie analizy.'
              : 'Sprawdź stan na stronie analizy.',
        })
      }
      pump()
    })
  }

  function settle(key: string, result: Analysis) {
    const item = itemsRef.current.find((candidate) => candidate.key === key)
    if (item?.status !== 'analyzing') return
    jobsRef.current.get(key)?.controller?.abort()
    if (result.status === 'COMPLETED') {
      update(key, { status: 'completed', progress: undefined, verdict: result.verdict })
    } else if (result.status === 'CANCELLED') {
      update(key, { status: 'cancelled', progress: undefined, message: undefined })
    } else {
      fail(key, analysisFailureMessage(result.errorMessage))
    }
    pump()
  }

  async function cancelStarted(key: string, analysisId: string, signal: AbortSignal | undefined) {
    setCancelling((n) => n + 1)
    update(key, { message: undefined })
    try {
      const settled = await cancelAndSettle(analysisId, signal)
      if (signal?.aborted) return
      if (settled) settle(key, settled) // null: already finishing, the open stream reports it
    } catch {
      if (signal?.aborted) return
      update(key, { message: CANCEL_FAILED_MESSAGE }) // still watched, so the result arrives
    } finally {
      setCancelling((n) => n - 1)
    }
  }

  function cancelAll() {
    cancelledRef.current = true
    timersRef.current.forEach(clearTimeout)
    timersRef.current.clear()
    const signal = runRef.current?.signal

    for (const item of itemsRef.current) {
      if (item.status === 'analyzing' && item.analysisId) {
        void cancelStarted(item.key, item.analysisId, signal)
      } else if (!isTerminal(item.status)) {
        // Pending, queued, uploading, or an analysis whose POST is still in flight.
        jobsRef.current.get(item.key)?.controller?.abort()
        update(item.key, { status: 'cancelled', progress: undefined, message: undefined })
      }
    }
  }

  const finished = started && items.every((item) => isTerminal(item.status))
  // Leaving the page drops files that have not reached an analysis yet.
  const sending =
    started &&
    items.some(
      (item) =>
        item.status === 'pending' || item.status === 'uploading' || item.status === 'queued',
    )

  return {
    items,
    skipped,
    started,
    finished,
    sending,
    cancelling: cancelling > 0,
    add,
    start,
    cancelAll,
    reset,
  }
}
