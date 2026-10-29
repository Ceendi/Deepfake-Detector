import { listAnalyses } from './analysis'
import { ApiError } from './errors'
import type { AnalysisSummary, Paged } from './types'

function delay(ms: number, signal: AbortSignal): Promise<void> {
  signal.throwIfAborted()
  return new Promise((resolve, reject) => {
    const abort = () => {
      clearTimeout(timer)
      reject(signal.reason)
    }
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', abort)
      resolve()
    }, ms)
    signal.addEventListener('abort', abort, { once: true })
  })
}

export async function listAllAnalyses(signal: AbortSignal): Promise<AnalysisSummary[]> {
  const controller = new AbortController()
  const scoped = AbortSignal.any([signal, controller.signal])
  let pausedUntil = 0

  async function read(page: number): Promise<Paged<AnalysisSummary>> {
    for (;;) {
      scoped.throwIfAborted()
      if (pausedUntil > Date.now()) await delay(pausedUntil - Date.now(), scoped)
      try {
        return await listAnalyses(page, 100, scoped)
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 429) throw error
        pausedUntil = Math.max(
          pausedUntil,
          Date.now() + Math.max(1, error.retryAfterSeconds ?? 1) * 1000,
        )
      }
    }
  }

  try {
    const first = await read(0)
    const pages: AnalysisSummary[][] = [first.content]
    let next = 1
    async function worker() {
      while (next < first.page.totalPages) {
        const page = next++
        pages[page] = (await read(page)).content
      }
    }
    await Promise.all([worker(), worker()])
    const seen = new Set<string>()
    return pages.flat().filter((item) => !seen.has(item.id) && seen.add(item.id))
  } finally {
    controller.abort()
  }
}
