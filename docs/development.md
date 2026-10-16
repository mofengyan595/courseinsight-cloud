# CourseInsight 本地开发与测试

首次运行可使用根目录 README 的[快速启动](../README.md#快速启动quick-start)和 `demo` profile。
本页说明完整本地 AI 环境、分层测试、监控和技术设计；这些可选内容不影响 Demo 的启动。

## Java 测试

测试不读取根目录 `.env`，也不会启动根目录的完整 Compose 栈。MySQL 和 Redis
集成测试使用无固定宿主机端口的 Testcontainers；需要 Docker Engine 可用。
RocketMQ 和 AI 服务不属于默认测试基础设施。

在 `courseinsight-server` 目录运行：

```powershell
# 不启动容器：单元测试、MVC/security 切片和 HTTP mock 合约测试
.\mvnw.cmd test '-Dgroups=!integration'

# 仅运行 MySQL/Redis Testcontainers 集成测试
.\mvnw.cmd test '-Dgroups=integration'

# 完整测试，不排除任何测试
.\mvnw.cmd test
```

也可以从仓库根目录使用 PowerShell 脚本：

```powershell
.\scripts\test.ps1 -Suite fast
.\scripts\test.ps1 -Suite integration
.\scripts\test.ps1 -Suite all
```

## 启动本地应用

先确认 Docker Desktop / Docker Engine 可用。从仓库根目录复制 `.env.example`，填写本地数据库、
JWT 等配置后启动。不要提交 `.env`，也不要复用公开 Demo 密钥。

Windows PowerShell：

```powershell
Copy-Item .env.example .env
# 编辑 .env 后再启动
Set-Location courseinsight-server
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

macOS / Linux：

```bash
cp .env.example .env
# 编辑 .env 后再启动
cd courseinsight-server
bash ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

BERT 权重不随仓库分发，放置路径和降级行为见 [AI 服务说明](../courseinsight-ai-service/README.md)。
未配置 LLM 时使用本地模板建议。`local` 不默认创建演示管理员；如需初始化本地管理员，
在 `.env` 中显式设置 `BOOTSTRAP_ADMIN_ENABLED` 及对应账号信息，或使用已有账号。

`local` profile 会读取根目录 `.env` 和 `../compose.yaml`。Spring Boot 仅在服务未运行时
执行 Compose 启动，并等待
Compose healthcheck/readiness 通过。MySQL 和 Redis 使用 Spring Boot 识别的服务连接；
RocketMQ NameServer 与 AI 服务继续使用 `application.yaml` 中的本机映射端口。

在 IntelliJ IDEA 中启动时，将运行配置的 Active profiles 设置为 `local`，效果相同。

## 本地监控

本地监控链路为 Spring Boot Actuator / Micrometer → Prometheus → Grafana。
Prometheus 和 Grafana 位于可选的 Compose `observability` profile 中，不会被普通
`local` profile 强制启动。最短启动流程如下：

1. 在 `courseinsight-server` 目录启动本地应用；Spring Boot 会按现有 `start-only`
   方式启动 MySQL、Redis、RocketMQ 和 AI 服务：

   ```powershell
   .\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
   ```

2. 保持 Java 运行，在另一个终端从仓库根目录启动监控组件：

   ```powershell
   docker compose --profile observability up -d prometheus grafana
   ```

3. 打开以下地址：

   - Spring Boot 健康检查：<http://localhost:8080/actuator/health>
   - Spring Boot Prometheus 指标：<http://localhost:8080/actuator/prometheus>
   - Prometheus：<http://localhost:9090>
   - Prometheus targets：<http://localhost:9090/targets>
   - Grafana：<http://localhost:3000>
   - `CourseInsight Overview` dashboard：
     <http://localhost:3000/d/courseinsight-overview/courseinsight-overview>

Grafana 仅绑定本机回环地址，并为本地开发启用了匿名 Viewer；Prometheus data source
和 dashboard 都会在容器启动时自动 provisioning，无需手工添加。默认抓取间隔为
15 秒，Prometheus 数据保留 7 天。端口可通过 `.env` 中的 `PROMETHEUS_PORT` 和
`GRAFANA_PORT` 覆盖。

可用以下命令做最小验证：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
(Invoke-WebRequest http://localhost:8080/actuator/prometheus).Content |
    Select-String 'jvm_memory_used_bytes|http_server_requests|courseinsight_'
Invoke-RestMethod http://localhost:9090/api/v1/targets
Invoke-RestMethod http://localhost:3000/api/health
```

Dashboard 包含 HTTP 请求速率、p95 延迟、5xx，JVM heap、GC pause，HikariCP
active/max，以及 AI 请求速率/p95/失败、Analysis Task 生命周期事件和 Outbox 发布
结果。自定义 Timer / Counter 只使用固定的 `outcome` tag，publisher 并发 Gauge 不含
用户、任务或其他高基数标签：

- `courseinsight.ai.request`：Timer，区分 `success`、`retryable_failure`、
  `terminal_failure`；
- `courseinsight.analysis.task`：Counter，区分 `success`、`retryable_failure`、
  `terminal_failure`、`lease_recovery`、`dlq_terminal`；
- `courseinsight.outbox.publish`：Counter，区分 `success`、`failure`；
- `courseinsight.outbox.send`：Timer，记录 RocketMQ 发送延迟并区分 `success`、`failure`；
- `courseinsight.outbox.publish.configured.concurrency`、`active`、`peak.active`：
  Gauge，记录 publisher 配置并发、当前并发和进程内观测峰值。

本阶段没有增加任务状态或 Outbox backlog Gauge。现有实现没有可复用的低成本聚合，
为这些 Gauge 在每次 Prometheus 抓取时扫描业务表不适合本地开发基线。

仅停止监控组件且保留监控数据：

```powershell
docker compose --profile observability stop grafana prometheus
```

## 认证接口限流

`POST /api/auth/login` 同时按请求来源和规范化后的用户名限流，默认分别为每分钟
20 次、每 5 分钟 10 次；`POST /api/auth/register` 默认按请求来源每 10 分钟
5 次。阈值和窗口可通过 `.env.example` 中的 `AUTH_*` 配置调整。Redis 不可用时与
已有业务限流保持一致，暂时放行请求并记录警告，避免认证入口因缓存故障整体不可用。

当前请求来源只使用 Servlet 容器提供的 `getRemoteAddr()`，不会信任客户端直接发送的
`X-Forwarded-For` 或 `X-Real-IP`。部署在反向代理之后时，需要先在容器/框架层配置明确
可信的代理和 forwarded-header 处理，否则所有请求可能表现为同一个代理地址。

## 停止本地服务

Compose 生命周期为 `start-only`。停止 Java 不会停止或重建 MySQL、Redis、
RocketMQ 和 AI 服务，下一次启动 Java 会复用已经运行的服务。需要手动停止共享服务时，
在仓库根目录运行：

```powershell
docker compose stop
```

再次启动 Java 会重新启动这些已停止的服务。若明确需要删除容器和网络，可运行
`docker compose down`；不要附加 `--volumes`，除非确实要删除本地数据库、Redis 和
RocketMQ 持久化数据。

## AI 服务测试

在 `courseinsight-ai-service` 目录使用项目 Python 环境运行 `python -m pytest -q`。
依赖分别定义在 `requirements.txt`、`requirements-dev.txt` 和 `requirements-bert.txt`；
Dockerfile 使用 Python 3.12 与 CPU 版 PyTorch。测试接口及降级行为不等于验证真实 BERT 的准确率。

## 技术设计与可靠性边界

### Outbox 发布与重复消息

提交单条评价或上传 CSV 时，评价、分析任务和 Outbox 事件在同一 MySQL 事务中写入。
发布器定时读取到期事件，领取后同步发送到 RocketMQ，再将事件标为 `SENT`。
发送失败记录原因与下次重试时间；超过恢复时间的 `PUBLISHING` 事件可再次领取。

| 失败窗口 | 当前处理方式 |
| --- | --- |
| 业务事务回滚 | 评价、任务与 Outbox 一起回滚，不留下需要发送的事件 |
| 事务提交后，发布器尚未发送 | Outbox 保留待发送意图，后续轮询继续处理 |
| Broker 已接收，但 `SENT` 更新失败或进程退出 | 事件保留为 `PUBLISHING`，超时后可能重发 |
| 旧发布尝试的成功或失败迟到 | 更新必须匹配当前 `publish_token`，拒绝覆盖新的尝试 |

因此语义是 at-least-once，不能据此承诺 exactly-once 或零重复消息。每次 Outbox 领取生成新的
`publish_token`；`SENT / FAILED` 条件更新同时匹配事件 ID、`PUBLISHING` 状态和 token。
token 保护的是数据库中的发布状态，不能撤销已经发出的消息。

本地 Broker 使用单节点和异步刷盘。应用重发机制不能覆盖 Broker 已确认但持久化故障导致的消息丢失；
目前租约恢复只扫描 `PROCESSING`，没有针对长期 `WAITING + SENT` 的自动投递对账。
生产的消息持久化、复制和对账策略需要单独确定，不能仅凭 Outbox 承诺在所有 Broker 故障下都不丢任务。

固定线程池与“已提交但未完成”的发送数量共同限制并发，调度周期还使用原子标记防止重叠。
默认发布并发为 2，该上限仅作用于单个应用实例，不是集群全局限流。

发布调度与租约恢复各使用独立的单线程调度器，避免发布等待整批 MQ 发送时阻塞恢复。
发送仍由原有固定发布池执行，并发上限和防重叠保护保留。

成功 Outbox 默认保留。可通过 `OUTBOX_CLEANUP_ENABLED=true` 显式开启分批清理：
`OUTBOX_CLEANUP_RETENTION_DAYS` 默认 30，`OUTBOX_CLEANUP_BATCH_SIZE` 默认 500（1–1000），
`OUTBOX_CLEANUP_INTERVAL_MS` 和 `OUTBOX_CLEANUP_INITIAL_DELAY_MS` 默认 3600000。
清理仅删除保留期外的 `SENT` 元数据，使用独立调度器，不删除待发送/失败/发布中的事件。
开启前按实际审计需求确定保留期和外部归档；当前未提供自动归档。

源码入口：[评价事务](../courseinsight-server/src/main/java/com/courseinsight/server/service/CommentService.java)、
[批次创建](../courseinsight-server/src/main/java/com/courseinsight/server/service/AnalysisBatchCreationService.java)、
[发布器](../courseinsight-server/src/main/java/com/courseinsight/server/message/AnalysisOutboxPublisher.java)、
[Outbox 条件更新](../courseinsight-server/src/main/java/com/courseinsight/server/mapper/AnalysisOutboxEventMapper.java)。

### 执行所有权、租约与恢复

`current_event_id` 标识任务当前入队代次；`execution_token` 标识一次执行领取。
领取任务通过条件更新完成，结果或失败写入必须匹配任务 ID、当前代次、`PROCESSING` 和执行 token。
成功状态与结果插入位于同一事务，所有权失效时不保存旧执行的结果；成功任务和过时代次消息会被忽略。

租约创建、过期判定和恢复领取使用 MySQL `CURRENT_TIMESTAMP(3)`，避免使用不同 JVM 时钟判断所有权。
默认执行租约 3 分钟，AI HTTP 读取超时 90 秒，恢复器每 30 秒检查过期执行。
恢复时通过条件更新生成新代次，并在同一事务中创建新的 Outbox 事件。

租约到期只表示可以恢复，不表示原 worker 已经停止。目前没有租约续期；旧 HTTP 调用可能继续运行，
并与恢复后的调用重叠。条件写入能防止旧结果覆盖新状态，不能保证 AI 只计算一次。

消息消费者将可重试异常交给 RocketMQ 重投，不可重试异常记录终止失败。
任务的 `FAILED` 可能仍在等待 MQ 重试；只有带终止标记的失败计入批次 `failedCount`，
其余计入 `retryingCount`。批次进度中的完成数量为成功数加终止失败数，100% 不等于全部成功。
批次重试接口只恢复带死信/终止标记且满足条件的失败任务，生成新代次；`requeuedCount=0` 不代表分析成功。

源码入口：[执行服务](../courseinsight-server/src/main/java/com/courseinsight/server/service/AnalysisExecutionService.java)、
[结果事务](../courseinsight-server/src/main/java/com/courseinsight/server/service/AnalysisResultPersistenceService.java)、
[任务条件更新](../courseinsight-server/src/main/java/com/courseinsight/server/mapper/AnalysisTaskMapper.java)、
[租约恢复](../courseinsight-server/src/main/java/com/courseinsight/server/service/AnalysisTaskLeaseRecoveryService.java)、
[批次恢复](../courseinsight-server/src/main/java/com/courseinsight/server/service/AnalysisBatchRecoveryService.java)、
[进度聚合](../courseinsight-server/src/main/java/com/courseinsight/server/mapper/AnalysisBatchProgressMapper.java)。

### 缓存一致性与降级

课程详情 TTL 在 24–30 分钟间随机，空值与统计摘要 TTL 在 4–5 分钟间随机，降低集中失效风险。
详情未命中使用固定数量的锁槽互斥重建并再次检查缓存；本实例的详情回填与提交后失效也互斥。
详情单次查询不再开启数据库事务，缓存命中和锁等待不需要持有事务资源。
相关业务事务提交后删除缓存，避免未提交就失效；Redis 读写异常时业务仍使用 MySQL。
排行榜由 ZSet 与 READY key 共同表示，Lua 脚本先写数据和 TTL，最后发布 READY，支持空排行榜的就绪判断。

事务后失效仍是尽力执行，没有持久化失效记录或可靠重试。失效失败或并发回填可能使缓存暂时保留旧值，
需要后续失效、重新填充或 TTL 收敛，并不提供强一致性。Redis 故障时回源也会增加 MySQL 压力。
JWT 请求仍查询当前用户状态，统计接口仍校验课程归属，因此缓存命中不等于零数据库查询。
本地互斥不提供多实例全局一致性；当前未引入 Caffeine 或用户权限 Redis 缓存。

完整审查、修改依据和验证范围见[后端工程审计](backend-audit.md)。

源码入口：[详情缓存](../courseinsight-server/src/main/java/com/courseinsight/server/cache/CourseDetailCache.java)、
[课程事务后失效](../courseinsight-server/src/main/java/com/courseinsight/server/service/CourseService.java)、
[统计缓存](../courseinsight-server/src/main/java/com/courseinsight/server/cache/CourseAnalyticsCache.java)、
[排行榜发布脚本](../courseinsight-server/src/main/resources/redis/publish-course-popularity-ranking.lua)。

### 认证与对象权限

JWT 使用 HS256，校验签名、有效期和 issuer 后，认证转换器读取 `app_user` 的当前角色与启用状态。
接口规则限制角色，业务服务校验对象归属；管理员可以跨课程管理，教师只能管理本人创建的课程。
评价在响应中匿名展示，但数据库仍保留用户关联，用于个人评价管理与所有权校验。

源码入口：[JWT 配置](../courseinsight-server/src/main/java/com/courseinsight/server/config/JwtConfig.java)、
[接口授权](../courseinsight-server/src/main/java/com/courseinsight/server/config/SecurityConfig.java)、
[当前用户校验](../courseinsight-server/src/main/java/com/courseinsight/server/security/CurrentUserJwtAuthenticationConverter.java)、
[课程归属](../courseinsight-server/src/main/java/com/courseinsight/server/service/CourseManagementAccessService.java)。
完整分析的匿名字段与前端会话边界见[教师分析说明](teacher-comment-analysis.md)和[前端 README](../courseinsight-web/README.md#权限与会话边界)。

这些机制分别由事务、并发领取、旧执行写入、租约恢复和缓存集成测试覆盖。
默认 Java 测试使用真实 MySQL/Redis Testcontainers，但 MQ/AI 主要使用 mock；
Demo smoke 验证真实 RocketMQ 与 AI Stub 路径，不验证真实模型质量、所有故障窗口或多实例容量。

## 运行配置与排错

### 环境与端口

| Profile | 配置来源 | 依赖生命周期 | AI 与账号 |
| --- | --- | --- | --- |
| `demo` | 仓库中的 `demo.env` | 脚本管理 `compose.demo.yaml`，Boot 不自动启停容器 | 8002 Stub，初始化公开演示管理员 |
| `local` | 未提交的 `.env` | Boot Compose `start-only`，Java 退出后依赖保留 | 8001 完整 AI 服务，管理员初始化默认关闭 |
| 测试 | `src/test/resources` | 不启动项目 Compose；集成测试按需启动 Testcontainers | HTTP/MQ 边界主要使用 mock |
| 默认 | 环境变量 | 不自动启动 Compose | 依赖连接与认证配置由运行方提供 |

前端真实 AI 联调可继续使用 `demo` profile：运行 `.\scripts\demo.ps1 start -RealAi`（Bash：`bash scripts/demo.sh start --real-ai`），叠加 `compose.demo-ai.yaml`，仅将 AI 替换为完整 Python 服务并保持 8002 端口，复用 Demo 数据卷。检查、停止时携带相同选项；去掉选项重新 `start` 可恢复 Stub。真实 AI 模式自动加载根 `.env`（若存在），再由 `demo.env` 覆盖演示配置；教学建议默认开启，未配置密钥或调用失败时使用本地模板。配置大模型后，评价及分析证据会发送到该服务，实际调用成功应检查结果 `adviceSource=llm_api`。详见[前端说明](../courseinsight-web/README.md#安装与运行)。

MySQL 由 Flyway 执行仓库现有的 V1–V16 迁移。Demo 与 local 使用独立数据卷，但默认宿主机端口有重合，
切换前应停止对应环境；不要用删除数据卷解决端口冲突。

| 组件 | 默认宿主机端口 | 就绪检查 |
| --- | --- | --- |
| Java API | 8080 | `/actuator/health` 检查 MySQL、Redis；不包含 MQ/AI |
| Vue 开发服务器 | 5173 | 浏览器页面，`/api` 代理到 Java |
| MySQL / Redis | 3307 / 6380 | Compose healthcheck |
| RocketMQ NameServer | 9876 | Compose TCP healthcheck |
| RocketMQ Broker | 10911、10909；指标 5557 | Broker TCP healthcheck；指标不能代替业务验证 |
| AI Stub / 完整 AI | 8002 / 8001 | `/health`；Stub 返回 `deterministic: true` |
| Prometheus / Grafana | 9090 / 3000 | 可选 `observability` profile |

`/api/health` 仅用于存活检查。`scripts/demo.* ready` 同时检查五个依赖容器与 Java Actuator，
`smoke` 再验证实际业务链路。Actuator 仅暴露 `health,prometheus`，`show-details` 关闭，
demo 额外显示组件状态；这些 Actuator 端点当前不要求登录，部署时应限制网络访问。

若要为正在运行的 Demo 添加可选监控，从仓库根目录运行：

```sh
docker compose --env-file demo.env -f compose.yaml --profile observability up -d prometheus grafana
```

停止时也使用相同配置，仅停止监控组件：

```sh
docker compose --env-file demo.env -f compose.yaml --profile observability stop grafana prometheus
```

监控地址和指标含义见[本地监控](#本地监控)。前端静态构建与同源代理示例见[前端部署说明](../courseinsight-web/README.md#部署)，
仓库没有验证生产服务器部署或多实例集群运行。

### Stub 构建与 Docker 网络

Demo 的 `start` 优先复用本地 Stub 镜像。Stub 源码变化后，可从仓库根目录强制重建：

```powershell
.\scripts\demo.ps1 start -Rebuild
```

```bash
bash scripts/demo.sh start --rebuild
```

首次构建的 `failed to fetch oauth token` 表示 Docker 访问 `auth.docker.io` / `registry-1.docker.io` 失败，
应检查 Docker 的网络、代理与 DNS；该错误不来自 Java 或项目登录凭据。
Docker Engine 未启动时应先启动引擎；端口冲突时应先识别并停止冲突服务，再重试。

### 清理 Demo 数据

普通 `stop` 保留数据。以下命令会删除 Demo 专用 MySQL、Redis 和 RocketMQ 数据卷，不可恢复，
只在明确需要清空本地演示数据时使用，不用于完整本地环境：

```powershell
.\scripts\demo.ps1 clean -ConfirmDeleteData
```

```bash
bash scripts/demo.sh clean --confirm-delete-data
```
