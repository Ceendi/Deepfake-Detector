import { useEffect, useRef, useState } from 'react'

import { uploadFileWithProgress } from '@/api/files'
import { cancelAnalysis } from '@/api/analysis'
import { canRetryStart, startAnalysisSafely } from '@/api/start-analysis-safely'
import { CANCEL_FAILED_MESSAGE, cancelAndSettle } from '@/api/cancel-analysis'
import { ApiError, UncertainStartError } from '@/api/errors'
import { AnalysisAgeLimitError, watchAnalysis } from '@/api/watch-analysis'
import { analysisFailureMessage } from '@/api/error-codes'
import type { Analysis, AnalysisProgressEvent, AnalysisStatus, AnalysisType } from '@/api/types'

import { isAbortError, messageForError, pickAnalysisType } from './upload-utils'

type Source = AnalysisProgressEvent['source'] // 'video' | 'audio'
export interface SourceProgress {
  progress: number // 0..100
  stage: string
  status: AnalysisStatus
}
export type ProgressBySource = Partial<Record<Source, SourceProgress>>

export type FlowState =
  | { name: 'idle' }
  | { name: 'uploading' }
  | { name: 'starting' }
  | { name: 'analyzing'; analysisId: string }
  | { name: 'stillRunning'; analysisId: string; watching: boolean; timedOut?: boolean }
  | { name: 'failed'; message: string; retry: 'start' | 'reset' | 'history' }

// Distinguishes the user's cancel from unmount: only the former cancels a just-created analysis.
const USER_CANCEL = new DOMException('Cancelled by user', 'AbortError')

export function useAnalysisFlow(onComplete: (analysisId: string) => void) {
  const [state, setState] = useState<FlowState>({ name: 'idle' })
  const [uploadProgress, setUploadProgress] = useState(0)
  const [bySource, setBySource] = useState<ProgressBySource>({})
  const [cancelling, setCancelling] = useState(false)
  const [cancelError, setCancelError] = useState<string | null>(null)

  const [analysisType, setAnalysisType] = useState<AnalysisType | null>(null)

  // One controller per run covers upload, start, stream and recovery; unmount aborts it.
  const flowRef = useRef<AbortController | null>(null)
  const uploadedRef = useRef<{ fileId: string; type: AnalysisType } | null>(null)
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
    setAnalysisType(null)
    setUploadProgress(0)
    setState({ name: 'uploading' })

    let fileId: string
    let mimetype: string
    try {
      ;({ fileId, mimetype } = await uploadFileWithProgress(file, {
        signal,
        onProgress: (pct) => {
          if (!signal.aborted) setUploadProgress(pct)
        },
      }))
    } catch (err) {
      if (signal.aborted) return
      if (isAbortError(err)) return setState({ name: 'idle' })
      return setState({ name: 'failed', message: messageForError(err), retry: 'reset' })
    }
    if (signal.aborted) return

    const type = pickAnalysisType(mimetype)
    setAnalysisType(type)
    uploadedRef.current = { fileId, type }
    await launch(fileId, type, signal)
  }

  async function launch(fileId: string, type: AnalysisType, signal: AbortSignal) {
    setBySource({})
    setState({ name: 'starting' })

    let analysis: Analysis
    try {
      analysis = await startAnalysisSafely({ fileId, type }, signal)
    } catch (err) {
      if (signal.aborted) return
      // 404: the upload is gone, so only a fresh upload can help.
      const missingFile = err instanceof ApiError && err.isNotFound
      if (missingFile) uploadedRef.current = null
      return setState({
        name: 'failed',
        message: messageForError(err, 'start'),
        retry:
          err instanceof UncertainStartError ? 'history' : canRetryStart(err) ? 'start' : 'reset',
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

      if (!(err instanceof ApiError && err.isNotFound)) {
        return setState({
          name: 'stillRunning',
          analysisId,
          watching: false,
          ...(err instanceof AnalysisAgeLimitError ? { timedOut: true } : {}),
        })
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
      setState({
        name: 'failed',
        message: analysisFailureMessage(result.errorMessage),
        retry: 'reset',
      })
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
    if (state.name === 'failed' && state.retry === 'history') return
    const uploaded = uploadedRef.current
    if (state.name === 'failed' && state.retry === 'start' && uploaded) {
      void launch(uploaded.fileId, uploaded.type, begin())
      return
    }
    setState({ name: 'idle' })
  }

  function reset() {
    flowRef.current?.abort()
    uploadedRef.current = null
    setAnalysisType(null)
    setCancelling(false)
    setCancelError(null)
    setState({ name: 'idle' })
  }

  return {
    state,
    analysisType,
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
