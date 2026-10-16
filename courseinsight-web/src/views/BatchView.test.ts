import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cloneVNode, createApp, h, nextTick, shallowRef } from 'vue'
import type {
  App,
  ComponentPublicInstance,
  FunctionalComponent,
  VNode,
  VNodeChild,
} from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import * as api from '../api'
import type { BatchProgress, BatchResult, PageResponse } from '../types/api'
import BatchView from './BatchView.vue'

vi.mock('../api', () => ({
  batchProgress: vi.fn(),
  batchResults: vi.fn(),
  retryBatch: vi.fn(),
  exportBatch: vi.fn(),
}))
vi.mock('../stores/auth', () => ({ useAuthStore: () => ({ canManage: true }) }))
vi.mock('../components/CommentAnalysisDialog.vue', () => ({
  default: {
    props: ['commentId'],
    setup: (props: { commentId: number }) => () =>
      h('div', { class: 'test-dialog' }, `评价-${props.commentId}`),
  },
}))
const messages = vi.hoisted(() => ({
  success: vi.fn(),
  confirm: vi.fn<() => Promise<void>>(),
}))
vi.mock('element-plus', () => ({
  ElMessage: { success: messages.success },
  ElMessageBox: { confirm: messages.confirm },
}))

function deferred<T>() {
  let resolve!: (data: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}
function progress(
  id: number,
  status: BatchProgress['status'] = 'PROCESSING',
): BatchProgress {
  return {
    batchId: id,
    batchNo: `BATCH-${id}`,
    courseId: 1,
    originalFilename: `batch-${id}.csv`,
    status,
    totalCount: 20,
    waitingCount: status === 'PROCESSING' ? 20 : 0,
    processingCount: 0,
    retryingCount: 0,
    successCount:
      status === 'COMPLETED' ? 20 : status === 'PARTIAL_FAILED' ? 19 : 0,
    failedCount: status === 'FAILED' ? 20 : status === 'PARTIAL_FAILED' ? 1 : 0,
    completionPercentage: status === 'PROCESSING' ? 0 : 100,
    createdAt: '2026-10-11T12:00:00',
    completedAt: null,
  }
}
function results(id: number, page = 1): PageResponse<BatchResult> {
  return {
    page,
    size: 10,
    total: 20,
    totalPages: 2,
    items: [
      {
        taskId: id,
        commentId: id,
        content: `评价-${id}`,
        rating: 5,
        taskStatus: 'SUCCESS',
        retryCount: 0,
        failureReason: null,
        deadLetteredAt: null,
        taskCompletedAt: null,
        resultId: id,
        language: null,
        sentiment: 'POSITIVE',
        confidence: 1,
        sentimentSource: null,
        sentimentDevice: null,
        topics: null,
        topicEvidence: null,
        keywords: null,
        longTextHandled: null,
        longTextTruncated: null,
        advice: null,
        riskLevel: null,
        adviceSource: null,
        resultCreatedAt: null,
      },
    ],
  }
}

// 保留真实页面、路由和请求 composable，仅替换不涉及本测试的 Element Plus 渲染。
const container: FunctionalComponent = (_, { slots }) =>
  h('div', slots.default?.())
const button: FunctionalComponent<{ disabled?: boolean }> = (
  props,
  { slots },
) => h('button', { disabled: props.disabled }, slots.default?.())
const table: FunctionalComponent<{ data: BatchResult[] }> = (
  props,
  { slots },
) =>
  h('div', { class: 'test-results' }, [
    JSON.stringify(props.data),
    ...props.data.flatMap((row) =>
      (slots.default?.() || []).map((column) => {
        const children = column.children as {
          default?: (scope: { row: BatchResult }) => VNodeChild
        } | null
        return children?.default?.({ row })
      }),
    ),
  ])
const pagination: FunctionalComponent<{ currentPage: number }> = (
  props,
  { emit },
) =>
  h(
    'button',
    {
      class: 'test-next-page',
      onClick: () => emit('current-change', 2),
    },
    `第 ${props.currentPage} 页`,
  )
const alert: FunctionalComponent<{ title: string }> = (props) =>
  h('div', props.title)
pagination.props = ['currentPage']

let app: App | undefined
let host: HTMLDivElement
async function settle() {
  await vi.advanceTimersByTimeAsync(0)
  await nextTick()
}
async function mountBatch(id = 123) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/analysis/:batchId', component: BatchView },
      { path: '/analysis', component: { render: () => h('div') } },
    ],
  })
  await router.push(`/analysis/${id}`)
  await router.isReady()
  const view = shallowRef<ComponentPublicInstance>()
  // 无 key，明确验证 Vue Router 复用同一组件实例的场景。
  app = createApp({
    render: () =>
      h(RouterView, null, {
        default: ({ Component }: { Component: VNode }) =>
          cloneVNode(Component, { ref: view }),
      }),
  })
  app.use(router)
  for (const name of ['ElTag', 'ElSkeleton', 'ElProgress', 'ElTableColumn'])
    app.component(name, container)
  app.component('ElButton', button)
  app.component('ElAlert', alert)
  app.component('ElTable', table)
  app.component('ElPagination', pagination)
  app.directive('loading', () => {})
  app.mount(host)
  await settle()
  return { router, view }
}
function click(label: string) {
  const target = [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  )
  expect(target, `应存在按钮：${label}`).toBeDefined()
  target!.click()
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  host = document.createElement('div')
  document.body.append(host)
  vi.mocked(api.batchProgress).mockImplementation(async (id) =>
    progress(Number(id)),
  )
  vi.mocked(api.batchResults).mockImplementation(async (id, query) =>
    results(Number(id), query.page),
  )
  messages.confirm.mockResolvedValue()
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host.remove()
  vi.useRealTimers()
})

