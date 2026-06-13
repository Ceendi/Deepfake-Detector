import { useEffect, useState } from 'react'

import { presignFile } from '@/api/files'
import type { AnalysisType } from '@/api/types'
import { Spinner } from '@/components/ui/Spinner/Spinner'
import { AudioPlayer } from '@/components/ui/AudioPlayer/AudioPlayer'
import { VideoPlayer } from '@/components/ui/VideoPlayer/VideoPlayer'

import styles from '../AnalysisResult.module.css'

type State = 'loading' | 'ready' | 'error'

// Podgląd analizowanego pliku. Presign zwraca URL osiągalny wprost z przeglądarki, więc trafia
// bezpośrednio do <video>/<audio> (bez nagłówka Authorization — w odróżnieniu od artefaktów Grad-CAM).
// FULL to plik wideo (ma obie ścieżki), więc tylko AUDIO renderujemy jako odtwarzacz dźwięku.
export function MediaPreview({ fileId, type }: { fileId: string; type: AnalysisType }) {
  const [url, setUrl] = useState<string | null>(null)
  const [state, setState] = useState<State>('loading')
  const isVideo = type !== 'AUDIO'

  useEffect(() => {
    const controller = new AbortController()

    presignFile(fileId, controller.signal)
      .then((res) => {
        setUrl(res.url)
        setState('ready')
      })
      .catch(() => {
        if (!controller.signal.aborted) setState('error')
      })

    return () => controller.abort()
  }, [fileId])

  return (
    <section className={styles.section}>
      <span className={styles.sectionLabel}>Analizowany plik</span>

      {state === 'loading' && (
        <div className={styles.mediaPlaceholder}>
          <Spinner size="md" />
        </div>
      )}

      {state === 'error' && (
        <div className={styles.mediaPlaceholder}>
          <span className={styles.mediaNote}>Nie udało się wczytać podglądu pliku.</span>
        </div>
      )}

      {state === 'ready' &&
        url &&
        (isVideo ? (
          <VideoPlayer src={url} onError={() => setState('error')} />
        ) : (
          <AudioPlayer src={url} onError={() => setState('error')} />
        ))}
    </section>
  )
}
