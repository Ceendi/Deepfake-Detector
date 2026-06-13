// Hook SSE dla ekranu wyniku: otwiera GET /api/analysis/{id}/stream gdy analiza jest w toku
// i agreguje zdarzenia `progress` per źródło (video/audio). `onResult` woła rodzic, by przeładować
// pełny zasób — event `result` niesie tylko status/verdict/confidence (bez details/probów).
// Montowany TYLKO dla PENDING/PROCESSING (rodzic strzeże), więc strumień odpalamy bezwarunkowo.
import { useEffect, useRef, useState } from 'react'

import { streamAnalysis } from '@/api/stream'
import type { AnalysisProgressEvent, AnalysisResultEvent } from '@/api/types'

import type { Source, SourceProgress } from './progress-steps'

export type ProgressBySource = Partial<Record<Source, SourceProgress>>

export function useAnalysisStream(
  id: string,
  onResult: (e: AnalysisResultEvent) => void,
): ProgressBySource {
  const [bySource, setBySource] = useState<ProgressBySource>({})
  // onResult przez ref — żeby zmiana jego tożsamości nie restartowała strumienia (dep tylko [id]).
  // Aktualizacja w efekcie (nie w renderze) — wymóg react-hooks/refs. Reset bySource przy zmianie
  // analizy załatwia `key={analysis.id}` na <LiveProgress> (remount → świeży useState).
  const onResultRef = useRef(onResult)
  useEffect(() => {
    onResultRef.current = onResult
  }, [onResult])

  useEffect(() => {
    const controller = new AbortController()
    let settled = false

    void streamAnalysis(
      id,
      {
        onProgress: (e: AnalysisProgressEvent) =>
          setBySource((prev) => ({
            ...prev,
            [e.source]: { progress: e.progress, stage: e.stage },
          })),
        onResult: (e) => {
          settled = true
          onResultRef.current(e) // rodzic refetchuje → strona przełączy się na widok terminalny
          controller.abort() // koniec; serwer i tak zamyka połączenie
        },
      },
      controller.signal,
    ).catch((err) => {
      // AbortError (unmount / normalne zakończenie) i już-obsłużony result → cisza.
      // Inny błąd strumienia: zostaw ostatni snapshot; user może odświeżyć (brak reconnectu — TODO).
      if (settled) return
      if (err instanceof DOMException && err.name === 'AbortError') return
    })

    return () => controller.abort()
  }, [id])

  return bySource
}
