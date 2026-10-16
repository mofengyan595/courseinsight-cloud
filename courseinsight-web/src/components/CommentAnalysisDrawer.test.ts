import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref } from 'vue'
import type { App, FunctionalComponent } from 'vue'
import { ApiError } from '../api/http'
import * as api from '../api'
import type { CommentAnalysis, AnalysisTaskStatus } from '../types/api'
import CommentAnalysisDrawer from './CommentAnalysisDrawer.vue'

vi.mock('../api', () => ({ commentAnalysis: vi.fn() }))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}
function response(id: number, status: AnalysisTaskStatus): CommentAnalysis {
  return {
    comment: {
      id,
      courseId: 1,
      content: `匿名反馈-${id}`,
      rating: 5,
      anonymous: true,
      status: 1,
      createdAt: '2026-10-11T12:00:00',
      updatedAt: '2026-10-11T12:00:00',
    },
    task: {
      id,
      taskNo: `task-${id}`,
      commentId: id,
      courseId: 1,
      status,
      retryCount: 0,
      failureReason: status === 'FAILED' ? '服务暂不可用' : null,
      startedAt: null,
      completedAt: null,
      deadLetteredAt: null,
      createdAt: '2026-10-11T12:00:00',
      updatedAt: '2026-10-11T12:00:00',
    },
    result:
      status === 'SUCCESS'
        ? {
            id,
            language: 'zh',
            sentiment: 'neutral',
            confidence: 0.9,
            sentimentSource: 'stub',
            sentimentDevice: 'cpu',
            topics: ['clarity'],
            topicEvidence: [{ evidence: '案例清晰' }],
            keywords: ['案例'],
            longTextHandled: false,
            longTextTruncated: false,
            advice: { summary: '增加实践' },
            riskLevel: 'low',
            adviceSource: 'stub',
            createdAt: '2026-10-11T12:00:01',
          }
        : null,
  }
}
const container: FunctionalComponent = (_, { slots }) =>
  h('div', slots.default?.())
const button: FunctionalComponent = (_, { slots }) =>
  h('button', slots.default?.())
const drawer: FunctionalComponent = (_, { slots, emit }) =>
  h('div', [
    h('button', { onClick: () => emit('close') }, '关闭分析'),
    slots.default?.(),
    slots.footer?.(),
  ])
const alert: FunctionalComponent<{ title: string; description?: string }> = (
  props,
) => h('div', [props.title, props.description])
let app: App | undefined
let host: HTMLDivElement
const commentId = ref(1)
const shown = ref(true)
async function flush() {
  await vi.advanceTimersByTimeAsync(0)
  await nextTick()
}
async function mount() {
  app = createApp({
    render: () =>
      shown.value
        ? h(CommentAnalysisDrawer, {
            commentId: commentId.value,
            onClose: () => {
              shown.value = false
            },
          })
        : null,
  })
  app.component('ElDrawer', drawer)
  app.component('ElButton', button)
  app.component('ElTag', container)
  app.component('ElSkeleton', container)
  app.component('ElAlert', alert)
  app.mount(host)
  await flush()
}
function click(label: string) {
  const button = [...host.querySelectorAll('button')].find(
    (item) => item.textContent === label,
  )
  expect(button).toBeDefined()
  button!.click()
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  commentId.value = 1
  shown.value = true
  host = document.createElement('div')
  document.body.append(host)
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host.remove()
  vi.useRealTimers()
})

