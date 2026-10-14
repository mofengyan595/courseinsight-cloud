# CourseInsight Cloud

CourseInsight Cloud 是一个课程评价与教学分析系统。学生可以提交评分和匿名反馈，教师可以管理课程、查看评价分析和统计，管理员可以跨课程管理。

评价提交后由后台异步分析，生成情感、关键词、课程维度和教学建议。项目使用 Vue 工作台、单体 Spring Boot 业务后端和独立的 Python AI 服务，支持通过本地 Demo 体验完整流程。

## 功能与展示

- 课程管理：搜索、分页、创建和编辑课程。
- 评价管理：提交评分与匿名评价，查看、删除自己的评价；教师按需查看单条评价的分析结果。
- 批量分析：上传 CSV，查看逐条分析结果，下载结果 CSV；单次最多 200 条、文件不超过 2 MB。上传时校验文件大小、行数、表头和 UTF-8 编码；导出时对可能被表格软件识别为公式的文本进行处理。
- 任务管理：查看批次进度、成功与失败数量和失败原因，支持对符合条件的失败任务发起重试；刷新页面后也可以通过批次 URL 继续查看进度。
- 数据看板：展示课程评价数、平均评分、情感分布、任务状态和风险统计。
- 权限控制：学生、教师、管理员三种角色；教师的管理和分析权限限于本人创建的课程，学生不能读取完整 AI 分析结果。

下面是仓库中已有的真实 Demo 截图。演示使用固定结果的 AI Stub，展示的是业务流程，不代表模型分析质量。

批次进度与结果：

![批次分析结果](docs/images/frontend-batch-desktop.png)

教师查看单条评价分析：

![教师评价分析](docs/images/teacher-comment-analysis.png)

