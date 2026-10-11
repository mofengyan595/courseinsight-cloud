# CourseInsight Web

独立 Vue 3 + TypeScript 课程评价与教学分析工作台。所有业务数据来自同仓库 Spring Boot REST API，浏览器不直接调用 Python、模型或 RocketMQ。

技术栈：Vue 3 Composition API / `<script setup>`、Vite、Vue Router、Pinia、Axios、Element Plus、ECharts。Pinia 仅保存登录状态，业务数据由页面和 composable 管理。

## 安装与运行

需要 Node.js 22.12+（本次使用 22.17.1）、JDK 17、Docker Engine 和 Compose v2。在仓库根目录启动隔离 Demo 依赖：

```powershell
.\scripts\demo.ps1 start
```

第二个终端启动后端并保持运行：

```powershell
Set-Location courseinsight-server
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=demo'
```

第三个终端启动前端：

```powershell
Set-Location courseinsight-web
npm ci
npm run dev
```

打开 <http://127.0.0.1:5173>，使用根 README 中的公开、本地 Demo 账号登录。

演示路径：登录 → 创建课程 → 课程详情 → 批量分析评价 → 上传 CSV → 等待任务终态 → 展开结果或查看详情 → 下载 CSV → 数据看板。

样例：[`public/analysis-batch-sample.csv`](public/analysis-batch-sample.csv)。使用 UTF-8，表头 `content,rating`，最多 2MB、200 条评价；content 非空且不超过 2000 字符，rating 为 1–5 整数。含逗号或换行的内容用双引号包围。完整内容和行级错误由后端校验。

普通本地开发可改用现有 `local` profile，配置和账号准备见 [`../docs/development.md`](../docs/development.md)。前端固定请求同源 `/api`，Vite 开发代理目标为 `http://localhost:8080`，无需修改后端 CORS。前端不读取根目录 `.env`，不需要真实模型、API Key 或付费服务。

结束时在前端、Java 终端按 Ctrl+C；仓库根目录 `scripts/demo.ps1 stop` 停止依赖并保留数据卷。

## 页面与接口契约

| 页面 | 实际接口 | 行为 |
| --- | --- | --- |
| 登录 `/login` | `POST /api/auth/login` | 读取 accessToken、tokenType、expiresIn、userId、username、displayName、role |
| 课程列表 `/courses` | `GET /api/courses`、`POST /api/courses` | 关键词、分页、创建；创建响应 data 为课程 ID |
| 课程详情 `/courses/:id` | `GET /api/courses/{id}`、`PUT /api/courses/{id}` | code/name/teacherName/description，编辑额外提交 status |
| 课程评价 | `GET/POST /api/courses/{id}/comments` | 匿名评价列表、评分和评价提交 |
| 单条评价分析 | `GET /api/comments/{commentId}/analysis` | 教师/管理员点击后查询；抽屉显示任务状态和完整分析 |
| 个人信息 `/profile` | `GET /api/comments/me`、`DELETE /api/comments/{id}` | 登录身份信息、个人评价、确认删除 |
| 上传 `/analysis` | `POST /api/courses/{id}/analysis-batches` | FormData 的 file 字段；读取 batchId 后跳转 |
| 批次 `/analysis/:batchId` | `GET /api/analysis-batches/{id}` | 状态、waiting/processing/retrying/success/failed 与完成百分比 |
| 结果 | `GET /api/analysis-batches/{id}/results` | 分页评价、情感、置信度、关键词、维度、证据、建议、风险和来源 |
| 导出 / 重试 | `GET /api/analysis-batches/{id}/export`、`POST /api/analysis-batches/{id}/retry-failed` | Blob CSV 下载；符合后端条件的死信任务重新入队 |
| 看板 `/analytics?courseId=…` | `GET /api/courses/{id}/analytics/summary` | 评论/评分、情感、任务执行、风险统计；无数据为空状态 |

JSON 响应为 `{ code, message, data }`，成功码 0；分页为 `{ page, size, total, totalPages, items }`，页码从 1 开始。分析动态字段在后端是 JsonNode，使用递归 JSON 类型展示数组、对象和 null，不假定是固定字符串数组。

当前后端没有批次列表、资料更新、评价编辑接口，前端不提供虚构操作。通过保存的 URL 或上传页输入 batchId 恢复任务。AdminUserController 用户管理属于可选第二阶段，当前 MVP 不包含。

## 权限与会话边界

