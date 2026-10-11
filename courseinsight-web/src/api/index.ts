import { http, request } from './http'
import type {
  Analytics,
  BatchCreated,
  BatchProgress,
  BatchResult,
  BatchRetry,
  Comment,
  CommentCreate,
  Course,
  CourseCreate,
  CourseUpdate,
  LoginRequest,
  LoginResponse,
  PageQuery,
  PageResponse,
} from '../types/api'

export const login = (data: LoginRequest) =>
  request<LoginResponse>({ method: 'POST', url: '/auth/login', data })
export const courses = (
  params: PageQuery & { keyword?: string },
  signal?: AbortSignal,
) => request<PageResponse<Course>>({ url: '/courses', params, signal })
export const course = (id: string | number, signal?: AbortSignal) =>
  request<Course>({ url: `/courses/${id}`, signal })
export const createCourse = (data: CourseCreate) =>
  request<number>({ method: 'POST', url: '/courses', data })
export const updateCourse = (id: number, data: CourseUpdate) =>
  request<number>({ method: 'PUT', url: `/courses/${id}`, data })
export const comments = (id: string, params: PageQuery, signal?: AbortSignal) =>
  request<PageResponse<Comment>>({
    url: `/courses/${id}/comments`,
    params,
    signal,
  })
export const myComments = (params: PageQuery, signal?: AbortSignal) =>
  request<PageResponse<Comment>>({ url: '/comments/me', params, signal })
export const createComment = (id: string, data: CommentCreate) =>
  request<number>({ method: 'POST', url: `/courses/${id}/comments`, data })
export const deleteComment = (id: number) =>
  request<void>({ method: 'DELETE', url: `/comments/${id}` })
export const uploadBatch = (id: number, file: File) => {
  const data = new FormData()
  data.append('file', file)
  return request<BatchCreated>({
    method: 'POST',
    url: `/courses/${id}/analysis-batches`,
    data,
    timeout: 60000,
  })
}
export const batchProgress = (id: string, signal?: AbortSignal) =>
  request<BatchProgress>({ url: `/analysis-batches/${id}`, signal })
export const batchResults = (
  id: string,
  params: PageQuery,
  signal?: AbortSignal,
) =>
  request<PageResponse<BatchResult>>({
    url: `/analysis-batches/${id}/results`,
    params,
    signal,
  })
export const retryBatch = (id: string) =>
  request<BatchRetry>({
    method: 'POST',
    url: `/analysis-batches/${id}/retry-failed`,
  })
export const analytics = (id: number, signal?: AbortSignal) =>
  request<Analytics>({ url: `/courses/${id}/analytics/summary`, signal })
export async function exportBatch(id: string) {
  const response = await http.get<Blob>(`/analysis-batches/${id}/export`, {
    responseType: 'blob',
    timeout: 60000,
  })
  const url = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = `analysis-batch-${id}.csv`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
