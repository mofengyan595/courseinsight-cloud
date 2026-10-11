import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AxiosHeaders } from 'axios'
import { http } from './http'
import { exportBatch, uploadBatch } from './index'

const originalAdapter = http.defaults.adapter
beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  http.defaults.adapter = originalAdapter
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

it('CSV 上传使用 multipart file 字段并返回真实批次数据', async () => {
  const file = new File(['content,rating\n测试,5'], 'reviews.csv', {
    type: 'text/csv',
  })
  const batch = {
    batchId: 123,
    batchNo: 'BATCH-123',
    courseId: 1,
    totalCount: 1,
  }
  http.defaults.adapter = async (config) => {
    expect(config.url).toBe('/courses/1/analysis-batches')
    expect(config.method).toBe('post')
    expect(config.data).toBeInstanceOf(FormData)
    expect(config.data.get('file')).toBe(file)
    return {
      config,
      data: { code: 0, message: 'success', data: batch },
      status: 200,
      statusText: 'OK',
      headers: new AxiosHeaders(),
    }
  }
  expect(await uploadBatch(1, file)).toEqual(batch)
})

it('CSV 下载使用 Blob 并释放临时 URL', async () => {
  const blob = new Blob(['content,rating\n测试,5'], { type: 'text/csv' })
  const createObjectURL = vi.fn(() => 'blob:test-export')
  const revokeObjectURL = vi.fn()
  vi.stubGlobal('URL', { createObjectURL, revokeObjectURL })
  const click = vi
    .spyOn(HTMLAnchorElement.prototype, 'click')
    .mockImplementation(function (this: HTMLAnchorElement) {
      expect(this.download).toBe('analysis-batch-123.csv')
      expect(this.href).toBe('blob:test-export')
    })
  http.defaults.adapter = async (config) => {
    expect(config.responseType).toBe('blob')
    return {
      config,
      data: blob,
      status: 200,
      statusText: 'OK',
      headers: new AxiosHeaders({ 'content-type': 'text/csv' }),
    }
  }
  await exportBatch('123')
  expect(click).toHaveBeenCalledOnce()
  expect(createObjectURL).toHaveBeenCalledWith(blob)
  await vi.advanceTimersByTimeAsync(1000)
  expect(revokeObjectURL).toHaveBeenCalledWith('blob:test-export')
})

it('取消下载请求后不创建文件或触发下载', async () => {
  const controller = new AbortController()
  const createObjectURL = vi.fn()
  vi.stubGlobal('URL', { createObjectURL })
  const click = vi
    .spyOn(HTMLAnchorElement.prototype, 'click')
    .mockImplementation(() => {})
  http.defaults.adapter = async (config) => {
    expect(config.signal).toBe(controller.signal)
    controller.abort()
    return {
      config,
      data: new Blob(),
      status: 200,
      statusText: 'OK',
      headers: new AxiosHeaders({ 'content-type': 'text/csv' }),
    }
  }
  await expect(exportBatch('123', controller.signal)).rejects.toMatchObject({
    code: 'ERR_CANCELED',
  })
  expect(createObjectURL).not.toHaveBeenCalled()
  expect(click).not.toHaveBeenCalled()
})
