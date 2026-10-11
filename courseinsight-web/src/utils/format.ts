import type { JsonValue } from '../types/api'
export const roleLabel = (role?: string) =>
  ({ STUDENT: '学生', TEACHER: '教师', ADMIN: '管理员' })[role || ''] ||
  '未知角色'
export const sentimentLabel = (value: string | null) =>
  ({ positive: '积极', neutral: '中性', negative: '消极' })[value || ''] ||
  value ||
  '尚未分析'
export const statusLabel = (value: string) =>
  ({
    PROCESSING: '处理中',
    COMPLETED: '全部完成',
    FAILED: '全部失败',
    PARTIAL_FAILED: '部分失败',
    WAITING: '等待处理',
    RETRYING: '等待重试',
    SUCCESS: '成功',
  })[value] || value
export const riskLabel = (value: string | null) =>
  ({ high: '高风险', middle: '中风险', low: '低风险' })[value || ''] ||
  value ||
  '未分类'
export const dateLabel = (value?: string | null) =>
  value ? value.replace('T', ' ').slice(0, 19) : '—'
export function jsonLabel(value: JsonValue): string {
  if (value === null) return '暂无'
  if (Array.isArray(value))
    return value.length ? value.map(jsonLabel).join('、') : '暂无'
  if (typeof value === 'object')
    return (
      Object.entries(value)
        .map(([key, item]) => `${key}：${jsonLabel(item)}`)
        .join('\n') || '暂无'
    )
  return String(value)
}
