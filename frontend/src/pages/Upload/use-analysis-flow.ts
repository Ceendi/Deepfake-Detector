import { useEffect, useRef, useState } from 'react'

import { uploadFileWithProgress } from '@/api/files'
import { startAnalysis, cancelAnalysis } from '@/api/analysis'
import { CANCEL_FAILED_MESSAGE, cancelAndSettle } from '@/api/cancel-analysis'
import { ApiError } from '@/api/errors'
import { watchAnalysis } from '@/api/watch-analysis'
import type { Analysis, AnalysisProgressEvent, AnalysisStatus } from '@/api/types'

import { isAbortError, messageForError, pickAnalysisType } from './upload-utils'

// --- Postęp analizy per źródło (dwa paski: video + audio) --------------------
type Source = AnalysisProgressEvent['source'] // 'video' | 'audio'
export interface SourceProgress {
  progress: number // 0..100
  stage: string // surowy stage z backendu — NA RAZIE bez mapowania na tekst (patrz STAGE_LABELS)
  status: AnalysisStatus
}
export type ProgressBySource = Partial<Record<Source, SourceProgress>>

// --- Faza cyklu analizy (po kliknięciu „Analizuj plik") ----------------------
export type FlowState =
  | { name: 'idle' } // jeszcze nie ruszył (PreviewState z „Analizuj plik")
  | { name: 'uploading' } // POST /files/upload w locie (postęp w uploadProgress)
  | { name: 'starting' } // POST /analysis w locie (krótko)
  | { name: 'analyzing'; analysisId: string } // SSE otwarty (postęp w bySource)
  // Dłużej niż odzyskiwanie. watching=false: przekroczony limit wieku, śledzenie zakończone.
  | { name: 'stillRunning'; analysisId: string; watching: boolean }
  // retry: 'start' = plik już jest na serwerze, ponów tylko POST /analysis; 'reset' = wróć do podglądu.
  | { name: 'failed'; message: string; retry: 'start' | 'reset' }

// Distinguishes the user's cancel from unmount: only the former cancels a just-created analysis.
const USER_CANCEL = new DOMException('Cancelled by user', 'AbortError')

