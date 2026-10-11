import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { http, request, ApiError } from './http'
import { readSession, saveSession } from '../auth/session'
import type { LoginResponse } from '../types/api'

const identity: LoginResponse = {
  accessToken: 'test-only-token',
  tokenType: 'Bearer',
  expiresIn: 3600,
  userId: 1,
  username: 'test',
  displayName: '测试用户',
  role: 'TEACHER',
}
const original = http.defaults.adapter
beforeEach(() => {
  sessionStorage.clear()
})
afterEach(() => {
  http.defaults.adapter = original
  sessionStorage.clear()
})
function reply(data: unknown, contentType = 'application/json') {
  http.defaults.adapter = async (config) => ({
    config,
    data,
    status: 200,
    statusText: 'OK',
    headers: new AxiosHeaders({ 'content-type': contentType }),
  })
}
function reject(status: number, data: unknown) {
  http.defaults.adapter = async (config) => {
    throw new AxiosError(
      'request failed',
      'ERR_BAD_RESPONSE',
      config,
      undefined,
      {
        config,
        data,
        status,
        statusText: 'Error',
        headers: new AxiosHeaders(),
      },
    )
  }
}
describe('统一请求边界', () => {
  it('提取 data 并携带 Bearer 登录状态', async () => {
    saveSession(identity)
    http.defaults.adapter = async (config) => {
      expect(config.headers.Authorization).toBe('Bearer test-only-token')
      return {
        config,
        data: { code: 0, message: 'success', data: 42 },
        status: 200,
        statusText: 'OK',
        headers: new AxiosHeaders(),
      }
    }
    expect(await request({ url: '/courses' })).toBe(42)
  })
  it('拒绝 HTTP 200 中的业务失败码', async () => {
    reply({ code: 409, message: '你已经评价过该课程', data: null })
    await expect(request({ url: '/courses' })).rejects.toThrow(
      '你已经评价过该课程',
    )
  })
  it('401 清除状态并发出过期事件', async () => {
    saveSession(identity)
    const expired = vi.fn()
    window.addEventListener('auth-expired', expired, { once: true })
    reject(401, { code: 401, message: '令牌无效', data: null })
    await expect(request({ url: '/courses' })).rejects.toMatchObject({
      status: 401,
    })
    expect(readSession()).toBeNull()
    expect(expired).toHaveBeenCalledOnce()
  })
  it('403 提示权限不足并保留有效会话', async () => {
    saveSession(identity)
    reject(403, { code: 403, message: '无权管理其他教师的课程' })
    await expect(request({ url: '/courses' })).rejects.toThrow(
      '权限不足：无权管理其他教师的课程',
    )
    expect(readSession()).not.toBeNull()
  })
  it('Blob 下载失败时仍解析 JSON 错误', async () => {
    reject(
      403,
      new Blob([JSON.stringify({ code: 403, message: '禁止导出' })], {
        type: 'application/json',
      }),
    )
    // jsdom 的 Blob 不实现 text，使用浏览器同等接口模拟。
    const blob = new Blob()
    Object.defineProperty(blob, 'text', {
      value: async () => JSON.stringify({ code: 403, message: '禁止导出' }),
    })
    reject(403, blob)
    await expect(
      http.get('/analysis-batches/1/export', { responseType: 'blob' }),
    ).rejects.toThrow('权限不足：禁止导出')
  })
  it('正常 CSV Blob 不按 JSON envelope 解析', async () => {
    const blob = new Blob(['content,rating\n测试,5'])
    reply(blob, 'text/csv')
    expect((await http.get('/export', { responseType: 'blob' })).data).toBe(
      blob,
    )
  })
  it('网络失败提供明确提示', async () => {
    http.defaults.adapter = async (config) => {
      throw new AxiosError('Network Error', 'ERR_NETWORK', config)
    }
    await expect(request({ url: '/courses' })).rejects.toThrow('无法连接服务')
  })
  it('拒绝意外 HTML 响应', async () => {
    reply('<html>error</html>')
    await expect(request({ url: '/courses' })).rejects.toBeInstanceOf(ApiError)
  })
})
