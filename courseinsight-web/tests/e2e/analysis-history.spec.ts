import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'

// 仅使用契约响应验证浏览器交互，不创建真实课程或调用模型。
const createdAt = '2026-10-16T10:00:00'
const course = {
  id: 1,
  code: 'READ',
  name: '课程评价阅读测试',
  teacherName: '测试教师',
  status: 1,
  ownerUserId: 21,
}
const comment = {
  id: 70,
  courseId: 1,
  content: '案例清楚，但实践偏少，希望增加课堂练习。',
  rating: 3,
  anonymous: true,
  status: 1,
  createdAt,
  updatedAt: createdAt,
}
const result = {
  id: 80,
  language: 'zh',
  sentiment: 'neutral',
  confidence: 0.91,
  sentimentSource: 'test',
  sentimentDevice: 'cpu',
  topics: ['实践安排'],
  topicEvidence: [{ aspect: '实践安排', evidence: '希望增加课堂练习' }],
  keywords: ['案例', '实践'],
  longTextHandled: false,
  longTextTruncated: false,
  riskLevel: 'middle',
  adviceSource: 'llm_api',
  createdAt,
  advice: {
    summary: '案例讲解清楚，可增加实践机会。',
    suggestions: [
      {
        aspect: '实践安排',
        actionType: 'improve',
        suggestion: '每次案例讲解后安排一次课堂练习。',
        evidence: '希望增加课堂练习',
      },
    ],
  },
}
const summary = {
  batchId: 30,
  batchNo: 'BATCH-30',
  courseId: 1,
  courseName: course.name,
  originalFilename: '课程评价.csv',
  totalCount: 1,
  createdAt,
}
const progress = {
  ...summary,
  status: 'COMPLETED',
  waitingCount: 0,
  processingCount: 0,
  retryingCount: 0,
  successCount: 1,
  failedCount: 0,
  completionPercentage: 100,
  completedAt: createdAt,
}
const paged = (items: unknown[], total = items.length, page = 1) => ({
  page,
  size: 10,
  total,
  totalPages: Math.ceil(total / 10),
  items,
})

async function setup(page: Page, role = 'TEACHER') {
  let uploads = 0
  let historyFailed = false
  let deletes = 0
  let deleted = false
  let deletionFailed = false
  let writes = 0
  let assignedTeacher = 21
  let lastCourseWrite: { teacherUserId?: number } | undefined
  await page.addInitScript(
    (role) =>
      sessionStorage.setItem(
        'courseinsight.session.v1',
        JSON.stringify({
          accessToken: 'test-only-token',
          userId: role === 'TEACHER' ? 21 : 1,
          username: 'teacher',
          displayName: '测试教师',
          role,
          expiresAt: Date.now() + 3600000,
        }),
      ),
    role,
  )
  await page.route('http://127.0.0.1:5173/api/**', (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (route.request().method() !== 'GET') writes++
    let data: unknown
    if (path === '/api/comments/70' && route.request().method() === 'DELETE') {
      deletes++
      if (deletionFailed)
        return route.fulfill({
          status: 503,
          json: { code: 503, message: '删除失败，请稍后重试' },
        })
      deleted = true
      data = null
    } else if (path === '/api/courses/1/analysis-batches') {
      uploads++
      data = summary
    } else if (path === '/api/analysis-batches') {
      if (historyFailed)
        return route.fulfill({
          status: 503,
          json: { code: 503, message: '任务列表暂不可用' },
        })
      const pageNo = Number(url.searchParams.get('page') || 1)
      data = paged(
        [{ ...summary, batchId: pageNo === 1 ? 30 : 29 }],
        11,
        pageNo,
      )
    } else if (path === '/api/admin/users') {
      const choices = [
        {
          id: 21,
          username: 'teacher_a',
          displayName: '教师甲',
          role: 'TEACHER',
          status: 1,
        },
        {
          id: 22,
          username: 'teacher_b',
          displayName: '教师乙',
          role: 'TEACHER',
          status: 1,
        },
      ]
      const userId = Number(url.searchParams.get('userId'))
      data = paged(
        userId ? choices.filter((item) => item.id === userId) : choices,
      )
    } else if (path === '/api/courses' && route.request().method() === 'POST') {
      lastCourseWrite = route.request().postDataJSON()
      data = 1
    } else if (path === '/api/courses') data = paged([course])
    else if (path === '/api/courses/1' && route.request().method() === 'PUT') {
      lastCourseWrite = route.request().postDataJSON()
      assignedTeacher = lastCourseWrite?.teacherUserId || assignedTeacher
      data = 1
    } else if (path === '/api/courses/1')
      data = { ...course, ownerUserId: assignedTeacher }
    else if (path === '/api/comments/me') data = paged([comment])
    else if (path === '/api/courses/1/comments')
      data = paged(deleted ? [] : [comment])
    else if (path === '/api/comments/70/analysis')
      data = {
        comment,
        task: {
          id: 60,
          status: 'SUCCESS',
          retryCount: 0,
          startedAt: createdAt,
          completedAt: createdAt,
        },
        result,
      }
    else if (path === '/api/analysis-batches/30') data = progress
    else if (path === '/api/analysis-batches/30/results')
      data = paged([
        {
          ...comment,
          ...result,
          taskId: 60,
          commentId: 70,
          taskStatus: 'SUCCESS',
          resultId: 80,
        },
      ])
    else
      return route.fulfill({
        status: 404,
        json: { code: 404, message: '测试未定义接口' },
      })
    return route.fulfill({ json: { code: 0, data } })
  })
  return {
    uploads: () => uploads,
    deletes: () => deletes,
    writes: () => writes,
    courseWrite: () => lastCourseWrite,
    failDeletion: (value: boolean) => {
      deletionFailed = value
    },
    failHistory: (value: boolean) => {
      historyFailed = value
    },
  }
}

