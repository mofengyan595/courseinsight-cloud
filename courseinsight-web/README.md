# CourseInsight Web

独立 Vue 3 + TypeScript 课程评价与教学分析工作台。所有业务数据来自同仓库 Spring Boot REST API，浏览器不直接调用 Python、模型或 RocketMQ。

技术栈：Vue 3 Composition API / `<script setup>`、Vite、Vue Router、Pinia、Axios、Element Plus、ECharts。Pinia 仅保存登录状态，业务数据由页面和 composable 管理。

## 安装与运行

需要 Node.js 22.12+（本次使用 22.17.1）、JDK 17、Docker Engine 和 Compose v2。在仓库根目录启动隔离 Demo 依赖和真实 AI 服务：

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

`start` 默认启动真实 Python AI 服务，监听 8002；Java 使用 `demo` profile，已有 Demo 数据卷保留。Linux/macOS 使用 `bash scripts/demo.sh start`。首次构建需要下载 Python/PyTorch 依赖；代码更新后加 `-Rebuild`（Bash 为 `--rebuild`）。

BERT 权重放置路径见 [AI 服务说明](../courseinsight-ai-service/README.md)。权重或运行依赖不可用时按原有策略降级到 TF-IDF / 规则，实际来源可在单条和批次分析详情查看。

教学建议默认开启。启动时自动加载根 `.env`（若存在），再用 `demo.env` 覆盖数据库等演示配置。在根 `.env` 配置 `LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL` 后，仍使用 `start`，无需额外开关。也可从启动终端提供 LLM 环境变量；密钥只传给 AI 容器，前端不接触密钥。

启用后会将新评价文本、关键词和维度证据发送到配置的模型服务，可能消耗 API 额度。未配置或调用失败时保留本地兜底。查看 `llmConfigured=true` 只能证明已配置；应以新分析结果的 `adviceSource=llm_api` 确认实际调用成功。历史结果不会随配置变化自动重算。若只需要本地建议，清除根 `.env` 和启动终端中的 `LLM_API_KEY` 后重新启动真实 AI 服务。

检查或停止服务直接使用 `.\scripts\demo.ps1 ready` / `.\scripts\demo.ps1 stop`。仅需固定结果测试时运行 `.\scripts\demo.ps1 start -Stub`（Bash：`bash scripts/demo.sh start --stub`），对应的 `ready` / `smoke` 也加 `-Stub`。切换只影响后续任务，历史分析不会自动重新计算。

演示路径：登录 → 创建课程 → 课程详情 → 批量分析评价 → 上传 CSV → 等待任务终态 → 展开结果或查看详情 → 下载 CSV → 数据看板。

样例：[`public/analysis-batch-sample.csv`](public/analysis-batch-sample.csv)。使用 UTF-8，表头 `content,rating`，最多 2MB、200 条评价；content 非空且不超过 2000 字符，rating 为 1–5 整数。含逗号或换行的内容用双引号包围。完整内容和行级错误由后端校验。

普通本地开发也可使用现有 `local` profile，配置和账号准备见 [`../docs/development.md`](../docs/development.md)。前端固定请求同源 `/api`，Vite 开发代理目标为 `http://localhost:8080`，无需修改后端 CORS。前端不读取根目录 `.env`，模型与 LLM 配置仅属于服务端。

结束时在前端、Java 终端按 Ctrl+C；仓库根目录 `scripts/demo.ps1 stop` 停止依赖并保留数据卷。

## 页面与接口契约

