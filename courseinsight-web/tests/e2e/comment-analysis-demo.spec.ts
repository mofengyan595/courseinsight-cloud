import { test, expect } from '@playwright/test'
import type { APIRequestContext, Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import type { CommentAnalysis } from '../../src/types/api'

async function loginApi(
  request: APIRequestContext,
  username: string,
  password: string,
) {
  const response = await request.post('/api/auth/login', {
    data: { username, password },
  })
  expect(response.status()).toBe(200)
  return (await response.json()).data.accessToken as string
}
async function loginPage(page: Page, username: string, password: string) {
  await page.goto('/login')
  await page
    .getByRole('textbox', { name: '用户名', exact: true })
    .fill(username)
  await page.getByRole('textbox', { name: '密码', exact: true }).fill(password)
  await page.getByRole('button', { name: '登录工作台' }).click()
  await expect(page).toHaveURL(/\/courses$/)
}

test('真实 Stub：学生评价 → RocketMQ → 教师逐条分析，含跨课程权限', async ({
  page,
  request,
}) => {
  test.skip(
    !process.env.E2E_USERNAME || !process.env.E2E_PASSWORD,
    '需要隔离 Demo 管理员环境变量',
  )
  test.setTimeout(90000)
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  const adminToken = await loginApi(
    request,
    process.env.E2E_USERNAME!,
    process.env.E2E_PASSWORD!,
  )
  const adminHeaders = { Authorization: `Bearer ${adminToken}` }
  const suffix = randomUUID().replaceAll('-', '').slice(0, 16)
  const password = randomUUID()
  const accounts = {
    student: `s_${suffix}`,
    teacher: `t_${suffix}`,
    outsider: `o_${suffix}`,
  }
  let teacherUserId: number | undefined
  for (const [role, username] of Object.entries(accounts)) {
    const registered = await request.post('/api/auth/register', {
      data: { username, password, displayName: '隔离 Demo 测试账号' },
    })
    expect(registered.status()).toBe(201)
    const user = (await registered.json()).data
    if (role === 'teacher') teacherUserId = user.id
    expect(user.role).toBe('STUDENT')
    if (role !== 'student') {
      const promoted = await request.patch(`/api/admin/users/${user.id}/role`, {
        headers: adminHeaders,
        data: { role: 'TEACHER' },
      })
      expect(promoted.status()).toBe(200)
    }
  }
  const created = await request.post('/api/courses', {
    headers: adminHeaders,
    data: {
      code: `CA-${suffix}`,
      name: '学生反馈与教师分析',
      teacherName: 'Demo 教师',
      description: '真实 MQ 与 AI Stub 联调',
      teacherUserId,
    },
  })
  expect(created.status()).toBe(201)
  const courseId = (await created.json()).data as number
  const content = `案例清晰，希望增加实践环节。${suffix}`
  await loginPage(page, accounts.student, password)
  await page.goto(`/courses/${courseId}`)
  const rating = page.getByRole('slider', { name: /课程评分/ })
  await rating.focus()
  for (let i = 0; i < 5; i++) await rating.press('ArrowRight')
  await page.getByRole('textbox', { name: '评价内容' }).fill(content)
  const submitted = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/api/courses/${courseId}/comments`) &&
      response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '提交评价', exact: true }).click()
  const commentResponse = await submitted
  expect(commentResponse.status()).toBe(201)
  const commentId = (await commentResponse.json()).data as number
  await expect(page.getByText('评价提交成功，感谢你的反馈。')).toBeVisible()
  await expect(page.getByText(content, { exact: true })).toBeVisible()
  await expect(
    page.getByRole('button', { name: '查看分析', exact: true }),
  ).toHaveCount(0)
  await expect(page.getByText('置信度')).toHaveCount(0)
  const studentToken = await page.evaluate(
    () =>
      JSON.parse(sessionStorage.getItem('courseinsight.session.v1')!)
        .accessToken as string,
  )
  const analysisUrl = `/api/comments/${commentId}/analysis`
  expect((await request.get(analysisUrl)).status()).toBe(401)
  expect(
    (
      await request.get(analysisUrl, {
        headers: { Authorization: `Bearer ${studentToken}` },
      })
    ).status(),
  ).toBe(403)
  expect(
    (
      await request.get(`/api/comments/${commentId}/analysis-task`, {
        headers: { Authorization: `Bearer ${studentToken}` },
      })
    ).status(),
  ).toBe(200)
  const outsiderToken = await loginApi(request, accounts.outsider, password)
  expect(
    (
      await request.get(analysisUrl, {
        headers: { Authorization: `Bearer ${outsiderToken}` },
      })
    ).status(),
  ).toBe(403)

  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await loginPage(page, accounts.teacher, password)
  let analysisQueries = 0
  page.on('request', (request) => {
    if (request.url().endsWith(analysisUrl)) analysisQueries++
  })
  await page.goto(`/courses/${courseId}`)
  await expect(
    page.getByRole('button', { name: '查看分析', exact: true }),
  ).toBeVisible()
  expect(analysisQueries).toBe(0)
  await expect(page.getByRole('button', { name: '查看数据看板' })).toBeVisible()
  await expect(page.getByRole('button', { name: '批量分析评价' })).toHaveCount(
    0,
  )
  await expect(
    page.getByRole('button', { name: '提交评价', exact: true }),
  ).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '删除评价', exact: true }),
  ).toHaveCount(0)
  await page.getByRole('button', { name: '查看分析', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '评价分析' })
  await expect(
    drawer.getByText('分析完成', { exact: true }).first(),
  ).toBeVisible({ timeout: 45000 })
  await expect(drawer.getByText('中性', { exact: false })).toBeVisible()
  await expect(drawer.getByText(/90.0%/)).toBeVisible()
  await drawer.getByText('分析与运行信息', { exact: true }).click()
  await expect(drawer.getByText(/情感来源：performance_stub/)).toBeVisible()
  await drawer.getByText('分析与运行信息', { exact: true }).click()
  await expect(drawer.getByText('教学改进建议', { exact: true })).toBeVisible()
  const finalQueries = analysisQueries
  await page.waitForTimeout(2300)
  expect(analysisQueries).toBe(finalQueries)
  await page.screenshot({
    path: 'test-results/teacher-comment-analysis.png',
    fullPage: true,
  })
  const persisted = await request.get(analysisUrl, { headers: adminHeaders })
  expect(persisted.status()).toBe(200)
  const detail = (await persisted.json()).data as CommentAnalysis
  expect(detail.task.status).toBe('SUCCESS')
  expect(detail.result?.sentimentSource).toBe('performance_stub')
  expect(detail.comment).not.toHaveProperty('userId')
  expect(detail.comment).not.toHaveProperty('username')
  expect(
    (
      await request.get('/api/comments/0/analysis', { headers: adminHeaders })
    ).status(),
  ).toBe(404)
  const removed = await request.delete(`/api/comments/${commentId}`, {
    headers: { Authorization: `Bearer ${studentToken}` },
  })
  expect(removed.status()).toBe(200)
  expect(
    (await request.get(analysisUrl, { headers: adminHeaders })).status(),
  ).toBe(404)
  expect(errors).toEqual([])
})
