import { isVideoFile } from './upload-utils'

const PROBE_TIMEOUT_MS = 5000

/**
 * Duration read the same way as the single-file preview, without rendering it. Resolves null
 * when the browser cannot decode the file; the server still checks the length then.
 */
export function probeDuration(file: File, signal?: AbortSignal): Promise<number | null> {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file)
    const media = document.createElement(isVideoFile(file) ? 'video' : 'audio')
    let settled = false

    const done = (seconds: number | null) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      signal?.removeEventListener('abort', abort)
      media.onloadedmetadata = null
      media.onerror = null
      media.removeAttribute('src')
      URL.revokeObjectURL(url)
      resolve(seconds)
    }
    const abort = () => done(null)
    const timer = setTimeout(abort, PROBE_TIMEOUT_MS)

    signal?.addEventListener('abort', abort, { once: true })
    media.preload = 'metadata'
    media.onloadedmetadata = () => done(media.duration)
    media.onerror = abort
    media.src = url
  })
}
