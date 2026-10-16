# Vue 前端验收记录

## 2026-10-16 单条分析、登录页与大模型建议

- 单条分析改为原文卡片、摘要与情感概览、建议卡片、维度证据；兼容建议行动类型的 camelCase / snake_case。技术参数和原始返回数据默认折叠；本地兜底与大模型建议明确区分。登录页改为浅紫色双栏布局，手机端使用单栏表单。未修改认证、对象权限和异步轮询流程。
- `npm run lint`、`npm run typecheck`、`npm run build` 通过；`npm test` 45 项通过，包含结构化建议、缺省字段、非结构化内容与纯文本渲染检查；构建仍有原有大 chunk 提示。
- Playwright 异常交互测试 12 项通过；本地 BERT / 本地建议完整联调 1 项通过，检查前先断言 `llmConfigured=false`。桌面、390px 手机端登录和单条分析截图已实际查看，未发现横向溢出。
- 真实 AI 模式默认生成教学建议，由 Compose 自动加载根 `.env`（若存在），再用 `demo.env` 覆盖隔离演示配置；没有把密钥传给前端或输出到日志。`-EnableLlm` / `--enable-llm` 保留兼容。PowerShell / Bash 语法与 Compose 配置校验通过。
- 用户明确授权后启用配置的 `chat.ecnu.edu.cn / ecnu-plus`。`E2E_ADVICE_SOURCE=llm_api` 的真实浏览器联调 1 项通过（约 46 秒）：单条评价及两行 CSV 均成功，三条结果的教学建议来源全部为 `llm_api`，情感来源为本地 BERT；同时检查建议卡片、技术信息折叠、下载与刷新恢复。
- 历史结果未重算，测试记录保留在隔离 Demo 数据库；未修改 Java / Python 业务代码，未重跑全部 Java 测试。此次联调验证调用与展示，不评价模型准确率或建议质量。以下为此前同日及 2026-10-11 的验证记录。

## 2026-10-16 真实 AI 联调补充

- `.\scripts\demo.ps1 start -RealAi` / `ready -RealAi` 通过；保留隔离 Demo 数据卷，8002 从 Stub 切换为当前源码的 Python 服务。首次完整镜像构建的依赖下载较慢，本次核对已有镜像的依赖清单、模型与源码哈希后，复用依赖并复制当前源码完成构建；未完成从空缓存构建验证。
- `/health` 实际返回 `activeBackend=bert`、`bertReady=true`、`llmConfigured=false`。
- Playwright `tests/e2e/real-ai.spec.ts`：1 项通过（约 35 秒）。网页登录、创建课程、提交单条评价、查看分析、上传两行 CSV、查看详情、CSV 下载及刷新恢复均经过真实 Java / RocketMQ / Python 服务。
- 单条任务 `SUCCESS`；批次 `COMPLETED`，`totalCount=2`、`successCount=2`、`failedCount=0`；逐条检查持久化情感来源为 BERT（允许原有规则修正），并检查置信度范围、课程维度、关键词、建议及网页来源展示。
- `npm run lint`、`npm run typecheck`、`npm test`（43 项）、`npm run build` 通过；构建仍有原有大 chunk 提示。Compose 配置校验、PowerShell / Bash 语法检查通过。
- 测试创建的课程、单条评价和批次保留在隔离 Demo 数据库，便于页面查看。未启用付费 LLM；建议来源为 `local_fallback`，未评价模型准确率、建议质量或性能。本次未修改 Java / Python 业务代码，未重跑全部 Java 测试。下面保留 2026-10-11 的 Stub 验证记录。

日期：2026-10-11。环境：Windows、Node.js 22.17.1、JDK 17、Docker Desktop、Chrome。

## 已执行检查

| 检查 | 实际结果 |
| --- | --- |
| `npm run lint` | 通过，0 warning |
| `npm run typecheck` | 通过 |
| `npm run build` | 通过；Vite 提示 Element Plus 与 ECharts 相关 chunk 超过 500KB，属于体积优化项 |
| `npm test` | 4 个测试文件、24 项通过 |
| Playwright 真实 Demo | 通过，使用真实 Spring Boot REST API 与隔离 Demo 依赖 |
| Playwright 异常交互 | 9 项通过，契约响应仅在测试中注入 |
| `scripts/test.ps1 -Suite fast` | 193 项通过，0 失败 / 错误 / 跳过 |
| `scripts/test.ps1 -Suite integration` | 73 项通过，真实 MySQL/Redis Testcontainers，0 失败 / 错误 / 跳过 |
| `scripts/demo.ps1 smoke` | 通过；真实异步批次 COMPLETED，3 条结果 |

