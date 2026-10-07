// Share SSE recovery with upload and pass the complete terminal resource to the page.
import { useEffect, useRef, useState } from 'react'

import { watchAnalysis } from '@/api/watch-analysis'
import type { Analysis, AnalysisProgressEvent } from '@/api/types'

import type { Source, SourceProgress } from './progress-steps'

export type ProgressBySource = Partial<Record<Source, SourceProgress>>

export function useAnalysisStream(
  id: string,
  onResult: (analysis: Analysis) => void,
  onError: (error: unknown) => void,
): ProgressBySource {
  const [bySource, setBySource] = useState<ProgressBySource>({})
  // onResult przez ref — żeby zmiana jego tożsamości nie restartowała strumienia (dep tylko [id]).
  // Aktualizacja w efekcie (nie w renderze) — wymóg react-hooks/refs. Reset bySource przy zmianie
  // analizy załatwia `key={analysis.id}` na <LiveProgress> (remount → świeży useState).
  const onResultRef = useRef(onResult)
  const onErrorRef = useRef(onError)
  useEffect(() => {
    onResultRef.current = onResult
    onErrorRef.current = onError
  }, [onResult, onError])

  useEffect(() => {
    const controller = new AbortController()

    void watchAnalysis(
      id,
      {
        onProgress: (e: AnalysisProgressEvent) =>
          setBySource((prev) => ({
            ...prev,
            [e.source]: { progress: e.progress, stage: e.stage },
          })),
        onResult: (e) => {
          onResultRef.current(e)
          controller.abort()
        },
      },
      controller.signal,
    ).catch((err) => {
      if (!controller.signal.aborted) onErrorRef.current(err)
    })

    return () => controller.abort()
  }, [id])

  return bySource
}
