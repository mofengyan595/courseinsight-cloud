## 变更

为 CourseInsight Cloud 增加独立 `courseinsight-web/` Vue 工作台，使用户能在网页登录、创建课程、提交评价和上传 CSV，并通过真实 Java + RocketMQ 异步流程查看进度、分页结果、教学建议与下载 CSV。

- Vue 3 / `<script setup>`、TypeScript、Vite、Vue Router、Pinia、Axios、Element Plus、ECharts。
- 登录、路由守卫、sessionStorage 会话、401 清理、403 提示、退出登录。
- 课程搜索/分页/创建/编辑/详情，课程评价提交/查看，个人评价删除确认。
- CSV multipart `file` 上传，带 batchId 的可恢复 URL；约两秒轮询、终态停止、失败退避和卸载取消。
- 实际结果、失败原因、来源、关键词、维度、证据、建议、风险，CSV 导出与死信重新入队。
- 真实课程统计看板，零数据状态，响应式导航、表单验证和错误恢复。

## API 与后端范围

对接现有 auth/login、courses GET/POST/PUT、课程 comments GET/POST、comments/me、comments DELETE、analysis-batches 上传/进度/results/export/retry-failed、analytics/summary。

使用现有 `{code,message,data}`、成功码 0 与 `{page,size,total,totalPages,items}`，TypeScript 与真实 DTO 对齐。

没有修改 Java/Python 后端、数据库 migration、鉴权、Outbox、缓存、任务执行、MQ 或性能测试。Python 参考仓库只读。课程 DTO 不暴露 ownerUserId，前端根据角色显示入口，对象归属继续由服务端独立鉴权。

## 启动与部署

仓库根目录 `scripts/demo.ps1 start` → `courseinsight-server` 中以 demo profile 启动 Java → `courseinsight-web` 中 `npm ci`、`npm run dev` → 打开 http://127.0.0.1:5173。

Vite 将 `/api` 代理到 localhost:8080，无后端 CORS 改动。生产构建输出 dist，提供 Nginx 同源 `/api` 代理和 history fallback 示例。会话安全边界、技术结构和完整命令见 [前端 README](courseinsight-web/README.md)。

## 实际验证

- lint、TypeScript、生产构建通过；构建有 Element Plus/ECharts chunk 体积提示，未隐藏。
- Vitest 24 项通过。
- Playwright 真实 Demo 完整链路通过，包含创建/编辑课程、评价、CSV 异步分析、结果、下载、刷新恢复、看板、手机布局和删除自己的测试评价。
- 9 项独立异常交互测试通过：登录错误、失效 Token、角色守卫、网络恢复、文件错误、部分失败重试、403、空看板、可空课程简介编辑。
- Java fast 193 项、integration 73 项通过。
- 原有 Demo smoke 通过，批次完成并读取 3 条确定性结果。

截图和详细证据见 [前端验证记录](docs/frontend-validation.md)。

## 验证边界

AI Stub 只验证调用链，不等于真实 BERT 推理验证；部分失败的前端交互使用测试响应注入，没有主动破坏 Demo 服务制造失败。生产部署和性能 benchmark 未重跑。可选 AdminUserController 用户管理延后。

没有提交 node_modules、dist、真实 Token、密码或本地配置；保留 package-lock.json。用户原有未跟踪文档不进入本 PR。请保留代码审查，不自动合并 main。