// --- Kompozycja: upload → start → stream → wynik -----------------------------
export function useAnalysisFlow(onComplete: (analysisId: string) => void) {
  const [state, setState] = useState<FlowState>({ name: 'idle' })
  const [uploadProgress, setUploadProgress] = useState(0)
  const [bySource, setBySource] = useState<ProgressBySource>({})
  const [cancelling, setCancelling] = useState(false)
  const [cancelError, setCancelError] = useState<string | null>(null)

  // One controller per run covers upload, start, stream and recovery; unmount aborts it.
  const flowRef = useRef<AbortController | null>(null)
  const uploadedRef = useRef<{ fileId: string; file: File } | null>(null)
  const onCompleteRef = useRef(onComplete)
  useEffect(() => {
    onCompleteRef.current = onComplete
  }, [onComplete])

  useEffect(() => () => flowRef.current?.abort(), [])

  function begin(): AbortSignal {
    flowRef.current?.abort()
    const controller = new AbortController()
    flowRef.current = controller
    setCancelling(false)
    setCancelError(null)
    return controller.signal
  }

  async function start(file: File) {
    const signal = begin()
    uploadedRef.current = null
    setUploadProgress(0)
    setState({ name: 'uploading' })

    let fileId: string
    try {
      ;({ fileId } = await uploadFileWithProgress(file, {
        signal,
        onProgress: (pct) => {
          if (!signal.aborted) setUploadProgress(pct)
        },
      }))
    } catch (err) {
      if (signal.aborted) return // cancel() już ustawił idle albo komponent zniknął
      if (isAbortError(err)) return setState({ name: 'idle' })
      return setState({ name: 'failed', message: messageForError(err), retry: 'reset' })
    }
    if (signal.aborted) return // cancel tuż po zakończeniu uploadu nie tworzy analizy

    uploadedRef.current = { fileId, file }
    await launch(fileId, file, signal)
  }

  async function launch(fileId: string, file: File, signal: AbortSignal) {
    setBySource({})
    setState({ name: 'starting' })

    let analysis: Analysis
    try {
      analysis = await startAnalysis({ fileId, type: pickAnalysisType(file) })
    } catch (err) {
      if (signal.aborted) return
      // 404: the upload is gone, so only a fresh upload can help.
      const missingFile = err instanceof ApiError && err.isNotFound
      if (missingFile) uploadedRef.current = null
      return setState({
        name: 'failed',
        message: messageForError(err),
        retry: missingFile ? 'reset' : 'start',
      })
    }
    if (signal.aborted) {
      // The POST raced the user's cancel; leaving the page keeps the analysis in history.
      if (signal.reason === USER_CANCEL) void cancelAnalysis(analysis.id).catch(() => {})
      return
    }

    const analysisId = analysis.id
    setState({ name: 'analyzing', analysisId })
    void watchAnalysis(
      analysisId,
      {
        onProgress: (e) =>
          setBySource((prev) => ({
            ...prev,
            [e.source]: { progress: e.progress, stage: e.stage, status: e.status },
          })),
        onResult: (result) => {
          if (!signal.aborted) settle(analysisId, result)
        },
      },
      signal,
      {
        createdAt: analysis.createdAt,
        onStillRunning: () => {
          if (!signal.aborted) setState({ name: 'stillRunning', analysisId, watching: true })
        },
      },
    ).catch((err) => {
      if (signal.aborted || isAbortError(err)) return
      // The analysis exists: unless it is gone, link to it instead of offering a re-upload.
      if (!(err instanceof ApiError && err.isNotFound)) {
        return setState({ name: 'stillRunning', analysisId, watching: false })
      }
      setState({ name: 'failed', message: messageForError(err), retry: 'reset' })
    })
  }

  // Route by the terminal resource and stop watching it.
  function settle(id: string, result: Analysis) {
    flowRef.current?.abort()
    setCancelling(false)
    if (result.status === 'COMPLETED') {
      onCompleteRef.current(id)
    } else if (result.status === 'CANCELLED') {
      setState({ name: 'idle' })
    } else {
      const message = result.errorMessage ?? 'Analiza nie powiodła się.'
      setState({ name: 'failed', message, retry: 'reset' })
    }
  }

  async function cancel() {
    if (state.name === 'uploading' || state.name === 'starting') {
      flowRef.current?.abort(USER_CANCEL)
      setState({ name: 'idle' })
      return
    }
    if (state.name !== 'analyzing' && !(state.name === 'stillRunning' && state.watching)) return

    const signal = flowRef.current?.signal
    setCancelling(true)
    setCancelError(null)
    try {
      const settled = await cancelAndSettle(state.analysisId, signal)
      if (signal?.aborted) return
      if (settled) settle(state.analysisId, settled)
      else setCancelling(false) // already finishing; the open stream delivers the result
    } catch {
      if (signal?.aborted) return
      // The stream stays open, so the analysis keeps being tracked.
      setCancelling(false)
      setCancelError(CANCEL_FAILED_MESSAGE)
    }
  }

  function retry() {
    const uploaded = uploadedRef.current
    if (state.name === 'failed' && state.retry === 'start' && uploaded) {
      void launch(uploaded.fileId, uploaded.file, begin())
      return
    }
    setState({ name: 'idle' })
  }

  function reset() {
    flowRef.current?.abort()
    uploadedRef.current = null
    setCancelling(false)
    setCancelError(null)
    setState({ name: 'idle' })
  }

  return {
    state,
    uploadProgress,
    bySource,
    cancelling,
    cancelError,
    start,
    cancel,
    retry,
    reset,
  }
}
