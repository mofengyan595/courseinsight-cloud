import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'

// 仅测试环境注入契约响应，用于可重复触发异常；真实链路见 demo.spec.ts。
const emptyPage = { page: 1, size: 10, total: 0, totalPages: 0, items: [] }
async function signedIn(page: Page, role = 'TEACHER') {
  await page.addInitScript((role) => {
    sessionStorage.setItem(
      'courseinsight.session.v1',
      JSON.stringify({
        accessToken: 'test-only-token',
        tokenType: 'Bearer',
        expiresIn: 3600,
        userId: 1,
        username: 'test',
        displayName: '交互测试',
        role,
        expiresAt: Date.now() + 3600000,
      }),
    )
  }, role)
}
test('登录错误显示、未登录路由守卫及手机登录布局', async ({ page }) => {
  await page.route('**/api/auth/login', (route) =>
    route.fulfill({
      status: 401,
      json: { code: 401, message: '用户名或密码错误', data: null },
    }),
  )
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/analysis/1')
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await page.getByRole('textbox', { name: '用户名', exact: true }).fill('test')
  await page.getByRole('textbox', { name: '密码', exact: true }).fill('wrong')
  await page.getByRole('button', { name: '登录工作台' }).click()
  await expect(
    page.getByRole('alert').getByText('用户名或密码错误'),
  ).toBeVisible()
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true)
})
test('失效 Token 清理会话并跳转登录', async ({ page }) => {
  await signedIn(page)
  await page.route('**/api/courses?**', (route) =>
    route.fulfill({
      status: 401,
      json: { code: 401, message: '令牌无效', data: null },
    }),
  )
  await page.goto('/courses')
  await expect(page).toHaveURL(/\/login/)
  expect(
    await page.evaluate(() =>
      sessionStorage.getItem('courseinsight.session.v1'),
    ),
  ).toBeNull()
})
test('STUDENT 看不到管理入口且不能直接访问分析页', async ({ page }) => {
  await signedIn(page, 'STUDENT')
  await page.route('**/api/courses?**', (route) =>
    route.fulfill({ json: { code: 0, data: emptyPage } }),
  )
  await page.goto('/courses')
  await expect(
    page.getByRole('button', { name: '创建课程', exact: true }),
  ).toHaveCount(0)
  await expect(
    page.getByRole('link', { name: '批量分析', exact: true }),
  ).toHaveCount(0)
  await page.goto('/analysis/1')
  await expect(page).toHaveURL(/\/forbidden$/)
  await expect(page.getByText('权限不足', { exact: true }).last()).toBeVisible()
})
test('课程空状态、网络失败和手动恢复', async ({ page }) => {
  await signedIn(page)
  let fail = true
  await page.route('**/api/courses?**', (route) =>
    fail
      ? route.abort('failed')
      : route.fulfill({ json: { code: 0, data: emptyPage } }),
  )
  await page.goto('/courses')
  await expect(page.getByRole('alert')).toContainText('无法连接服务')
  fail = false
  await page.getByRole('button', { name: '重新加载' }).click()
  await expect(page.getByText('还没有课程，从创建第一门课程开始')).toBeVisible()
})
test('文件错误、服务端 CSV 校验错误', async ({ page }) => {
  await signedIn(page)
  await page.route('**/api/courses?**', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          ...emptyPage,
          total: 1,
          items: [
            { id: 1, name: '测试课程', code: 'TEST', teacherName: '测试教师' },
          ],
        },
      },
    }),
  )
  await page.route('**/api/courses/1', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          id: 1,
          name: '测试课程',
          code: 'TEST',
          teacherName: '测试教师',
        },
      },
    }),
  )
  await page.route('**/api/courses/1/analysis-batches', (route) =>
    route.fulfill({
      status: 400,
      json: { code: 400, message: 'CSV 表头必须包含 content 和 rating' },
    }),
  )
  await page.goto('/analysis?courseId=1')
  await page.getByLabel('上传 CSV 文件').setInputFiles({
    name: 'bad.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('bad'),
  })
  await expect(page.getByRole('alert')).toContainText('仅支持 .csv 文件')
  await page.getByLabel('上传 CSV 文件').setInputFiles({
    name: 'bad.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('wrong,header\ndata,5'),
  })
  await page.getByRole('button', { name: '上传并进入处理队列' }).click()
  await expect(page.getByRole('alert')).toContainText(
    'CSV 表头必须包含 content 和 rating',
  )
})
test('PARTIAL_FAILED 终态、失败详情、重新入队后恢复轮询', async ({ page }) => {
  await signedIn(page)
  let checks = 0
  let retried = false
  const progress = {
    batchId: 1,
    batchNo: 'test-batch',
    courseId: 1,
    originalFilename: 'test.csv',
    status: 'PARTIAL_FAILED',
    totalCount: 2,
    waitingCount: 0,
    processingCount: 0,
    retryingCount: 0,
    successCount: 1,
    failedCount: 1,
    completionPercentage: 100,
    createdAt: '2026-10-11T10:00:00',
    completedAt: '2026-10-11T10:01:00',
  }
  await page.route('**/api/analysis-batches/1', (route) => {
    checks++
    return route.fulfill({
      json: {
        code: 0,
        data: retried
          ? {
              ...progress,
              status: 'PROCESSING',
              waitingCount: 1,
              failedCount: 0,
              completionPercentage: 50,
              completedAt: null,
            }
          : progress,
      },
    })
  })
  await page.route('**/api/analysis-batches/1/results?**', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          ...emptyPage,
          total: 1,
          items: [
            {
              taskId: 1,
              commentId: 1,
              content: '测试失败评价',
              rating: 2,
              taskStatus: 'FAILED',
              retryCount: 3,
              failureReason: 'AI 服务暂不可用',
              deadLetteredAt: '2026-10-11T10:01:00',
              confidence: null,
              sentiment: null,
              topics: null,
              topicEvidence: null,
              keywords: null,
              advice: null,
              riskLevel: null,
            },
          ],
        },
      },
    }),
  )
  await page.route('**/api/analysis-batches/1/retry-failed', (route) => {
    retried = true
    return route.fulfill({
      json: {
        code: 0,
        data: {
          batchId: 1,
          requeuedCount: 1,
          requestedAt: '2026-10-11T10:02:00',
        },
      },
    })
  })
  await page.goto('/analysis/1')
  await expect(
    page.getByText(
      '本批次处理已结束，部分评价分析失败。请查看失败原因，可重试符合条件的死信任务。',
    ),
  ).toBeVisible()
  await expect(page.getByText(/自动查询已停止/)).toBeVisible()
  const atTerminal = checks
  await page.waitForTimeout(2300)
  expect(checks).toBe(atTerminal)
  await page.getByRole('button', { name: '详情', exact: true }).first().click()
  await expect(page.getByText(/AI 服务暂不可用/)).toBeVisible()
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '重试失败任务' }).click()
  await page.getByRole('button', { name: '重新入队', exact: true }).click()
  await expect(
    page.getByText(
      '文件已上传，任务已创建；等待队列调度或正在分析。上传成功不代表分析完成。',
    ),
  ).toBeVisible()
  await expect.poll(() => checks).toBeGreaterThan(atTerminal)
})
test('服务端 403 显示权限不足并暂停轮询', async ({ page }) => {
  await signedIn(page)
  await page.route('**/api/analysis-batches/**', (route) =>
    route.fulfill({
      status: 403,
      json: { code: 403, message: '无权管理其他教师的课程' },
    }),
  )
  await page.goto('/analysis/1')
  await expect(page.getByRole('alert').first()).toContainText('权限不足')
  await expect(page.getByText(/自动查询已暂停/)).toBeVisible()
})