- STUDENT：课程浏览、评价提交、个人评价管理。
- TEACHER：额外创建/编辑课程、单条评价分析、批量分析、看板；服务端限制为自己创建的课程。
- ADMIN：可管理所有课程。
- 学生提交后提示“评价提交成功，感谢你的反馈。”，不展示分析入口或结果。完整分析接口明确拒绝学生，包括自己的评价；原有仅查询任务状态的 `/analysis-task` 权限保持兼容。返回评论不包含学生身份。
- 课程 DTO 未暴露 ownerUserId，前端仅根据登录角色显示入口，不推测教师归属；对象权限由服务端独立校验，403 明确提示并保留登录状态。
- 入口角色来自登录快照，后端每次校验数据库当前角色/状态；权限调整后可重新登录更新入口。前端守卫不能作为安全边界。
- Token 只放在当前标签页 sessionStorage，不写入 URL、日志、源码或 localStorage。关闭标签页通常清除状态，但同源 JavaScript 仍可读取，无法防御 XSS，复制标签页也可能复制初始会话。本方案用于演示；生产需要 HTTPS、CSP、依赖审计与符合自身威胁模型的会话方案。
- 根据 expiresIn 记录过期时间；401 清理并跳转登录，403、429、网络和业务错误显示明确提示。后端没有刷新 Token 接口，不虚构刷新流程。
- `VITE_*` 会公开进入产物，禁止填写密码、JWT secret 或 API Key。`.env.example` 仅说明并保留空的测试账号变量。

## 异步状态与异常

上传成功仅代表批次、任务和 Outbox 意图已创建，等待 RocketMQ 调度。正常轮询在上次请求完成约 2 秒后进行，不重叠请求；PROCESSING 继续，COMPLETED/FAILED/PARTIAL_FAILED 停止。完成百分比来自后端，包含成功与失败，100% 不等于全部成功。

网络失败后依次等待 4 秒、8 秒，连续 3 次失败暂停；401/403/404 立即暂停。页面销毁时取消在途请求与定时器并忽略迟到结果。刷新或重试重新入队后恢复查询。成功/失败计数变化时更新当前页结果，也可独立刷新结果。

retry-failed 当前只恢复死信任务；requeuedCount=0 表示没有符合条件的任务，不代表分析成功。部分失败有独立提示和失败详情，不覆盖已有成功结果。

单条评价分析按点击加载，列表不会逐条请求。WAITING/PROCESSING 显示“分析中”，按同样的间隔与退避规则轮询；SUCCESS/FAILED 停止。关闭抽屉、切换评论或离开课程页会取消请求并丢弃迟到响应。完整结果只在 SUCCESS 返回，其余状态 result 为 null；单条查询不提供虚构的完成百分比或重试接口。

## 目录

```text
src/api/          Axios 边界与真实 API
src/auth/         sessionStorage 会话
src/types/        DTO、响应、分页类型
src/stores/       必要全局登录状态
src/router/       懒加载路由与守卫
src/layouts/      响应式工作台
src/components/   表单、选择器、分页、错误、图表
src/composables/  请求取消、操作状态、可测试轮询器
src/views/        独立业务页面
src/utils/        展示转换和文件基础校验
tests/e2e/        真实 Demo 与独立异常交互测试
public/           图标与 CSV 样例
```

## 测试与构建

```powershell
npm run lint
npm run typecheck
npm test
npm run build
```

Vitest 覆盖登录成功/失败、会话过期、角色、业务错误、401/403、网络、Blob、文件、轮询终态/退避/取消。

浏览器测试使用本机 Chrome，Playwright 自动启动或复用前端服务：

```powershell
# 不依赖后端的异常交互契约测试，响应仅在测试中注入
npm run test:e2e -- tests/e2e/ui-states.spec.ts

# 后端 demo 已启动时，由终端变量提供隔离演示账号
$env:E2E_USERNAME = '<本地演示用户名>'
$env:E2E_PASSWORD = '<本地演示密码>'
npm run test:e2e -- tests/e2e/demo.spec.ts
# 学生注册、管理员赋予测试教师角色，实际经过 MQ 和 Stub
npm run test:e2e -- tests/e2e/comment-analysis-demo.spec.ts
Remove-Item Env:E2E_USERNAME, Env:E2E_PASSWORD
```

没有账号时真实 Demo 测试明确 skip，不算端到端通过。没有 Chrome 时可在 playwright.config.ts 更换已安装的浏览器 channel。测试不提交缓存、登录状态、下载临时文件或 trace。

本次实际结果、截图和验证边界见 [`../docs/frontend-validation.md`](../docs/frontend-validation.md)。AI Stub 验证完整 HTTP/异步链路，不代表真实 BERT 推理验证。

学生反馈与教师分析的接口、权限和补充验证见 [`../docs/teacher-comment-analysis.md`](../docs/teacher-comment-analysis.md)。真实测试只应运行在隔离 Demo；每次注册 3 个随机测试账号，后端注册限流为每来源 10 分钟 5 次，重复运行需等待窗口恢复。测试不会绕过限流或删除账号、课程。

## 部署

`npm run build` 生成 `dist/`。`npm run preview` 用于本机预览，包含本地 `/api` 代理，不作为生产服务。静态部署需同源代理 `/api` 到后端，并为 Vue Router history 配置 index.html 回退，见 [`deploy/nginx.conf.example`](deploy/nginx.conf.example)。生产不要暴露 Vite dev server，不使用公开 Demo 凭据。

单条分析新增后端只读查询和对应安全规则；不修改 migration、Outbox、缓存、任务执行或性能测试，原有 Java 分层测试和 Demo 保持独立。

视觉参考：[Python CourseInsight](https://github.com/Wanny276/nlp_final/blob/main/app.py) 的品牌色与反馈信息层级，Vue 页面独立实现，不复制 Streamlit 或 NLP 调用。