test('上传后离开、刷新仍能从历史返回，不会重复上传；支持分页和失败恢复', async ({
  page,
}) => {
  const state = await setup(page, 'ADMIN')
  await page.goto('/analysis?courseId=1')
  await page.getByLabel('上传 CSV 文件').setInputFiles({
    name: '课程评价.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('content,rating\n案例清楚,5'),
  })
  await page.getByRole('button', { name: '上传并进入处理队列' }).click()
  await expect(page).toHaveURL(/\/analysis\/30$/)
  await page.getByRole('button', { name: '返回批量分析' }).click()
  await page.reload()
  const history = page.getByRole('region', { name: '历史分析任务' })
  await expect(history.getByText('课程评价.csv')).toBeVisible()
  await history.getByRole('link', { name: '查看任务' }).click()
  await expect(page).toHaveURL(/\/analysis\/30$/)
  await expect(
    page.getByText('本批次全部分析完成，结果已可查看和导出。'),
  ).toBeVisible()
  expect(state.uploads()).toBe(1)
  await page.getByRole('button', { name: '返回批量分析' }).click()
  await history.getByRole('listitem', { name: '第 2 页', exact: true }).click()
  await expect(history.getByRole('link', { name: '查看任务' })).toHaveAttribute(
    'href',
    '/analysis/29',
  )
  state.failHistory(true)
  await history.getByRole('button', { name: '刷新任务' }).click()
  await expect(history.getByRole('alert')).toContainText('任务列表暂不可用')
  state.failHistory(false)
  await history.getByRole('button', { name: '重新加载' }).click()
  await expect(history.getByRole('link', { name: '查看任务' })).toBeVisible()
  await page.evaluate(() => scrollTo(0, 0))
  await page.screenshot({
    path: 'test-results/analysis-history-desktop.png',
    fullPage: true,
  })
})

for (const viewport of [
  { name: '桌面', width: 1440, height: 1000 },
  { name: '窄屏', width: 1024, height: 900 },
  { name: '手机', width: 390, height: 844 },
]) {
  test(`${viewport.name}单条和批量共用内容区居中弹窗，支持阅读、刷新和关闭`, async ({
    page,
  }) => {
    await setup(page)
    await page.setViewportSize(viewport)
    await page.goto('/courses/1')
    await expect(
      page.getByRole('button', { name: '删除评价', exact: true }),
    ).toHaveCount(0)
    await page.getByRole('button', { name: '查看分析', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '评价分析' })
    await expect(
      dialog.getByText('教学改进建议', { exact: true }),
    ).toBeVisible()
    const single = await dialog.locator('.analysis-report').innerText()
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await page.goto('/analysis/30')
    await page.getByRole('button', { name: '详情', exact: true }).click()
    await expect(
      dialog.getByText('教学改进建议', { exact: true }),
    ).toBeVisible()
    expect(await dialog.locator('.analysis-report').innerText()).toBe(single)
    await dialog.evaluate(async (node) => {
      await Promise.all(
        node.getAnimations({ subtree: true }).map((item) => item.finished),
      )
    })
    const panel = dialog.locator('.el-dialog')
    const bounds = (await panel.boundingBox())!
    const main = (await page.locator('.workspace-main').boundingBox())!
    expect(bounds.x).toBeGreaterThan(0)
    expect(
      Math.abs(bounds.x + bounds.width / 2 - (main.x + main.width / 2)),
    ).toBeLessThan(2)
    expect(
      Math.abs(bounds.y + bounds.height / 2 - viewport.height / 2),
    ).toBeLessThan(2)
    expect(bounds.y + bounds.height).toBeLessThan(page.viewportSize()!.height)
    expect(
      await dialog
        .locator('.analysis-report')
        .evaluate((node) => node.scrollWidth <= node.clientWidth),
    ).toBe(true)
    await page.screenshot({
      path: `test-results/analysis-dialog-${viewport.width}.png`,
      fullPage: false,
      animations: 'disabled',
    })
    await dialog.getByRole('button', { name: '刷新分析' }).click()
    await expect(dialog.getByText('91.0%', { exact: true })).toBeVisible()
    await dialog.getByText('分析与运行信息', { exact: true }).click()
    await expect(dialog.getByText(/情感来源：test/)).toBeVisible()
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(dialog).toHaveCount(0)
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true)
  })
}