test('空看板显示空状态，不生成虚构指标', async ({ page }) => {
  await signedIn(page)
  await page.route('**/api/courses?**', (route) =>
    route.fulfill({ json: { code: 0, data: emptyPage } }),
  )
  await page.route('**/api/courses/1', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: { id: 1, name: '空课程', code: 'EMPTY', teacherName: '测试教师' },
      },
    }),
  )
  await page.route('**/api/courses/1/analytics/summary', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          courseId: 1,
          totalComments: 0,
          averageRating: 0,
          tasks: {
            total: 0,
            waiting: 0,
            processing: 0,
            success: 0,
            failed: 0,
            completionPercentage: 0,
          },
          sentiments: {
            total: 0,
            positive: 0,
            neutral: 0,
            negative: 0,
            positivePercentage: 0,
            neutralPercentage: 0,
            negativePercentage: 0,
          },
          risks: { high: 0, middle: 0, low: 0, unclassified: 0 },
        },
      },
    }),
  )
  await page.goto('/analytics?courseId=1')
  await expect(
    page.getByText('暂无统计数据，提交评价或上传 CSV 后再来查看'),
  ).toHaveCount(3)
  await expect(page.getByText('暂无评分数据')).toBeVisible()
  await expect(page.locator('.insight-chart')).toHaveCount(0)
})

test('已有课程简介为 null 时仍可编辑保存', async ({ page }) => {
  await signedIn(page)
  const current = {
    id: 1,
    code: 'NULL-DESC',
    name: '无简介课程',
    teacherName: '测试教师',
    description: null,
    status: 1,
    createdAt: '2026-10-11T10:00:00',
    updatedAt: '2026-10-11T10:00:00',
  }
  let saved = false
  await page.route('**/api/courses/1/comments?**', (route) =>
    route.fulfill({ json: { code: 0, data: emptyPage } }),
  )
  await page.route('**/api/courses/1', (route) => {
    if (route.request().method() === 'PUT') {
      const body = route.request().postDataJSON()
      expect(body.description).toBe('')
      expect(body.status).toBe(1)
      saved = true
      return route.fulfill({ json: { code: 0, data: 1 } })
    }
    return route.fulfill({ json: { code: 0, data: current } })
  })
  await page.goto('/courses/1')
  await page.getByRole('button', { name: '编辑课程' }).click()
  await expect(page.getByRole('textbox', { name: '课程简介' })).toHaveValue('')
  await page.getByRole('button', { name: '保存课程' }).click()
  await expect.poll(() => saved).toBe(true)
  await expect(page.getByRole('dialog')).toHaveCount(0)
})

