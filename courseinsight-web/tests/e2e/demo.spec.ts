import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'

test('真实 Demo：网页登录 → 创建课程 → 评价 → CSV → RocketMQ → 结果 → 下载 → 看板', async ({
  page,
}) => {
  test.skip(
    !process.env.E2E_USERNAME || !process.env.E2E_PASSWORD,
    '需要通过环境变量提供隔离 Demo 账号',
  )
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/login')
  await page.screenshot({ path: 'test-results/demo-login-desktop.png', fullPage: true })
  await page
    .getByRole('textbox', { name: '用户名', exact: true })
    .fill(process.env.E2E_USERNAME!)
  await page
    .getByRole('textbox', { name: '密码', exact: true })
    .fill(process.env.E2E_PASSWORD!)
  await page.getByRole('button', { name: '登录工作台' }).click()
  await expect(page).toHaveURL(/\/courses$/)
  await page.getByRole('button', { name: '创建课程', exact: true }).click()
  const code = `WEB-${Date.now()}`
  const commentText = `课程案例清晰，希望增加实践环节。${code}`
  await page.getByRole('textbox', { name: '课程代码' }).fill(code)
  await page
    .getByRole('textbox', { name: '课程名称' })
    .fill('Web 联调 · 软件工程')
  await page.getByRole('textbox', { name: '教师姓名' }).fill('演示教师')
  await page
    .getByRole('textbox', { name: '课程简介' })
    .fill(
      '通过真实 Spring Boot API 创建，用于 Vue、RocketMQ 与 AI Stub 端到端验证。',
    )
  await page.getByRole('button', { name: '保存课程' }).click()
  await expect(page).toHaveURL(/\/courses\/\d+$/)
  const courseId = page.url().split('/').pop()!
  await page.getByRole('button', { name: '编辑课程' }).click()
  await page
    .getByRole('textbox', { name: '课程简介' })
    .fill('已通过网页登录、创建与编辑课程，继续验证评价和批次分析。')
  await page.getByRole('button', { name: '保存课程' }).click()
  await expect(
    page.getByText('已通过网页登录、创建与编辑课程，继续验证评价和批次分析。'),
  ).toBeVisible()
  const rating = page.getByRole('slider', { name: /课程评分/ })
  await rating.focus()
  await rating.press('ArrowRight')
  await rating.press('ArrowRight')
  await rating.press('ArrowRight')
  await rating.press('ArrowRight')
  await page.getByRole('textbox', { name: '评价内容' }).fill(commentText)
  await page.getByRole('button', { name: '提交评价', exact: true }).click()
  await expect(page.getByText(commentText, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '批量分析评价' }).click()
  await page
    .getByLabel('上传 CSV 文件')
    .setInputFiles('public/analysis-batch-sample.csv')
  await page.screenshot({ path: 'test-results/demo-upload-desktop.png', fullPage: true })
  await page.getByRole('button', { name: '上传并进入处理队列' }).click()
  await expect(page).toHaveURL(/\/analysis\/\d+$/)
  const batchUrl = page.url()
  await expect(
    page.getByText('本批次全部分析完成，结果已可查看和导出。'),
  ).toBeVisible({ timeout: 45000 })
  await expect(
    page.locator('.results-table').getByText('中性', { exact: true }).first(),
  ).toBeVisible()
  await page.reload()
  await expect(page).toHaveURL(batchUrl)
  await expect(
    page.getByText('本批次全部分析完成，结果已可查看和导出。'),
  ).toBeVisible()
  await page.screenshot({
    path: 'test-results/demo-batch-desktop.png',
    fullPage: true,
  })
  await page.getByRole('button', { name: '详情', exact: true }).first().click()
  await expect(page.getByText('教学建议', { exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
  const downloading = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载 CSV' }).click()
  const download = await downloading
  expect(download.suggestedFilename()).toMatch(/\.csv$/)
  const csv = await readFile((await download.path())!, 'utf8')
  expect(csv).toContain('课程讲解清晰')
  expect(csv).toContain('neutral')
  await page.goto(`/analytics?courseId=${courseId}`)
  await expect(page.getByRole('img', { name: /^评价情感分布/ })).toBeVisible()
  await page.screenshot({
    path: 'test-results/demo-analytics-desktop.png',
    fullPage: true,
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(batchUrl)
  await expect(
    page.getByText('本批次全部分析完成，结果已可查看和导出。'),
  ).toBeVisible()
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true)
  await page.screenshot({
    path: 'test-results/demo-batch-mobile.png',
    fullPage: true,
  })
  await page.getByRole('button', { name: '打开导航' }).click()
  await page.getByRole('link', { name: '个人信息' }).click()
  await expect(page.getByText(commentText, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '删除', exact: true }).first().click()
  await page.getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(page.getByText(commentText, { exact: true })).toHaveCount(0)
  expect(errors).toEqual([])
})
