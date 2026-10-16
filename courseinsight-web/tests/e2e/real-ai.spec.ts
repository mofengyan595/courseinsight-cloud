import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import type {
  AnalysisResult,
  BatchProgress,
  BatchResult,
  CommentAnalysis,
  PageResponse,
} from '../../src/types/api'

test('真实 AI：单条评价与 CSV 经 RocketMQ 分析，并在网页展示模型结果', async ({
  page,
  request,
}) => {
  test.skip(
    process.env.E2E_REAL_AI !== '1' ||
      !process.env.E2E_USERNAME ||
      !process.env.E2E_PASSWORD,
    '需要真实 AI Demo 和 E2E_REAL_AI=1、隔离 Demo 账号',
  )
  test.setTimeout(240000)
  const backend = process.env.E2E_AI_BACKEND || 'bert'
  expect(['bert', 'tfidf', 'rule']).toContain(backend)
  const health = await request.get(
    `${process.env.E2E_AI_URL || 'http://127.0.0.1:8002'}/health`,
    { timeout: 90000 },
  )
  expect(health.status()).toBe(200)
  const aiHealth = await health.json()
  expect(aiHealth).toMatchObject({
    service: 'courseinsight-ai-service',
    activeBackend: backend,
  })
  if (process.env.E2E_ADVICE_SOURCE === 'local_fallback')
    expect(aiHealth.llmConfigured).toBe(false)
  if (process.env.E2E_ADVICE_SOURCE === 'llm_api')
    expect(aiHealth.llmConfigured).toBe(true)
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/login')
  await page.screenshot({
    path: 'test-results/login-redesign-desktop.png',
    fullPage: true,
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({
    path: 'test-results/login-redesign-mobile.png',
    fullPage: true,
  })
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page
    .getByRole('textbox', { name: '用户名', exact: true })
    .fill(process.env.E2E_USERNAME!)
  await page
    .getByRole('textbox', { name: '密码', exact: true })
    .fill(process.env.E2E_PASSWORD!)
  await page.getByRole('button', { name: '登录工作台' }).click()
  await expect(page).toHaveURL(/\/courses$/)
  const token = await page.evaluate(
    () =>
      JSON.parse(sessionStorage.getItem('courseinsight.session.v1')!)
        .accessToken as string,
  )
  const headers = { Authorization: `Bearer ${token}` }
  await page.getByRole('button', { name: '创建课程', exact: true }).click()
  await page.getByRole('textbox', { name: '课程代码' }).fill(`AI-${Date.now()}`)
  await page.getByRole('textbox', { name: '课程名称' }).fill('真实 AI 联调课程')
  await page.getByRole('textbox', { name: '教师姓名' }).fill('联调教师')
  await page.getByRole('button', { name: '保存课程' }).click()
  await expect(page).toHaveURL(/\/courses\/\d+$/)
  const courseId = page.url().split('/').pop()!
  const content = '老师讲课很清楚，案例也很有帮助。'
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
  await page.getByRole('button', { name: '查看分析', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '评价分析' })
  await expect(
    drawer.getByText('分析完成', { exact: true }).first(),
  ).toBeVisible({ timeout: 90000 })
  const single = await request.get(`/api/comments/${commentId}/analysis`, {
    headers,
  })
  expect(single.status()).toBe(200)
  const detail = (await single.json()).data as CommentAnalysis
  expect(detail.task.status).toBe('SUCCESS')
  expect(detail.result).not.toBeNull()
  function verifyModelResult(result: AnalysisResult | BatchResult) {
    expect([backend, `${backend}+rule`]).toContain(result.sentimentSource)
    expect(['positive', 'neutral', 'negative']).toContain(result.sentiment)
    expect(result.confidence).toBeGreaterThanOrEqual(0)
    expect(result.confidence).toBeLessThanOrEqual(1)
    expect(result.topics).toEqual(expect.arrayContaining(['授课方式']))
    expect(result.keywords).toEqual(expect.any(Array))
    expect(result.advice).not.toBeNull()
    expect(['local_fallback', 'llm_api']).toContain(result.adviceSource)
    if (process.env.E2E_ADVICE_SOURCE)
      expect(result.adviceSource).toBe(process.env.E2E_ADVICE_SOURCE)
  }
  verifyModelResult(detail.result!)
  await drawer.getByText('分析与运行信息', { exact: true }).click()
  await expect(
    drawer.getByText(`情感来源：${detail.result!.sentimentSource}`, {
      exact: false,
    }),
  ).toBeVisible()
  await drawer.getByText('分析与运行信息', { exact: true }).click()
  await expect(drawer.getByText('教学改进建议', { exact: true })).toBeVisible()
  await expect(drawer.locator('.suggestion-card').first()).toBeVisible()
  await drawer.locator('.el-drawer__body').evaluate((element) => {
    element.scrollTop = 0
  })
  await page.screenshot({
    path: 'test-results/real-ai-comment.png',
    fullPage: false,
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await drawer.locator('.el-drawer__body').evaluate((element) => {
    element.scrollTop = 0
  })
  await page.screenshot({
    path: 'test-results/real-ai-comment-mobile.png',
    fullPage: false,
  })
  expect(
    await drawer.evaluate(
      (element) => element.scrollWidth <= element.clientWidth,
    ),
  ).toBe(true)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.keyboard.press('Escape')

  await page.getByRole('button', { name: '批量分析评价' }).click()
  const texts = [
    content,
    '老师讲课速度太快，案例讲解不清楚，希望放慢授课节奏。',
  ]
  await page.getByLabel('上传 CSV 文件').setInputFiles({
    name: 'real-ai-reviews.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from(
      `content,rating\n${texts[0]},5\n${texts[1]},2\n`,
      'utf8',
    ),
  })
  await page.getByRole('button', { name: '上传并进入处理队列' }).click()
  await expect(page).toHaveURL(/\/analysis\/\d+$/)
  const batchUrl = page.url()
  const batchId = batchUrl.split('/').pop()!
  await expect(
    page.getByText('本批次全部分析完成，结果已可查看和导出。'),
  ).toBeVisible({ timeout: 90000 })
  const progressResponse = await request.get(
    `/api/analysis-batches/${batchId}`,
    { headers },
  )
  expect(progressResponse.status()).toBe(200)
  const progress = (await progressResponse.json()).data as BatchProgress
  expect(progress).toMatchObject({
    status: 'COMPLETED',
    totalCount: 2,
    successCount: 2,
    failedCount: 0,
  })
  const resultsResponse = await request.get(
    `/api/analysis-batches/${batchId}/results`,
    { headers },
  )
  expect(resultsResponse.status()).toBe(200)
  const results = (await resultsResponse.json())
    .data as PageResponse<BatchResult>
  expect(results.total).toBe(2)
  expect(results.items).toHaveLength(2)
  for (const row of results.items) {
    expect(row.taskStatus).toBe('SUCCESS')
    expect(texts).toContain(row.content)
    verifyModelResult(row)
  }
  for (const text of texts)
    await expect(
      page.locator('.results-table').getByText(text, { exact: true }),
    ).toBeVisible()
  await page.getByRole('button', { name: '详情', exact: true }).first().click()
  await expect(
    page
      .getByRole('dialog', { name: '分析详情' })
      .getByText(`情感来源：${results.items[0]!.sentimentSource}`, {
        exact: false,
      }),
  ).toBeVisible()
  await page.screenshot({
    path: 'test-results/real-ai-batch.png',
    fullPage: true,
  })
  await page.keyboard.press('Escape')
  const downloading = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载 CSV' }).click()
  const download = await downloading
  const csv = await readFile((await download.path())!, 'utf8')
  for (const text of texts) expect(csv).toContain(text)
  await page.reload()
  await expect(
    page.getByText('本批次全部分析完成，结果已可查看和导出。'),
  ).toBeVisible()
  expect(errors).toEqual([])
})