const reviewCourse = {
  id: 1,
  code: 'REVIEW',
  name: '评价课程',
  teacherName: '测试教师',
  description: '',
  status: 1,
  createdAt: '2026-10-11T12:00:00',
  updatedAt: '2026-10-11T12:00:00',
}
const review = {
  id: 10,
  courseId: 1,
  content: '匿名学习反馈',
  rating: 5,
  anonymous: true,
  status: 1,
  createdAt: '2026-10-11T12:00:00',
  updatedAt: '2026-10-11T12:00:00',
}
test('学生提交只显示感谢反馈，不查询或展示教学分析', async ({ page }) => {
  await signedIn(page, 'STUDENT')
  let submitted = false
  let analysisRequests = 0
  await page.route('**/api/courses/1', (route) =>
    route.fulfill({ json: { code: 0, data: reviewCourse } }),
  )
  await page.route('**/api/courses/1/comments?**', (route) =>
    route.fulfill({
      json: {
        code: 0,
        data: {
          ...emptyPage,
          total: submitted ? 1 : 0,
          items: submitted ? [review] : [],
        },
      },
    }),
  )
  await page.route('**/api/courses/1/comments', (route) => {
    submitted = true
    return route.fulfill({ status: 201, json: { code: 0, data: 10 } })
  })
  await page.route('**/api/comments/*/analysis', (route) => {
    analysisRequests++
    return route.fulfill({ status: 403, json: { code: 403 } })
  })
  await page.goto('/courses/1')
  const rating = page.getByRole('slider', { name: /课程评分/ })
  await rating.focus()
  await rating.press('ArrowRight')
  await page.getByRole('textbox', { name: '评价内容' }).fill(review.content)
  await page.getByRole('button', { name: '提交评价', exact: true }).click()
  await expect(page.getByText('评价提交成功，感谢你的反馈。')).toBeVisible()
  await expect(page.getByText(review.content, { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '查看分析' })).toHaveCount(0)
  await expect(
    page.getByText(/分析任务已创建|置信度|教学改进建议/),
  ).toHaveCount(0)
  expect(analysisRequests).toBe(0)
})

for (const role of ['TEACHER', 'ADMIN']) {
  test(`${role} 按点击查询单条分析，离开课程停止查询`, async ({ page }) => {
    await signedIn(page, role)
    let requests = 0
    await page.route('**/api/courses?**', (route) =>
      route.fulfill({
        json: {
          code: 0,
          data: { ...emptyPage, total: 1, items: [reviewCourse] },
        },
      }),
    )
    await page.route('**/api/courses/1', (route) =>
      route.fulfill({ json: { code: 0, data: reviewCourse } }),
    )
    await page.route('**/api/courses/1/comments?**', (route) =>
      route.fulfill({
        json: {
          code: 0,
          data: {
            ...emptyPage,
            total: 2,
            items: [review, { ...review, id: 11, content: '另一条反馈' }],
          },
        },
      }),
    )
    await page.route('**/api/comments/*/analysis', (route) => {
      requests++
      expect(route.request().url()).toMatch(/\/comments\/10\/analysis$/)
      return route.fulfill({
        json: {
          code: 0,
          data: {
            comment: review,
            task: {
              id: 1,
              status: 'WAITING',
              retryCount: 0,
              failureReason: null,
              startedAt: null,
              completedAt: null,
            },
            result: null,
          },
        },
      })
    })
    await page.goto('/courses')
    await page.getByRole('link', { name: '评价课程', exact: true }).click()
    await expect(
      page.getByRole('button', { name: '查看分析', exact: true }),
    ).toHaveCount(2)
    expect(requests).toBe(0)
    await page
      .getByRole('button', { name: '查看分析', exact: true })
      .first()
      .click()
    await expect(page.getByRole('dialog', { name: '评价分析' })).toContainText(
      '等待分析',
    )
    expect(requests).toBe(1)
    if (role === 'TEACHER') {
      await page.keyboard.press('Escape')
      await expect(page.getByRole('dialog')).toHaveCount(0)
      await page.getByRole('button', { name: '返回课程列表' }).click()
    } else {
      // 抽屉仍在等待时通过浏览器返回触发 SPA 路由卸载。
      await page.goBack()
    }
    await expect(page).toHaveURL(/\/courses$/)
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await page.waitForTimeout(2300)
    expect(requests).toBe(1)
  })
}
