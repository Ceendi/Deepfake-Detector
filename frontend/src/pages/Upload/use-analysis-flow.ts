import { useEffect, useRef, useState } from 'react'

import { uploadFileWithProgress } from '@/api/files'
import { startAnalysis, getAnalysis, cancelAnalysis } from '@/api/analysis'
import { streamAnalysis } from '@/api/stream'
import type { AnalysisProgressEvent, AnalysisResultEvent, AnalysisStatus } from '@/api/types'

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
  | { name: 'failed'; message: string }

// --- Upload z postępem -------------------------------------------------------
function useFileUpload() {
  const [progress, setProgress] = useState(0)
  const ctrl = useRef<AbortController | null>(null)

  function upload(file: File) {
    setProgress(0)
    ctrl.current = new AbortController()
    return uploadFileWithProgress(file, { signal: ctrl.current.signal, onProgress: setProgress })
  }
  function cancel() {
    ctrl.current?.abort()
  }
  return { progress, upload, cancel }
}

// --- Strumień SSE z agregacją per źródło -------------------------------------
function useAnalysisStream() {
  const [bySource, setBySource] = useState<ProgressBySource>({})
  const ctrl = useRef<AbortController | null>(null)
  const gotResult = useRef(false)

  function open(
    id: string,
    handlers: { onResult: (e: AnalysisResultEvent) => void; onError: (err: unknown) => void },
  ) {
    setBySource({})
    gotResult.current = false
    const controller = new AbortController()
    ctrl.current = controller

    void streamAnalysis(
      id,
      {
        onProgress: (e) =>
          setBySource((prev) => ({
            ...prev,
            [e.source]: { progress: e.progress, stage: e.stage, status: e.status },
          })),
        onResult: (e) => {
          gotResult.current = true
          handlers.onResult(e)
          controller.abort() // result = koniec → zamknij stream (serwer i tak zamyka), bez reconnectu
        },
      },
      controller.signal,
    ).catch((err) => {
      if (gotResult.current || isAbortError(err)) return // normalne zakończenie / anulowanie
      handlers.onError(err)
    })
  }
  function close() {
    ctrl.current?.abort()
  }
  return { bySource, open, close }
}

// --- Kompozycja: upload → start → stream → wynik -----------------------------
export function useAnalysisFlow(onComplete: (analysisId: string) => void) {
  const [state, setState] = useState<FlowState>({ name: 'idle' })
  const upload = useFileUpload()
  const stream = useAnalysisStream()

  // Sprzątanie przy odmontowaniu — przerwij upload i zamknij stream (abort skończonego = no-op).
  useEffect(() => {
    return () => {
      upload.cancel()
      stream.close()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function start(file: File) {
    try {
      setState({ name: 'uploading' })
      const { fileId, fileKey } = await upload.upload(file)

      setState({ name: 'starting' })
      const analysis = await startAnalysis({ fileId, fileKey, type: pickAnalysisType(file) })

      setState({ name: 'analyzing', analysisId: analysis.id })
      stream.open(analysis.id, {
        onResult: (result) => void handleResult(analysis.id, result),
        onError: (err) => setState({ name: 'failed', message: messageForError(err) }),
      })
    } catch (err) {
      if (isAbortError(err)) return setState({ name: 'idle' }) // user anulował upload
      setState({ name: 'failed', message: messageForError(err) })
    }
  }

  async function handleResult(id: string, result: AnalysisResultEvent) {
    if (result.status === 'COMPLETED') {
      onComplete(id)
    } else if (result.status === 'CANCELLED') {
      setState({ name: 'idle' })
    } else {
      // FAILED — event nie niesie errorMessage; dociągamy z pełnego zasobu (best-effort).
      let message = 'Analiza nie powiodła się.'
      try {
        const full = await getAnalysis(id)
        message = full.errorMessage ?? message
      } catch {
        /* zostaw komunikat generyczny */
      }
      setState({ name: 'failed', message })
    }
  }

  async function cancel() {
    if (state.name === 'uploading') {
      upload.cancel() // abort XHR → start() złapie AbortError → wróci do idle
      return
    }
    if (state.name === 'analyzing') {
      stream.close()
      try {
        await cancelAnalysis(state.analysisId) // DELETE; 409 = już skończona — ignoruj
      } catch {
        /* idempotentne / już terminalna */
      }
      setState({ name: 'idle' })
    }
  }

  function retry() {
    setState({ name: 'idle' })
  }

  return { state, uploadProgress: upload.progress, bySource: stream.bySource, start, cancel, retry }
}
