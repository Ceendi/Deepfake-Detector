// Share metadata requests between history, search and analysis results.
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

// Cache missing files; transient failures remain retryable on the next request.
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
  const [result, setResult] = useState<{ fileId: string; meta: FileMeta } | null>(null)

  useEffect(() => {
    let active = true
    const publish = (meta: FileMeta) => {
      if (active) setResult({ fileId, meta })
    }
    let retryTimer: ReturnType<typeof setTimeout> | undefined
    const load = (retry: boolean) => {
      loadFileMeta(fileId).then(publish, (error: unknown) => {
        if (!active) return
        publish(UNAVAILABLE)
        const transient =
          error instanceof TypeError ||
          (error instanceof ApiError &&
            (error.status === 0 || error.status === 429 || error.status >= 500))
        // One delayed retry recovers short outages without polling the gateway.
        if (retry && transient) retryTimer = setTimeout(() => load(false), 5000)
      })
    }
    load(true)
    return () => {
      active = false
      clearTimeout(retryTimer)
    }
  }, [fileId])

  // A new file must never inherit the previous file's name, even before its effect runs.
  return cachedFileMeta(fileId) ?? (result?.fileId === fileId ? result.meta : null)
}

const SEARCH_CONCURRENCY = 4

export interface FileNames {
  names: ReadonlyMap<string, string>
  loading: boolean
  incomplete: boolean
}

// Stop after a transient failure to avoid exhausting the gateway rate limit.
export function useFileNames(fileIds: string[], enabled: boolean): FileNames {
  const [result, setResult] = useState<FileNames & { source: string[] | null }>({
    names: new Map(),
    loading: false,
    incomplete: false,
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
      setResult({ names, loading, incomplete: stopped, source: fileIds })
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

    void Promise.resolve()
      .then(() => publish(queue.length > 0))
      .then(() => Promise.all(Array.from({ length: SEARCH_CONCURRENCY }, worker)))
      .then(() => publish(false))
    return () => {
      active = false
    }
  }, [fileIds, enabled])

  const current = result.source === fileIds
  return {
    names: result.names,
    loading: enabled && (!current || result.loading),
    incomplete: enabled && current && result.incomplete,
  }
}