describe('按需评价分析抽屉', () => {
  it('将建议和证据显示为中文卡片，兼容 camelCase 与 snake_case 行动类型', async () => {
    const value = response(1, 'SUCCESS')
    value.result!.adviceSource = 'llm_api'
    value.result!.advice = {
      summary: '案例清楚，可以补充实践。',
      problems: [
        {
          aspect: '实践',
          description: '实践机会不足',
          evidence: '希望增加实践',
        },
      ],
      suggestions: [
        {
          aspect: '实践',
          suggestion: '增加一次课堂练习',
          evidence: '希望增加实践',
          actionType: 'improve',
        },
        {
          aspect: '案例',
          suggestion: '保留案例讲解',
          evidence: '案例清晰',
          action_type: 'maintain',
        },
      ],
    }
    vi.mocked(api.commentAnalysis).mockResolvedValue(value)
    await mount()
    expect(host.querySelectorAll('.suggestion-card')).toHaveLength(2)
    expect(host.querySelector('.overview-card')?.textContent).toContain(
      '案例清楚，可以补充实践。',
    )
    expect(host.querySelector('.suggestion-list')?.textContent).toContain(
      '建议优化',
    )
    expect(host.querySelector('.suggestion-list')?.textContent).toContain(
      '保持优势',
    )
    expect(host.querySelector('.source-badge')?.textContent).toBe('大模型建议')
    expect(host.querySelector('.technical-details')?.hasAttribute('open')).toBe(
      false,
    )
    expect(host.querySelector('.report-section')?.textContent).not.toMatch(
      /actionType|action_type|suggestions|source:/,
    )
  })
  it('兼容缺省与非结构化字段，将文本作为纯文本展示', async () => {
    const value = response(1, 'SUCCESS')
    value.result!.advice = '<img src=x onerror=alert(1)>'
    value.result!.topicEvidence = null
    value.result!.keywords = { unexpected: true }
    value.result!.topics = null
    value.result!.confidence = null
    value.result!.adviceSource = 'local_fallback'
    vi.mocked(api.commentAnalysis).mockResolvedValue(value)
    await mount()
    expect(host.querySelector('.report-section')?.textContent).toContain(
      '<img src=x onerror=alert(1)>',
    )
    expect(host.querySelector('img')).toBeNull()
    expect(host.textContent).toContain('暂无维度证据')
    expect(host.textContent).toContain('本次使用本地建议')
  })
  it('WAITING → PROCESSING → SUCCESS 自动展示结果并在终态停止', async () => {
    vi.mocked(api.commentAnalysis)
      .mockResolvedValueOnce(response(1, 'WAITING'))
      .mockResolvedValueOnce(response(1, 'PROCESSING'))
      .mockResolvedValue(response(1, 'SUCCESS'))
    await mount()
    expect(host.textContent).toContain('等待分析')
    expect(host.textContent).not.toContain('置信度')
    await vi.advanceTimersByTimeAsync(2000)
    expect(host.textContent).toContain('分析中')
    await vi.advanceTimersByTimeAsync(2000)
    expect(host.textContent).toContain('分析完成')
    expect(host.textContent).toContain('90.0%')
    expect(host.textContent).toContain('增加实践')
    expect(host.textContent).toContain('案例清晰')
    await vi.advanceTimersByTimeAsync(60000)
    expect(api.commentAnalysis).toHaveBeenCalledTimes(3)
  })
  it('FAILED 展示原因并停止轮询', async () => {
    vi.mocked(api.commentAnalysis).mockResolvedValue(response(1, 'FAILED'))
    await mount()
    expect(host.textContent).toContain('分析失败')
    expect(host.textContent).toContain('服务暂不可用')
    expect(host.textContent).not.toContain('置信度')
    await vi.advanceTimersByTimeAsync(60000)
    expect(api.commentAnalysis).toHaveBeenCalledOnce()
  })
  it.each([401, 403, 404])(
    '%s 停止无意义请求并允许手动刷新',
    async (status) => {
      vi.mocked(api.commentAnalysis).mockRejectedValue(
        new ApiError(`错误-${status}`, status),
      )
      await mount()
      expect(host.textContent).toContain(`错误-${status}`)
      expect(host.textContent).toContain('自动查询已暂停')
      await vi.advanceTimersByTimeAsync(60000)
      expect(api.commentAnalysis).toHaveBeenCalledOnce()
      vi.mocked(api.commentAnalysis).mockResolvedValue(response(1, 'SUCCESS'))
      click('刷新分析')
      await flush()
      expect(host.textContent).toContain('分析完成')
      expect(host.textContent).not.toContain(`错误-${status}`)
      expect(api.commentAnalysis).toHaveBeenCalledTimes(2)
    },
  )
  it('切换评论取消在途请求并隔离迟到成功响应', async () => {
    const old = deferred<CommentAnalysis>()
    const current = deferred<CommentAnalysis>()
    vi.mocked(api.commentAnalysis).mockImplementation((id) =>
      id === 1 ? old.promise : current.promise,
    )
    await mount()
    const signal = vi.mocked(api.commentAnalysis).mock.lastCall![1]!
    commentId.value = 2
    await flush()
    expect(signal.aborted).toBe(true)
    old.resolve(response(1, 'SUCCESS'))
    await flush()
    expect(host.textContent).not.toContain('匿名反馈-1')
    current.resolve(response(2, 'SUCCESS'))
    await flush()
    expect(host.textContent).toContain('匿名反馈-2')
    expect(host.textContent).not.toContain('匿名反馈-1')
    await vi.advanceTimersByTimeAsync(10000)
    expect(api.commentAnalysis).toHaveBeenCalledTimes(2)
  })
  it('切换清除旧详情和错误，取消旧定时器及迟到失败', async () => {
    vi.mocked(api.commentAnalysis).mockResolvedValue(response(1, 'WAITING'))
    await mount()
    const late = deferred<CommentAnalysis>()
    vi.mocked(api.commentAnalysis).mockReturnValue(late.promise)
    await vi.advanceTimersByTimeAsync(2000)
    const signal = vi.mocked(api.commentAnalysis).mock.lastCall![1]!
    vi.mocked(api.commentAnalysis).mockRejectedValue(
      new ApiError('新评价不可访问', 403),
    )
    commentId.value = 2
    await flush()
    late.reject(new Error('旧请求错误'))
    await flush()
    expect(signal.aborted).toBe(true)
    expect(host.textContent).not.toContain('匿名反馈-1')
    expect(host.textContent).not.toContain('旧请求错误')
    expect(host.textContent).toContain('新评价不可访问')
    await vi.advanceTimersByTimeAsync(10000)
    expect(api.commentAnalysis).toHaveBeenCalledTimes(3)
    vi.mocked(api.commentAnalysis).mockResolvedValue(response(3, 'SUCCESS'))
    commentId.value = 3
    await flush()
    expect(host.textContent).not.toContain('新评价不可访问')
  })
  it.each(['close', 'unmount'])(
    '%s 取消请求，迟到响应不会恢复轮询',
    async (action) => {
      const pending = deferred<CommentAnalysis>()
      vi.mocked(api.commentAnalysis).mockReturnValue(pending.promise)
      await mount()
      const signal = vi.mocked(api.commentAnalysis).mock.lastCall![1]!
      if (action === 'close') click('关闭分析')
      else {
        app!.unmount()
        app = undefined
      }
      await flush()
      expect(signal.aborted).toBe(true)
      pending.resolve(response(1, 'WAITING'))
      await vi.advanceTimersByTimeAsync(10000)
      expect(api.commentAnalysis).toHaveBeenCalledOnce()
      expect(host.textContent).toBe('')
    },
  )
})