describe('批次页路由复用隔离', () => {
  it('同一实例切换 ID 时取消旧在途查询，忽略旧进度和结果的迟到成功响应', async () => {
    const oldProgress = deferred<BatchProgress>()
    const oldResults = deferred<PageResponse<BatchResult>>()
    vi.mocked(api.batchProgress).mockImplementation((id) =>
      id === '123'
        ? oldProgress.promise
        : Promise.resolve(progress(456, 'COMPLETED')),
    )
    vi.mocked(api.batchResults).mockImplementation((id) =>
      id === '123' ? oldResults.promise : Promise.resolve(results(456)),
    )
    const { router, view } = await mountBatch()
    const instance = view.value
    expect(instance).toBeDefined()
    const progressSignal = vi.mocked(api.batchProgress).mock.calls[0]![1]!
    const resultsSignal = vi.mocked(api.batchResults).mock.calls[0]![2]!
    expect(resultsSignal.aborted).toBe(false)
    await router.push('/analysis/456')
    await settle()
    expect(view.value).toBe(instance)
    expect(progressSignal.aborted).toBe(true)
    expect(resultsSignal.aborted).toBe(true)
    expect(host.textContent).toContain('批次 #456')
    expect(host.textContent).toContain('batch-456.csv')
    expect(host.textContent).toContain('评价-456')
    oldProgress.resolve(progress(123))
    oldResults.resolve(results(123))
    await settle()
    await vi.advanceTimersByTimeAsync(10000)
    expect(host.textContent).not.toContain('batch-123.csv')
    expect(host.textContent).not.toContain('评价-123')
    expect(api.batchProgress).toHaveBeenCalledTimes(2)
  })

  it('切换立即清空旧进度、结果、弹窗和分页，取消旧轮询定时器', async () => {
    const pending = deferred<BatchProgress>()
    const pendingResults = deferred<PageResponse<BatchResult>>()
    const { router, view } = await mountBatch()
    const instance = view.value
    expect(instance).toBeDefined()
    host.querySelector<HTMLButtonElement>('.test-next-page')!.click()
    await settle()
    expect(api.batchResults).toHaveBeenLastCalledWith(
      '123',
      { page: 2, size: 10 },
      expect.any(AbortSignal),
    )
    click('详情')
    await nextTick()
    expect(host.querySelector('.test-dialog')?.textContent).toContain(
      '评价-123',
    )
    vi.mocked(api.batchProgress).mockImplementation(() => pending.promise)
    vi.mocked(api.batchResults).mockImplementation(() => pendingResults.promise)
    await router.push('/analysis/456')
    await settle()
    expect(view.value).toBe(instance)
    expect(host.textContent).not.toContain('batch-123.csv')
    expect(host.textContent).not.toContain('评价-123')
    expect(host.querySelector('.test-dialog')).toBeNull()
    expect(api.batchResults).toHaveBeenLastCalledWith(
      '456',
      { page: 1, size: 10 },
      expect.any(AbortSignal),
    )
    await vi.advanceTimersByTimeAsync(10000)
    expect(api.batchProgress).toHaveBeenCalledTimes(2)
    pending.resolve(progress(456, 'COMPLETED'))
    pendingResults.resolve(results(456))
    await settle()
    expect(host.querySelector('.test-next-page')?.textContent).toContain(
      '第 1 页',
    )
  })

  it('清除旧查询和操作错误，忽略取消后的迟到失败，保留手动恢复', async () => {
    const oldResults = deferred<PageResponse<BatchResult>>()
    const { router } = await mountBatch()
    vi.mocked(api.exportBatch).mockRejectedValue(new Error('旧导出错误'))
    click('下载 CSV')
    await settle()
    vi.mocked(api.batchProgress).mockRejectedValue(new Error('旧进度错误'))
    await vi.advanceTimersByTimeAsync(2000)
    vi.mocked(api.batchResults).mockRejectedValueOnce(new Error('旧结果错误'))
    click('刷新结果')
    await settle()
    expect(host.textContent).toContain('旧导出错误')
    expect(host.textContent).toContain('旧进度错误')
    expect(host.textContent).toContain('旧结果错误')
    vi.mocked(api.batchResults).mockReturnValueOnce(oldResults.promise)
    click('刷新结果')
    await settle()
    const oldSignal = vi.mocked(api.batchResults).mock.lastCall![2]!
    vi.mocked(api.batchProgress).mockRejectedValue(new Error('新进度错误'))
    vi.mocked(api.batchResults).mockResolvedValue(results(456))
    await router.push('/analysis/456')
    await settle()
    oldResults.reject(new Error('旧迟到错误'))
    await settle()
    expect(oldSignal.aborted).toBe(true)
    expect(host.textContent).not.toContain('旧')
    expect(host.textContent).toContain('新进度错误')
    vi.mocked(api.batchProgress).mockResolvedValue(progress(456, 'COMPLETED'))
    click('刷新状态')
    await settle()
    expect(host.textContent).toContain('batch-456.csv')
    expect(host.textContent).not.toContain('新进度错误')
    await vi.advanceTimersByTimeAsync(10000)
    expect(api.batchProgress).toHaveBeenCalledTimes(4)
  })

  it('切换后取消旧失败重试请求，迟到响应不会刷新新批次或显示成功提示', async () => {
    const retry = deferred<Awaited<ReturnType<typeof api.retryBatch>>>()
    vi.mocked(api.batchProgress).mockImplementation(async (id) =>
      progress(Number(id), 'PARTIAL_FAILED'),
    )
    vi.mocked(api.retryBatch).mockReturnValue(retry.promise)
    const { router } = await mountBatch()
    click('重试失败任务')
    await settle()
    const signal = vi.mocked(api.retryBatch).mock.lastCall![1]!
    await router.push('/analysis/456')
    await settle()
    expect(signal.aborted).toBe(true)
    retry.resolve({
      batchId: 123,
      requeuedCount: 1,
      requestedAt: '2026-10-11T12:00:00',
    })
    await settle()
    expect(api.batchProgress).toHaveBeenCalledTimes(2)
    expect(messages.success).not.toHaveBeenCalled()
  })

  it('旧批次确认框在切换后才确认时，不向新旧批次发送重试', async () => {
    const confirmation = deferred<void>()
    messages.confirm.mockReturnValue(confirmation.promise)
    vi.mocked(api.batchProgress).mockImplementation(async (id) =>
      progress(Number(id), 'FAILED'),
    )
    const { router } = await mountBatch()
    click('重试失败任务')
    await router.push('/analysis/456')
    confirmation.resolve()
    await settle()
    expect(api.retryBatch).not.toHaveBeenCalled()
  })

  it('离开批次路由时取消请求，不查询清空后的无效批次 ID', async () => {
    const pendingProgress = deferred<BatchProgress>()
    const pendingResults = deferred<PageResponse<BatchResult>>()
    vi.mocked(api.batchProgress).mockReturnValue(pendingProgress.promise)
    vi.mocked(api.batchResults).mockReturnValue(pendingResults.promise)
    const { router } = await mountBatch()
    const progressSignal = vi.mocked(api.batchProgress).mock.lastCall![1]!
    const resultsSignal = vi.mocked(api.batchResults).mock.lastCall![2]!
    await router.push('/analysis')
    await settle()
    expect(progressSignal.aborted).toBe(true)
    expect(resultsSignal.aborted).toBe(true)
    pendingProgress.resolve(progress(123))
    pendingResults.resolve(results(123))
    await vi.advanceTimersByTimeAsync(10000)
    expect(api.batchProgress).toHaveBeenCalledTimes(1)
    expect(api.batchResults).toHaveBeenCalledTimes(1)
    expect(host.textContent).not.toContain('批次 #')
  })

  it('页面卸载取消进度、结果和导出请求，迟到错误不再更新页面', async () => {
    const pendingProgress = deferred<BatchProgress>()
    const pendingResults = deferred<PageResponse<BatchResult>>()
    const pendingExport = deferred<void>()
    await mountBatch()
    vi.mocked(api.batchProgress).mockReturnValue(pendingProgress.promise)
    vi.mocked(api.batchResults).mockReturnValue(pendingResults.promise)
    vi.mocked(api.exportBatch).mockReturnValue(pendingExport.promise)
    click('刷新状态')
    click('下载 CSV')
    await settle()
    const progressSignal = vi.mocked(api.batchProgress).mock.lastCall![1]!
    const resultSignal = vi.mocked(api.batchResults).mock.lastCall![2]!
    const exportSignal = vi.mocked(api.exportBatch).mock.lastCall![1]!
    app!.unmount()
    app = undefined
    expect(progressSignal.aborted).toBe(true)
    expect(resultSignal.aborted).toBe(true)
    expect(exportSignal.aborted).toBe(true)
    pendingProgress.resolve(progress(123))
    pendingResults.resolve(results(123))
    pendingExport.reject(new Error('已离开页面'))
    await vi.advanceTimersByTimeAsync(10000)
    expect(api.batchProgress).toHaveBeenCalledTimes(2)
    expect(host.textContent).toBe('')
  })
})
