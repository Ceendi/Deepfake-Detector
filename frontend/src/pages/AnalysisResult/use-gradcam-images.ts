import { useEffect, useState } from 'react'

import { fetchArtifact } from '@/api/analysis'

export interface GradcamImage {
  url: string // oryginalna ścieżka endpointu (klucz pozycji)
  objectUrl: string | null // blob: URL gotowy do <img src> (null dopóki nie pobrane)
  status: 'loading' | 'ready' | 'error'
}

type Loaded = Record<string, { objectUrl: string | null; status: 'ready' | 'error' }>

// Pobiera listę artefaktów Grad-CAM (każdy wymaga Bearer) jako blob → object URL.
// Wynik trzymamy w mapie url→stan, aktualizowanej TYLKO asynchronicznie w callbackach fetcha
// (synchroniczny setState w efekcie jest zabroniony — cascading renders). Tablicę wynikową
// wyliczamy podczas renderu: pozycje bez wpisu w mapie są jeszcze 'loading'. Object URL-e
// rewokujemy przy zmianie listy / odmontowaniu, żeby nie wyciekały. `urls.join` daje stabilny dep.
export function useGradcamImages(urls: string[]): GradcamImage[] {
  const key = urls.join('|')
  const [loaded, setLoaded] = useState<Loaded>({})

  useEffect(() => {
    const list = key ? key.split('|') : []
    if (list.length === 0) return

    const controller = new AbortController()
    const created: string[] = []
    let active = true

    list.forEach((url) => {
      fetchArtifact(url, controller.signal)
        .then((blob) => {
          if (!active) return
          const objectUrl = URL.createObjectURL(blob)
          created.push(objectUrl)
          setLoaded((prev) => ({ ...prev, [url]: { objectUrl, status: 'ready' } }))
        })
        .catch(() => {
          if (!active || controller.signal.aborted) return
          setLoaded((prev) => ({ ...prev, [url]: { objectUrl: null, status: 'error' } }))
        })
    })

    return () => {
      active = false
      controller.abort()
      created.forEach((objectUrl) => URL.revokeObjectURL(objectUrl))
    }
  }, [key])

  return (key ? key.split('|') : []).map((url) => {
    const entry = loaded[url]
    return entry
      ? { url, objectUrl: entry.objectUrl, status: entry.status }
      : { url, objectUrl: null, status: 'loading' }
  })
}
