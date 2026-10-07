/** Stop awaiting the whole operation, including work before fetch such as token refresh. */
export function abortable<T>(operation: () => Promise<T>, signal: AbortSignal): Promise<T> {
  signal.throwIfAborted()
  return new Promise((resolve, reject) => {
    const abort = () => {
      signal.removeEventListener('abort', abort)
      reject(signal.reason)
    }
    signal.addEventListener('abort', abort, { once: true })
    // Attach both outcomes even after cancellation, so late rejection cannot be unhandled.
    Promise.resolve()
      .then(() => {
        signal.throwIfAborted()
        return operation()
      })
      .then(
        (value) => {
          signal.removeEventListener('abort', abort)
          resolve(value)
        },
        (error: unknown) => {
          signal.removeEventListener('abort', abort)
          reject(error)
        },
      )
  })
}