更多页面见[前端验证记录](docs/frontend-validation.md#截图)，权限与交互说明见[学生反馈与教师分析](docs/teacher-comment-analysis.md)。

## 技术栈

| 层次 | 主要技术与版本 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.5.16、Spring Security、JWT |
| 持久化 | MySQL 8.4、MyBatis-Plus 3.5.17、Flyway |
| 缓存与消息 | Redis 7.2、RocketMQ 5.3.2 |
| 前端 | Vue 3.5、TypeScript 5.9、Vite 7.2、Element Plus 2.11、ECharts 6 |
| AI 服务 | Python 3.12、FastAPI 0.139.2、BERT / TF-IDF / 规则降级 |
| 测试与观测 | JUnit 5、Testcontainers、Vitest、Playwright、k6、Prometheus / Grafana |

## 系统架构

```mermaid
flowchart TB
    Web["Vue 工作台"] -->|REST API / JWT| API
    subgraph Boot["Spring Boot 业务后端（单体）"]
        API["认证与业务接口"] --> Service["业务服务与权限校验"]
        Publisher["Outbox 发布器"]
        Consumer["分析消费者"]
    end
    Service -->|评价、任务、Outbox 同一事务| DB[("MySQL")]
    Service <-->|缓存、限流、排行榜| Redis[("Redis")]
    DB -->|待发送事件| Publisher
    Publisher -->|at-least-once| MQ["RocketMQ"]
    MQ --> Consumer
    Consumer -->|HTTP| AI["FastAPI AI 服务 / Demo Stub"]
    Consumer -->|条件更新任务、保存结果| DB
```

MySQL 保存业务、任务和结果，Redis 保存缓存与可重建的派生数据。Outbox 发布器和消费者均在 Spring Boot 应用内，RocketMQ 负责传递分析任务；浏览器只访问 Java API，不直接调用 AI 服务。

完整 AI 服务优先使用 BERT，不可用时降级到 TF-IDF 或规则；教学建议可选调用 LLM，未配置或调用失败时使用本地模板。Demo 将 AI 服务替换为符合相同 HTTP 合约的 Stub，其余链路使用真实组件。

## 核心技术实现

### RocketMQ 与 Transactional Outbox

评价落库和消息发送分属数据库与消息系统，直接双写会留下“数据已提交，消息未发出”的窗口。提交评价或上传批次时，系统在一个 MySQL 事务中写入评价、分析任务和 Outbox 事件，再由定时发布器发送到 RocketMQ。

Outbox 发布和任务租约恢复使用独立调度器，避免消息发送等待阻塞超时任务的恢复。发布器使用固定线程池和有界提交窗口，限制单个应用实例的并发发送数量。每次领取事件生成 `publish_token`，发送成功或失败的状态更新必须匹配该 token，防止超时恢复后旧发送结果覆盖新的发布尝试。

已发送成功的 Outbox 事件可按保留期限分批清理 `SENT` 记录。该功能默认关闭，不影响待发送或失败事件的重试。

应用通过 Outbox 重试和消费端幂等处理重复投递，按 at-least-once（至少一次）模型设计：Broker 已接收消息但 `SENT` 未落库时，恢复后可能重发。消费者通过任务状态、事件代次和条件更新处理重复消息，不承诺 exactly-once。当前本地 RocketMQ 使用单节点异步刷盘，尚未保证 Broker 持久化故障下的消息不丢失。

### 异步任务状态与并发控制

上传成功表示任务已创建，不表示分析已完成。单条任务记录 `WAITING / PROCESSING / SUCCESS / FAILED`；批次根据成功和终止失败数量汇总为 `PROCESSING / COMPLETED / PARTIAL_FAILED / FAILED`，等待 MQ 重试的失败任务单独计数。

执行前通过条件更新领取任务，`current_event_id` 标识入队代次，`execution_token` 标识本次执行。结果与失败写入都必须匹配当前代次、执行 token 和 `PROCESSING` 状态；成功状态与分析结果在同一事务中保存，避免旧执行覆盖新结果。

执行租约及过期判断使用 MySQL 时钟，恢复器为过期任务创建新代次和 Outbox 事件。前端轮询至终态，遇到网络失败会退避，离开页面时取消请求。当前没有租约续期，超长 AI 调用仍可能重复发生；条件写入只保护最终状态。

### Redis 缓存

课程详情和统计采用 Cache Aside：优先读取 Redis，未命中时查询 MySQL 并回填。课程详情最大 TTL 为 30 分钟，空值和统计缓存最大 TTL 为 5 分钟，并加入 80%–100% 的随机抖动，减少缓存集中失效。

针对热点课程详情同时未命中的情况，查询使用单实例锁槽和二次缓存检查，减少并发重复回源。相关业务写入在事务提交后失效缓存；详情回填与提交后的失效操作共用同一锁槽，减少旧数据重新写入缓存的情况。

Redis 不可用时仍回退 MySQL。上述并发保护只作用于单实例，缓存删除失败以及跨实例回填仍可能造成短暂的数据不一致；缓存失效没有持久化重试，需要后续失效或 TTL 到期收敛。缓存命中也仍会执行用户权限所需的数据库查询。

课程热度排行榜使用 ZSet，Lua 脚本完成数据写入后再设置就绪标记，避免读取半成品。

### Spring Security 与 JWT

API 使用无状态 JWT 认证，校验签名、有效期和 issuer 后，再读取数据库中的当前用户角色与启用状态，使禁用账号和角色变更能及时生效。

接口层限制角色，服务层继续校验课程归属及评价所有权。学生评价对外匿名展示，完整分析仅供有课程管理权限的教师或管理员读取；前端隐藏入口只是交互控制，不能替代后端授权。

实现入口、重试和恢复细节见[技术设计与可靠性边界](docs/development.md#技术设计与可靠性边界)。

## 快速启动（Quick Start）

Demo 在宿主机运行 Java 和前端，在 Docker 中运行 MySQL、Redis、RocketMQ 和固定延迟 100 ms 的 AI Stub。它使用独立的 Compose 项目、数据库和数据卷，无需模型权重、LLM 密钥或付费 API，也无需创建 `.env`。

### 环境要求

- JDK 17，确保 `java` 可用；Maven 由仓库中的 Wrapper 提供。
- 已启动的 Docker Desktop / Docker Engine，包含 Docker Compose v2。
- Git；启动前端另需 Node.js 22.12+ 和 npm。
- 默认端口：后端 `8080`、前端 `5173`、MySQL `3307`、Redis `6380`、NameServer `9876`、Broker `10911 / 10909`、MQ 指标 `5557`、AI Stub `8002`。Demo 与完整本地环境共用部分宿主机端口，不能按默认配置同时启动。

首次运行需要下载 Docker 镜像和 Maven/npm 依赖，耗时取决于网络。

### Windows PowerShell

```powershell
git clone https://github.com/mofengyan595/courseinsight-cloud.git
Set-Location courseinsight-cloud

# 启动依赖并等待容器健康
.\scripts\demo.ps1 start

# 在当前终端启动后端并保持运行
Set-Location courseinsight-server
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=demo'
```

出现 `Started CourseinsightServerApplication` 后，在仓库根目录打开第二个终端：

```powershell
.\scripts\demo.ps1 ready
.\scripts\demo.ps1 smoke
```

### macOS / Linux

```bash
git clone https://github.com/mofengyan595/courseinsight-cloud.git
cd courseinsight-cloud

bash scripts/demo.sh start
cd courseinsight-server
bash ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

后端启动后，在仓库根目录的第二个终端运行：

```bash
bash scripts/demo.sh ready
bash scripts/demo.sh smoke
```

这里显式使用 Bash 执行 Maven Wrapper，无需修改文件执行权限。

### 启动前端与演示账号

在仓库根目录另开终端，以下命令适用于上述两种环境：

```sh
cd courseinsight-web
npm ci
npm run dev
```

打开 <http://127.0.0.1:5173>：

| 用户名 | 密码 | 角色 |
| --- | --- | --- |
| `demo_admin` | `demo-admin-password` | `ADMIN` |

账号仅在 `demo` profile 中初始化。它和 [`demo.env`](demo.env) 中的 JWT 密钥都是公开、本地演示配置，不得用于其他环境。

网页演示路径：登录 → 创建课程 → 课程详情 → 批量分析评价 → 上传 CSV → 查看进度和结果 → 下载 CSV → 数据看板。

上传页可下载样例，也可使用 [`analysis-batch-sample.csv`](docs/examples/analysis-batch-sample.csv)。文件采用 UTF-8，表头为 `content,rating`，评分为 1–5 的整数。开发服务器将 `/api` 代理到 `http://localhost:8080`。

### 验证运行结果与停止服务

`ready` 检查依赖容器健康和 `/actuator/health`；`smoke` 依次验证登录、课程创建与读回、3 行 CSV 上传、批次到达 `COMPLETED`，以及取回 3 条 `sentimentSource=performance_stub` 的成功结果。每一步输出 `[PASS] / [FAIL]`，失败时返回非零退出码。

`/api/health` 仅用于存活检查；Actuator health 包含 MySQL、Redis 状态，但不包含 RocketMQ、AI 的健康检查，因此演示时应使用 `ready` 脚本。smoke 每次会创建课程和批次，数据保留在隔离 Demo 中，其耗时不作为性能结果。

结束时在前端和 Java 终端按 `Ctrl+C`，再从仓库根目录停止依赖，保留数据卷：

```powershell
# Windows
.\scripts\demo.ps1 stop
```

```bash
# macOS / Linux
bash scripts/demo.sh stop
```

需要运行完整 AI 服务时，按[本地开发文档](docs/development.md#启动本地应用)创建并配置 `.env`，使用 `local` profile 启动。BERT 权重准备见 [AI 服务说明](courseinsight-ai-service/README.md)；监控、端口与常见问题见[运行配置与排错](docs/development.md#运行配置与排错)。

## 测试与性能

Java 测试分为无外部服务的单元、MVC/安全与 HTTP/MQ 合约测试，以及使用真实 MySQL、Redis Testcontainers 的集成测试。从仓库根目录运行：

```powershell
.\scripts\test.ps1 -Suite fast
.\scripts\test.ps1 -Suite integration  # 需要 Docker Engine
.\scripts\test.ps1 -Suite all          # 需要 Docker Engine
```

macOS/Linux 可在 `courseinsight-server` 中分别使用 `bash ./mvnw test '-Dgroups=!integration'`、`bash ./mvnw test '-Dgroups=integration'` 和 `bash ./mvnw test`。测试不读取根目录 `.env`，不启动项目 Compose；默认测试中的 MQ/AI 使用 mock，真实异步链路由 Demo smoke 单独验证。

2026 年 10 月 14 日完成的后端审计验证中，317 项 Java 测试全部通过，其中 MySQL、Redis 集成测试使用 Testcontainers。验证覆盖缓存并发、调度隔离、Outbox 清理与输入安全，详细记录见[后端工程审计报告](docs/backend-audit.md#验证记录)。

前端在 `courseinsight-web` 中运行 `npm run lint`、`npm run typecheck`、`npm test` 和 `npm run build`；Playwright 覆盖真实 Demo 与异常交互，两类验证分别记录。AI 服务测试见[开发文档](docs/development.md#ai-服务测试)，浏览器测试前提及执行方式见[前端 README](courseinsight-web/README.md#测试与构建)。

仓库保留了 k6 与指标采集报告，以下为历史本机实验中的代表性结果，均为每组 3 次：

| 实验 | 指标 | 调整前 | 调整后 |
| --- | --- | --- | --- |
| Redis 课程详情缓存 | p95 | 14.31 ms | 10.69 ms |
| Redis 课程详情缓存 | 每轮 MySQL `Com_select` | 6039 | 3039 |
| Outbox 发布并发 | 吞吐 | 36.595 tasks/s | 49.468 tasks/s |
| Outbox 发布并发 | 平均完成时间 | 27.559 s | 20.326 s |

- 缓存实验：100 RPS × 30 秒，相同 ID 顺序，miss/hit 交替。
- Outbox 实验：每轮 1000 任务、100 ms AI Stub，消费者 8，发布批次 100、间隔 1000 ms；对比串行与并发 4。

这些结果来自单台 Windows 主机，只适用于报告中的代码版本、负载与配置，不代表生产性能或真实 BERT/LLM 推理吞吐。本次后端审计未重新进行性能压测，上表不能解释为新缓存实现或调度隔离带来的性能提升。应用默认发布并发仍为 2，缓存命中路径仍有用户状态查询。

测试环境、复现命令与原始结果见 [Cache A/B 报告](performance/results/2026-08-09-phase-3-http-cache-P3_HTTP.md)、[Outbox 并发报告](performance/results/2026-08-10-phase-7-outbox-publisher-P7_OUTBOX.md)及[性能实验入口](performance/README.md)。Demo 与浏览器历史验证见[验收记录](docs/teacher-comment-analysis.md#本次实际验证2026-10-11)。

## 项目结构与相关文档

```text
courseinsight-cloud/
├─ courseinsight-server/       Spring Boot 业务 API、权限、任务与 Outbox
├─ courseinsight-web/          Vue 工作台与前端测试
├─ courseinsight-ai-service/   FastAPI 与 NLP 分析
├─ scripts/                   Demo 启停、smoke 与 Java 分层测试
├─ docs/                      开发说明、功能设计、截图与 CSV 样例
├─ performance/               AI Stub、k6 脚本与实验报告
├─ deploy/observability/      Prometheus / Grafana 配置
├─ compose.demo.yaml          隔离 Demo 依赖
└─ compose.yaml               完整本地依赖与可选监控
```

- [本地开发与测试](docs/development.md)：环境配置、测试、服务启停、监控和排错。
- [技术设计与可靠性边界](docs/development.md#技术设计与可靠性边界)：Outbox、执行租约、条件更新、缓存一致性及源码入口。
- [后端工程审计](docs/backend-audit.md)：缓存并发、Outbox 调度、输入校验、安全边界及测试记录。
- [学生反馈与教师分析](docs/teacher-comment-analysis.md)：接口、权限、按需查询和验证边界。
- [前端说明](courseinsight-web/README.md)与[页面验收记录](docs/frontend-validation.md)：API 对接、异步交互、构建和截图。
- [AI 服务说明](courseinsight-ai-service/README.md)：分析接口、降级与模型权重。
- [性能实验](performance/README.md)与[报告索引](performance/results/README.md)：负载、方法、结果与限制。