test('管理员创建和编辑课程可指定教师账号，编辑时回显已有归属', async ({
  page,
}) => {
  const state = await setup(page, 'ADMIN')
  await page.goto('/courses/1')
  await page.getByRole('button', { name: '编辑课程', exact: true }).click()
  const edit = page.getByRole('dialog', { name: '编辑课程' })
  await expect(
    edit.getByText('教师甲（teacher_a）', { exact: true }),
  ).toBeVisible()
  await edit.getByRole('combobox', { name: '授课教师账号' }).click()
  await page
    .getByRole('option', { name: '教师乙（teacher_b）', exact: true })
    .click()
  await edit.getByRole('button', { name: '保存课程', exact: true }).click()
  await expect(edit).toHaveCount(0)
  expect(state.courseWrite()?.teacherUserId).toBe(22)
  await page.getByRole('button', { name: '编辑课程', exact: true }).click()
  await expect(
    edit.getByText('教师乙（teacher_b）', { exact: true }),
  ).toBeVisible()
  await edit.getByRole('button', { name: '取消', exact: true }).click()
  await page.goto('/courses')
  await page.getByRole('button', { name: '创建课程', exact: true }).click()
  const create = page.getByRole('dialog', { name: '创建课程' })
  await create.getByRole('textbox', { name: '课程代码' }).fill('ASSIGN')
  await create.getByRole('textbox', { name: '课程名称' }).fill('教师分配测试')
  await create.getByRole('combobox', { name: '授课教师账号' }).click()
  await page
    .getByRole('option', { name: '教师甲（teacher_a）', exact: true })
    .click()
  await create.getByRole('button', { name: '保存课程', exact: true }).click()
  await expect(page).toHaveURL(/\/courses\/1$/)
  expect(state.courseWrite()?.teacherUserId).toBe(21)
})

test('教师可只读查看课程、评价和已有批次，不显示或发出业务写操作', async ({
  page,
}) => {
  const state = await setup(page)
  await page.goto('/courses')
  await expect(
    page.getByRole('button', { name: '创建课程', exact: true }),
  ).toHaveCount(0)
  await page.goto('/courses/1')
  for (const name of ['编辑课程', '提交评价', '删除评价', '批量分析评价']) {
    await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0)
  }
  await expect(
    page.getByRole('button', { name: '查看分析', exact: true }),
  ).toBeVisible()
  await page.goto('/analysis')
  await expect(page.getByLabel('上传 CSV 文件')).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '上传并进入处理队列' }),
  ).toHaveCount(0)
  await page
    .getByRole('region', { name: '历史分析任务' })
    .getByRole('link', { name: '查看任务' })
    .click()
  await expect(page).toHaveURL(/\/analysis\/30$/)
  await expect(
    page.getByRole('button', { name: '详情', exact: true }),
  ).toBeVisible()
  await page.route('http://127.0.0.1:5173/api/analysis-batches/30', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          ...progress,
          status: 'PARTIAL_FAILED',
          failedCount: 1,
          successCount: 0,
        },
      },
    }),
  )
  await page.reload()
  await expect(
    page.getByText(
      '本批次处理已结束，部分评价分析失败。请查看失败原因，可重试符合条件的死信任务。',
    ),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: '重试失败任务', exact: true }),
  ).toHaveCount(0)
  await page.goto('/profile')
  await expect(
    page.getByRole('button', { name: '删除', exact: true }),
  ).toHaveCount(0)
  expect(state.writes()).toBe(0)
})

test('管理员可在课程列表确认删除评价；取消不发送请求，失败保留评价并允许重试', async ({
  page,
}) => {
  const state = await setup(page, 'ADMIN')
  await page.goto('/courses/1')
  await page.getByRole('button', { name: '删除评价', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '删除课程评价' })
  await confirmation.getByRole('button', { name: '取消', exact: true }).click()
  expect(state.deletes()).toBe(0)
  await expect(page.locator('.comment-item')).toHaveCount(1)

  state.failDeletion(true)
  await page.getByRole('button', { name: '删除评价', exact: true }).click()
  await confirmation
    .getByRole('button', { name: '确认删除', exact: true })
    .click()
  await expect(page.getByRole('alert')).toContainText('删除失败，请稍后重试')
  await expect(page.locator('.comment-item')).toHaveCount(1)

  state.failDeletion(false)
  await page.getByRole('button', { name: '删除评价', exact: true }).click()
  await confirmation
    .getByRole('button', { name: '确认删除', exact: true })
    .click()
  await expect(page.locator('.comment-item')).toHaveCount(0)
  await expect(page.getByText('还没有评价，分享第一条学习反馈吧')).toBeVisible()
  expect(state.deletes()).toBe(2)
})
