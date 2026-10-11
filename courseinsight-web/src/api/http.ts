import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import { clearSession, readSession } from '../auth/session'
import type { ApiResponse } from '../types/api'

export class ApiError extends Error {
  constructor(
    message: string,
    public status = 0,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}
export const http = axios.create({ baseURL: '/api', timeout: 15000 })
http.interceptors.request.use((config) => {
  const session = readSession()
  if (session && !config.url?.startsWith('/auth/'))
    config.headers.Authorization = `Bearer ${session.accessToken}`
  return config
})

async function envelope(
  value: unknown,
): Promise<Partial<ApiResponse<unknown>> | undefined> {
  if (value instanceof Blob) {
    try {
      return JSON.parse(await value.text())
    } catch {
      return undefined
    }
  }
  return value && typeof value === 'object'
    ? (value as Partial<ApiResponse<unknown>>)
    : undefined
}
function fail(status: number, message?: string): never {
  if (status === 401) {
    clearSession()
    window.dispatchEvent(new Event('auth-expired'))
  }
  throw new ApiError(
    status === 403
      ? `权限不足：${message || '无法访问此资源'}`
      : message ||
        (status === 401 ? '登录已过期，请重新登录' : '请求失败，请稍后重试'),
    status,
  )
}
http.interceptors.response.use(
  async (response) => {
    const contentType = String(response.headers['content-type'])
    if (response.config.responseType === 'blob' && !contentType.includes('json')) {
      if (!contentType.includes('text/csv')) throw new ApiError('导出响应不是 CSV 文件，请检查后端服务', 502)
      return response
    }
    const body = await envelope(response.data)
    if (!body || typeof body.code !== 'number')
      throw new ApiError('服务响应格式异常，请检查后端服务', 502)
    if (body.code !== 0) fail(body.code, body.message)
    return response
  },
  async (error: unknown) => {
    if (axios.isCancel(error)) throw error
    if (axios.isAxiosError(error)) {
      const body = await envelope(error.response?.data)
      if (error.response) fail(error.response.status, body?.message)
      throw new ApiError(
        error.code === 'ECONNABORTED'
          ? '请求超时，请检查网络或稍后重试'
          : '无法连接服务，请检查网络及后端是否启动',
      )
    }
    throw new ApiError('请求失败，请稍后重试')
  },
)
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiResponse<T>>(config)
  return response.data.data
}
export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}
