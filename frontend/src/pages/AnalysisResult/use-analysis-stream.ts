import { useEffect, useRef, useState } from 'react'

import { watchAnalysis } from '@/api/watch-analysis'
import type { Analysis, AnalysisProgressEvent } from '@/api/types'

import type { Source, SourceProgress } from './progress-steps'

export type ProgressBySource = Partial<Record<Source, SourceProgress>>

export function useAnalysisStream(
  id: string,
  onResult: (analysis: Analysis) => void,
  onError: (error: unknown) => void,
  createdAt?: string,
): ProgressBySource {
  const [bySource, setBySource] = useState<ProgressBySource>({})

  // Callback changes must not reopen SSE; key={analysis.id} resets progress on navigation.
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
      { createdAt },
    ).catch((err) => {
      if (!controller.signal.aborted) onErrorRef.current(err)
    })

    return () => controller.abort()
  }, [id, createdAt])

  return bySource
}