| 页面 | 实际接口 | 行为 |
| --- | --- | --- |
| 登录 `/login` | `POST /api/auth/login` | 读取 accessToken、tokenType、expiresIn、userId、username、displayName、role |
| 课程列表 `/courses` | `GET /api/courses`、`POST /api/courses` | 关键词、分页、创建；创建响应 data 为课程 ID |
| 课程详情 `/courses/:id` | `GET /api/courses/{id}`、`PUT /api/courses/{id}` | 管理员可编辑并通过 teacherUserId 指定教师账号；不传该字段时保留原归属 |
| 课程评价 | `GET/POST /api/courses/{id}/comments`、`DELETE /api/comments/{id}` | 匿名评价列表、评分和提交；管理员可确认删除任意有效评价 |
| 单条评价分析 | `GET /api/comments/{commentId}/analysis` | 教师/管理员点击后查询；与批量逐条详情共用居中弹窗 |
| 个人信息 `/profile` | `GET /api/comments/me`、`DELETE /api/comments/{id}` | 登录身份信息、个人评价、确认删除 |
| 上传 `/analysis` | `POST /api/courses/{id}/analysis-batches` | 仅管理员可上传；FormData 的 file 字段，读取 batchId 后跳转 |
| 历史分析任务 `/analysis` | `GET /api/analysis-batches` | 教师仅见本人课程，管理员可见全部；按创建时间倒序分页，点击返回原批次，无需再次上传 |
| 批次 `/analysis/:batchId` | `GET /api/analysis-batches/{id}` | 状态、waiting/processing/retrying/success/failed 与完成百分比 |
| 结果 | `GET /api/analysis-batches/{id}/results` | 分页评价、情感、置信度、关键词、维度、证据、建议、风险和来源 |
| 导出 / 重试 | `GET /api/analysis-batches/{id}/export`、`POST /api/analysis-batches/{id}/retry-failed` | Blob CSV 下载；符合后端条件的死信任务重新入队 |
| 看板 `/analytics?courseId=…` | `GET /api/courses/{id}/analytics/summary` | 评论/评分、情感、任务执行、风险统计；无数据为空状态 |

JSON 响应为 `{ code, message, data }`，成功码 0；分页为 `{ page, size, total, totalPages, items }`，页码从 1 开始。分析动态字段在后端是 JsonNode，使用递归 JSON 类型展示数组、对象和 null，不假定是固定字符串数组。

当前后端没有资料更新、评价编辑接口，前端不提供虚构操作。已上传的任务保存在服务端，可从历史分析任务、保存的 URL 或输入 batchId 恢复查询。AdminUserController 用户管理属于可选第二阶段，当前 MVP 不包含。

## 权限与会话边界

- STUDENT：课程浏览、评价提交、个人评价管理。
- TEACHER：仅可只读查看归属本人的课程、评价、单条分析、已有批次和看板；不能创建/编辑课程、提交/删除评价、上传 CSV 或重试任务。服务端同时过滤列表和校验直接访问。
- ADMIN：负责课程维护、指定授课教师、批量导入及任务重试；保留创建/删除评价的测试能力。课程列表中的删除沿用软删除，保留分析任务记录；学生仅可删除本人评价。
- 学生提交后提示“评价提交成功，感谢你的反馈。”，不展示分析入口或结果。完整分析接口明确拒绝学生，包括自己的评价；原有仅查询任务状态的 `/analysis-task` 权限保持兼容。返回评论不包含学生身份。
- 前端按登录角色显示读写入口；服务端独立校验实际角色和评价所有权，403 明确提示并保留登录状态。
- 教师账号选择复用 `GET /api/admin/users`，只列出启用的 TEACHER，可搜索和分页。课程 `ownerUserId` 表示实际归属，`teacherName` 是展示姓名；未指定教师的新课程暂归管理员，编辑后可分配。改派后旧教师立即失去读取权限，权限检查先于详情和统计缓存。
- 入口角色来自登录快照，后端每次校验数据库当前角色/状态；权限调整后可重新登录更新入口。前端守卫不能作为安全边界。
- Token 只放在当前标签页 sessionStorage，不写入 URL、日志、源码或 localStorage。关闭标签页通常清除状态，但同源 JavaScript 仍可读取，无法防御 XSS，复制标签页也可能复制初始会话。本方案用于演示；生产需要 HTTPS、CSP、依赖审计与符合自身威胁模型的会话方案。
- 根据 expiresIn 记录过期时间；401 清理并跳转登录，403、429、网络和业务错误显示明确提示。后端没有刷新 Token 接口，不虚构刷新流程。
- `VITE_*` 会公开进入产物，禁止填写密码、JWT secret 或 API Key。`.env.example` 仅说明并保留空的测试账号变量。

## 异步状态与异常

上传成功仅代表批次、任务和 Outbox 意图已创建，等待 RocketMQ 调度。正常轮询在上次请求完成约 2 秒后进行，不重叠请求；PROCESSING 继续，COMPLETED/FAILED/PARTIAL_FAILED 停止。完成百分比来自后端，包含成功与失败，100% 不等于全部成功。

网络失败后依次等待 4 秒、8 秒，连续 3 次失败暂停；401/403/404 立即暂停。页面销毁时取消在途请求与定时器并忽略迟到结果。刷新或重试重新入队后恢复查询。成功/失败计数变化时更新当前页结果，也可独立刷新结果。

