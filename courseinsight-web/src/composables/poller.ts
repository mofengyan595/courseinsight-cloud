import { ApiError } from '../api/http'
import type { BatchStatus } from '../types/api'

export const isTerminal = (status: BatchStatus) =>
  ['COMPLETED', 'FAILED', 'PARTIAL_FAILED'].includes(status)

export function createPoller<T>(options: {
  fetch: (signal: AbortSignal) => Promise<T>
  terminal: (data: T) => boolean
  onData: (data: T) => void
  onError: (error: unknown, paused: boolean) => void
}) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let controller: AbortController | undefined
  let generation = 0
  let failures = 0
  function stop() {
    generation++
    clearTimeout(timer)
    controller?.abort()
  }
  async function tick(version: number) {
    controller = new AbortController()
    try {
      const data = await options.fetch(controller.signal)
      if (version !== generation) return
      failures = 0
      options.onData(data)
      if (options.terminal(data)) return
      timer = setTimeout(() => {
        void tick(version)
      }, 2000)
    } catch (error) {
      if (version !== generation) return
      failures++
      const paused =
        failures >= 3 ||
        (error instanceof ApiError && [401, 403, 404].includes(error.status))
      options.onError(error, paused)
      if (!paused)
        timer = setTimeout(
          () => {
            void tick(version)
          },
          2000 * 2 ** failures,
        )
    }
  }
  function start() {
    stop()
    failures = 0
    void tick(generation)
  }
  return { start, stop }
}
