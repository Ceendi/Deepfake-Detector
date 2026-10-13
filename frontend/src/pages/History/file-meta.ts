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
// błąd sieci nie trafia do cache, żeby kolejne wejście mogło ponowić.
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
        if (err instanceof ApiError && err.isNotFound) cache.set(fileId, UNAVAILABLE)
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
    void loadFileMeta(fileId).then((value) => {
      if (active) setMeta(value)
    })
    return () => {
      active = false
    }
  }, [fileId])

  return meta
}

const SEARCH_CONCURRENCY = 4

// Nazwy plików wszystkich analiz — potrzebne dopiero, gdy użytkownik zaczyna szukać. Ładowane w tle
// z ograniczoną równoległością; mapa rośnie w miarę napływu odpowiedzi (wyniki dochodzą na bieżąco).
export function useFileNames(fileIds: string[], enabled: boolean): ReadonlyMap<string, string> {
  const [names, setNames] = useState<ReadonlyMap<string, string>>(() => new Map())

  useEffect(() => {
    if (!enabled) return
    let active = true
    const queue = [...new Set(fileIds)]

    const publish = () => {
      if (!active) return
      const next = new Map<string, string>()
      for (const id of fileIds) {
        const name = cache.get(id)?.name
        if (name) next.set(id, name)
      }
      setNames(next)
    }

    async function worker() {
      for (let id = queue.shift(); id !== undefined && active; id = queue.shift()) {
        if (!cache.has(id)) {
          await loadFileMeta(id)
          publish()
        }
      }
    }

    void Promise.resolve().then(publish) // nazwy już w cache — bez synchronicznego setState w efekcie
    void Promise.all(Array.from({ length: SEARCH_CONCURRENCY }, worker))
    return () => {
      active = false
    }
  }, [fileIds, enabled])

  return names
}
