import { useEffect, useState } from 'react'

import { fetchArtifact } from '@/api/analysis'

export interface GradcamImage {
  url: string
  objectUrl: string | null
  status: 'loading' | 'ready' | 'error'
}

type Loaded = Record<string, { objectUrl: string | null; status: 'ready' | 'error' }>

// Entries belong to the frame list that loaded them: its cleanup revokes their object URLs, and an
// <img> pointing at a revoked URL fails with net::ERR_FILE_NOT_FOUND.
interface Frames {
  key: string
  loaded: Loaded
}

export function useGradcamImages(urls: string[]): GradcamImage[] {
  const key = urls.join('|')
  const [frames, setFrames] = useState<Frames>({ key, loaded: {} })

  useEffect(() => {
    const list = key ? key.split('|') : []
    if (list.length === 0) return

    const controller = new AbortController()
    const created: string[] = []
    let active = true
    const settle = (url: string, entry: Loaded[string]) =>
      setFrames((prev) => ({
        key,
        loaded: { ...(prev.key === key ? prev.loaded : {}), [url]: entry },
      }))

    list.forEach((url) => {
      fetchArtifact(url, controller.signal)
        .then((blob) => {
          if (!active) return
          const objectUrl = URL.createObjectURL(blob)
          created.push(objectUrl)
          settle(url, { objectUrl, status: 'ready' })
        })
        .catch(() => {
          if (!active || controller.signal.aborted) return
          settle(url, { objectUrl: null, status: 'error' })
        })
    })

    return () => {
      active = false
      controller.abort()
      created.forEach((objectUrl) => URL.revokeObjectURL(objectUrl))
      // The same list may come back later; its old entries now hold revoked URLs.
      setFrames((prev) => (prev.key === key ? { key: '', loaded: {} } : prev))
    }
  }, [key])

  const loaded = frames.key === key ? frames.loaded : {}
  return (key ? key.split('|') : []).map((url) => {
    const entry = loaded[url]
    return entry
      ? { url, objectUrl: entry.objectUrl, status: entry.status }
      : { url, objectUrl: null, status: 'loading' }
  })
}
