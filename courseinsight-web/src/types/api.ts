export type Role = 'STUDENT' | 'TEACHER' | 'ADMIN'
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}
export interface PageResponse<T> {
  page: number
  size: number
  total: number
  totalPages: number
  items: T[]
}
export interface PageQuery {
  page: number
  size: number
}
export interface LoginRequest {
  username: string
  password: string
}
export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  userId: number
  username: string
  displayName: string
  role: Role
}
export interface CourseCreate {
  code: string
  name: string
  teacherName: string
  description: string
}
export interface CourseUpdate extends CourseCreate {
  status: number
}
export interface Course extends Omit<CourseUpdate, 'description'> {
  description: string | null
  id: number
  createdAt: string
  updatedAt: string
}
export interface CommentCreate {
  content: string
  rating: number
}
export interface Comment extends CommentCreate {
  id: number
  courseId: number
  anonymous: boolean
  status: number
  createdAt: string
  updatedAt: string
}
export type BatchStatus =
  | 'PROCESSING'
  | 'COMPLETED'
  | 'FAILED'
  | 'PARTIAL_FAILED'
export interface BatchCreated {
  batchId: number
  batchNo: string
  courseId: number
  totalCount: number
}
export interface BatchProgress extends BatchCreated {
  originalFilename: string
  status: BatchStatus
  waitingCount: number
  processingCount: number
  retryingCount: number
  successCount: number
  failedCount: number
  completionPercentage: number
  createdAt: string
  completedAt: string | null
}
// 后端字段为 JsonNode，保留 JSON 形状，避免将动态对象误断言为字符串数组。
export type JsonValue =
  | string
  | number
  | boolean
  | null
  | JsonValue[]
  | { [key: string]: JsonValue }
export interface BatchResult {
  taskId: number
  commentId: number
  content: string
  rating: number
  taskStatus: string
  retryCount: number
  failureReason: string | null
  deadLetteredAt: string | null
  taskCompletedAt: string | null
  resultId: number | null
  language: string | null
  sentiment: string | null
  confidence: number | null
  sentimentSource: string | null
  sentimentDevice: string | null
  topics: JsonValue
  topicEvidence: JsonValue
  keywords: JsonValue
  longTextHandled: boolean | null
  longTextTruncated: boolean | null
  advice: JsonValue
  riskLevel: string | null
  adviceSource: string | null
  resultCreatedAt: string | null
}
export interface BatchRetry {
  batchId: number
  requeuedCount: number
  requestedAt: string
}
export interface Analytics {
  courseId: number
  totalComments: number
  averageRating: number
  tasks: {
    total: number
    waiting: number
    processing: number
    success: number
    failed: number
    completionPercentage: number
  }
  sentiments: {
    total: number
    positive: number
    neutral: number
    negative: number
    positivePercentage: number
    neutralPercentage: number
    negativePercentage: number
  }
  risks: { high: number; middle: number; low: number; unclassified: number }
}