retry-failed 当前只恢复死信任务；requeuedCount=0 表示没有符合条件的任务，不代表分析成功。部分失败有独立提示和失败详情，不覆盖已有成功结果。

单条和批量逐条详情按点击加载同一个评价分析弹窗，列表不会逐条请求。WAITING/PROCESSING 显示“分析中”，按同样的间隔与退避规则轮询；SUCCESS/FAILED 停止。关闭弹窗、切换评论或离开页面会取消请求并丢弃迟到响应。完整结果只在 SUCCESS 返回，其余状态 result 为 null；单条查询不提供虚构的完成百分比或重试接口。弹窗先展示原文、分析摘要、情感与关键词，再展示建议卡片和维度证据；模型来源、时间、重试与原始返回数据收在“分析与运行信息”中，默认折叠。弹窗在右侧内容区居中，手机端在屏幕内居中；正文独立滚动，可用关闭按钮或 Escape 退出。

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

# 先在仓库根目录以 start -Stub 启动依赖，再启动 Java demo
$env:E2E_USERNAME = '<本地演示用户名>'
$env:E2E_PASSWORD = '<本地演示密码>'
npm run test:e2e -- tests/e2e/demo.spec.ts
# 学生注册、管理员赋予测试教师角色，实际经过 MQ 和 Stub
npm run test:e2e -- tests/e2e/comment-analysis-demo.spec.ts
Remove-Item Env:E2E_USERNAME, Env:E2E_PASSWORD
```

没有账号时真实 Demo 测试明确 skip，不算端到端通过。没有 Chrome 时可在 playwright.config.ts 更换已安装的浏览器 channel。测试不提交缓存、登录状态、下载临时文件或 trace。

真实 AI 已启动时，单独运行模型联调测试（会在隔离 Demo 中创建一门课程、单条评价和两行 CSV 批次，保留记录便于查看）：

```powershell
$env:E2E_USERNAME = '<本地演示用户名>'
$env:E2E_PASSWORD = '<本地演示密码>'
$env:E2E_REAL_AI = '1'
npm run test:e2e -- tests/e2e/real-ai.spec.ts
Remove-Item Env:E2E_USERNAME, Env:E2E_PASSWORD, Env:E2E_REAL_AI
```

测试默认要求实际后端为 BERT，同时检查网页结果、任务成功计数、持久化模型来源、CSV 下载与刷新恢复，不使用固定情感或置信度。启用外部建议后设置 `E2E_ADVICE_SOURCE=llm_api`，严格要求三条测试评价均获得大模型建议；本地验证设置为 `local_fallback` 时也要求外部 LLM 未配置。若有意验证降级后端，设置 `E2E_AI_BACKEND=tfidf` 或 `rule`；自定义 AI 端口时设置 `E2E_AI_URL`。原 `demo.spec.ts`、`comment-analysis-demo.spec.ts` 保留 Stub 固定结果断言，应在 Stub 模式运行。

本次实际结果、截图和验证边界见 [`../docs/frontend-validation.md`](../docs/frontend-validation.md)。AI Stub 验证完整 HTTP/异步链路，不代表真实 BERT 推理验证。

学生反馈与教师分析的接口、权限和补充验证见 [`../docs/teacher-comment-analysis.md`](../docs/teacher-comment-analysis.md)。真实测试只应运行在隔离 Demo；每次注册 3 个随机测试账号，后端注册限流为每来源 10 分钟 5 次，重复运行需等待窗口恢复。测试不会绕过限流或删除账号、课程。

## 部署

`npm run build` 生成 `dist/`。`npm run preview` 用于本机预览，包含本地 `/api` 代理，不作为生产服务。静态部署需同源代理 `/api` 到后端，并为 Vue Router history 配置 index.html 回退，见 [`deploy/nginx.conf.example`](deploy/nginx.conf.example)。生产不要暴露 Vite dev server，不使用公开 Demo 凭据。

单条分析新增后端只读查询和对应安全规则；不修改 migration、Outbox、缓存、任务执行或性能测试，原有 Java 分层测试和 Demo 保持独立。

视觉参考：[Python CourseInsight](https://github.com/Wanny276/nlp_final/blob/main/app.py) 的品牌色与反馈信息层级，Vue 页面独立实现，不复制 Streamlit 或 NLP 调用。