前端源码未接入 Mock 数据。异常交互测试与真实联调分别验收，不将注入响应视为真实后端调用证据。

## 真实浏览器链路

`tests/e2e/demo.spec.ts` 使用现有公开、本地、隔离 Demo 管理员账号，账号通过进程环境提供，没有写入前端源码或构建。

1. 从网页登录，自动跳转课程列表。
2. 创建课程并进入详情，再编辑课程简介并读回。
3. 通过键盘评分组件选择 4 星，提交唯一内容的评价并读回。
4. 从课程详情进入上传页，上传前端提供的 3 行样例 CSV。
5. 跳转带 batchId 的 URL，经过 Java、Transactional Outbox、真实 RocketMQ、AI Stub 后进入 COMPLETED。
6. 网页显示 3 条成功结果、情感、置信度、风险和分析详情。
7. 刷新任务页面，依据 URL 恢复查询。
8. 下载 CSV 并检查实际文件包含评价内容和 neutral 分析结果。
9. 查询课程看板，显示真实的评论总数、平均评分、情感、任务和风险图表。
10. 切换 390×844 手机视口，检查页面无整体横向溢出、导航可打开，结果表可在局部横向滚动。
11. 进入个人信息，确认并删除本轮测试刚提交的评价。

测试创建的课程与 CSV 批次保留在隔离 Demo 数据库中，不删除数据卷。单条测试评价通过真实个人删除接口移除。

## 异常与边界

- 登录错误、未登录守卫、手机登录布局。
- 401 清理会话并跳转登录；STUDENT 隐藏管理入口，直接访问管理路由受阻。
- 403 明确权限不足，停止无意义轮询。
- 课程无数据、网络失败、手动恢复。
- 错误文件扩展名、后端 CSV 表头校验错误。
- PARTIAL_FAILED 显示独立提示、失败原因；终态不继续轮询，确认重试后显示重新入队并恢复状态查询。
- 零数据看板显示空状态，不渲染虚构图表，平均评分显示缺省。
- 已有课程简介为 null 时，编辑表单将其规范为空字符串，仍可正常保存。
- 单元测试另覆盖 FAILED/COMPLETED 终态、慢请求不重叠、退避与三次失败暂停、卸载取消、业务错误和 Blob JSON 错误。

## 截图

以下均来自真实 Demo，未填充随机或伪造业务数据。

登录：

![登录页](images/frontend-login-desktop.png)

CSV 上传：

![CSV 上传页](images/frontend-upload-desktop.png)

批次完成与实际结果：

![批次分析结果](images/frontend-batch-desktop.png)

真实统计看板：

![数据看板](images/frontend-analytics-desktop.png)

手机视口：

![手机批次页面](images/frontend-batch-mobile.png)

## 尚未验证 / 延后范围

- Demo 使用原有 deterministic AI Stub，固定返回 neutral、0.9 置信度、low 风险与 stub 来源；这验证完整调用链，不验证真实 BERT 模型推理、准确率或建议质量。
- 部分失败和死信恢复的前端交互使用可重复的测试响应验证；没有主动破坏 Demo 的 AI/MQ 服务制造真实失败。后端相关语义由现有 Java 单元与集成测试覆盖。
- 用户管理属于可选第二阶段，本次没有接入 AdminUserController。
- Nginx 同源代理示例已提供，未部署生产服务器或验证生产 HTTPS/CSP。
- Element Plus 全量注册与按页面懒加载的 ECharts 仍存在大 chunk 提示，可进一步按需导入；没有提高阈值掩盖提示。
- 本次未重新运行性能 benchmark，性能脚本、后端配置和任务逻辑均未修改，不声称吞吐量有变化。

仓库原有用户文件 `docs/resume-notes.md` 不属于本任务，保持未跟踪且不进入前端提交。
