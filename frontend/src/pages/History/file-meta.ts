// Nazwa i długość pliku dla wierszy historii. Lista analiz (AnalysisSummary) ich nie zwraca, więc
// dociągamy je z File Service. Cache modułowy współdzielą wiersze i wyszukiwarka: powrót na
// wcześniejszą stronę ani ponowne wyszukiwanie nie odpalają kolejnych żądań.
import { useEffect, useState } from 'react'

import { getFileMetadata } from '@/api/files'
import { ApiError } from '@/api/errors'

export interface FileMeta {
  name: string | null
  duration: number | null
}

const UNAVAILABLE: FileMeta = { name: null, duration: null }
const cache = new Map<string, FileMeta>()
const inflight = new Map<string, Promise<FileMeta>>()

export function cachedFileMeta(fileId: string): FileMeta | undefined {
  return cache.get(fileId)
}

// Jedno żądanie na plik naraz. 404 (plik usunięty / cudzy — IDOR) cache'ujemy jako brak danych;
// inne błędy (sieć, 429 z gatewaya) odrzucają promise i nie trafiają do cache — można ponowić.
export function loadFileMeta(fileId: string): Promise<FileMeta> {
  const hit = cache.get(fileId)
  if (hit) return Promise.resolve(hit)
  const pending = inflight.get(fileId)
  if (pending) return pending

  const request = getFileMetadata(fileId)
    .then(
      (m) => {
        const value = { name: m.name, duration: m.duration }
        cache.set(fileId, value)
        return value
      },
      (err) => {
        if (!(err instanceof ApiError && err.isNotFound)) throw err
        cache.set(fileId, UNAVAILABLE)
        return UNAVAILABLE
      },
    )
    .finally(() => inflight.delete(fileId))
  inflight.set(fileId, request)
  return request
}

export function useFileMeta(fileId: string): FileMeta | null {
  const [meta, setMeta] = useState<FileMeta | null>(() => cachedFileMeta(fileId) ?? null)

  useEffect(() => {
    let active = true
    loadFileMeta(fileId).then(
      (value) => {
        if (active) setMeta(value)
      },
      () => undefined, // wiersz zostaje z fallbackiem na #fileId
    )
    return () => {
      active = false
    }
  }, [fileId])

  return meta
}

const SEARCH_CONCURRENCY = 4

export interface FileNames {
  names: ReadonlyMap<string, string>
  loading: boolean // true, dopóki nie sprawdzono wszystkich plików (albo ładowanie nie stanęło)
}

// Nazwy plików wszystkich analiz — potrzebne dopiero, gdy użytkownik zaczyna szukać. Ładowane w tle
// z ograniczoną równoległością; mapa rośnie w miarę napływu odpowiedzi. Pierwszy błąd inny niż 404
// (np. 429 z limitu gatewaya na /api/files/**) zatrzymuje ładowanie, żeby nie zasypać limitu
// i nie blokować reszty żądań użytkownika do File Service.
export function useFileNames(fileIds: string[], enabled: boolean): FileNames {
  // `source` — dla której listy policzono wynik; nowa lista oznacza ładowanie od pierwszego renderu.
  const [result, setResult] = useState<FileNames & { source: string[] | null }>({
    names: new Map(),
    loading: false,
    source: null,
  })

  useEffect(() => {
    if (!enabled) return
    let active = true
    let stopped = false
    const queue = [...new Set(fileIds)].filter((id) => !cache.has(id))

    const publish = (loading: boolean) => {
      if (!active) return
      const names = new Map<string, string>()
      for (const id of fileIds) {
        const name = cache.get(id)?.name
        if (name) names.set(id, name)
      }
      setResult({ names, loading, source: fileIds })
    }

    async function worker() {
      for (let id = queue.shift(); id !== undefined && active && !stopped; id = queue.shift()) {
        try {
          await loadFileMeta(id)
          publish(true)
        } catch {
          stopped = true
        }
      }
    }

    // Start asynchronicznie — bez synchronicznego setState w efekcie.
    void Promise.resolve()
      .then(() => publish(queue.length > 0))
      .then(() => Promise.all(Array.from({ length: SEARCH_CONCURRENCY }, worker)))
      .then(() => publish(false))
    return () => {
      active = false
    }
  }, [fileIds, enabled])

  const current = result.source === fileIds
  return { names: result.names, loading: enabled && (!current || result.loading) }
}
