import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPoller, isTerminal } from './poller'
import { ApiError } from '../api/http'

describe('异步任务轮询', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())
  it.each(['COMPLETED', 'FAILED', 'PARTIAL_FAILED'] as const)(
    '在 %s 终态停止',
    async (status) => {
      const fetch = vi.fn().mockResolvedValue({ status })
      const onData = vi.fn()
      const poller = createPoller({
        fetch,
        terminal: (data) => isTerminal(data.status),
        onData,
        onError: vi.fn(),
      })
      poller.start()
      await vi.advanceTimersByTimeAsync(10000)
      expect(fetch).toHaveBeenCalledTimes(1)
      expect(onData).toHaveBeenCalledWith({ status })
    },
  )
  it('请求完成两秒后才再次查询，不重叠慢请求', async () => {
    let resolve!: (data: string) => void
    const fetch = vi.fn().mockImplementation(
      () =>
        new Promise<string>((done) => {
          resolve = done
        }),
    )
    const poller = createPoller({
      fetch,
      terminal: () => false,
      onData: vi.fn(),
      onError: vi.fn(),
    })
    poller.start()
    await vi.advanceTimersByTimeAsync(10000)
    expect(fetch).toHaveBeenCalledTimes(1)
    resolve('PROCESSING')
    await vi.advanceTimersByTimeAsync(1999)
    expect(fetch).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(fetch).toHaveBeenCalledTimes(2)
    poller.stop()
  })
  it('连续失败采用退避并在第三次暂停，手动恢复可继续', async () => {
    const fetch = vi.fn().mockRejectedValue(new Error('断网'))
    const onError = vi.fn()
    const poller = createPoller({
      fetch,
      terminal: () => false,
      onData: vi.fn(),
      onError,
    })
    poller.start()
    await vi.advanceTimersByTimeAsync(4000)
    expect(fetch).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(8000)
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(onError).toHaveBeenLastCalledWith(expect.any(Error), true)
    await vi.advanceTimersByTimeAsync(60000)
    expect(fetch).toHaveBeenCalledTimes(3)
    poller.start()
    await vi.advanceTimersByTimeAsync(0)
    expect(fetch).toHaveBeenCalledTimes(4)
    poller.stop()
  })
  it.each([401, 403, 404])('%s 错误立即暂停', async (status) => {
    const fetch = vi.fn().mockRejectedValue(new ApiError('无权访问', status))
    const onError = vi.fn()
    createPoller({
      fetch,
      terminal: () => false,
      onData: vi.fn(),
      onError,
    }).start()
    await vi.advanceTimersByTimeAsync(60000)
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(onError).toHaveBeenCalledWith(expect.any(ApiError), true)
  })
  it('离开页面时取消在途请求，忽略迟到响应', async () => {
    let resolve!: (data: string) => void
    let signal!: AbortSignal
    const fetch = vi.fn().mockImplementation((abort: AbortSignal) => {
      signal = abort
      return new Promise<string>((done) => {
        resolve = done
      })
    })
    const onData = vi.fn()
    const poller = createPoller({
      fetch,
      terminal: () => false,
      onData,
      onError: vi.fn(),
    })
    poller.start()
    poller.stop()
    expect(signal.aborted).toBe(true)
    resolve('COMPLETED')
    await vi.advanceTimersByTimeAsync(10000)
    expect(onData).not.toHaveBeenCalled()
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
