# CourseInsight 第一阶段性能基线

本目录使用官方 k6 标准功能建立 CourseInsight 的可重复性能基线。固定运行镜像为
`grafana/k6:1.3.0`，本轮验证的镜像摘要为
`sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d`。

## 目录

- `scenarios/`：Course detail、Course analytics、200 行异步 batch 场景。
- `lib/`：JWT 登录、响应解析和结构化 summary。
- `data/batch-200.csv`：固定的 200 行合成评价数据。
- `scripts/prepare-data.ps1`：创建隔离的合成教师和 50 门课程。
- `scripts/run.ps1`：用 Docker 运行单场景并采集 Prometheus 指标。
- `scripts/cleanup-data.ps1`：按数据集前缀清理本测试创建的数据。
- `../results/`：k6、Prometheus、batch outcome 和阶段报告。

第三阶段严格 Cache A/B 和 arrival-rate 容量实验见 [HTTP_BENCHMARK.md](HTTP_BENCHMARK.md)。
第四阶段 AI Stub 异步链路压测见 [PHASE4.md](PHASE4.md)，第五阶段 RocketMQ 原生积压与
Outbox 发布侧容量实验见 [PHASE5.md](PHASE5.md)。

## 前置条件

1. Docker Desktop 可用，仓库根目录已有本地 `.env`，但不要把其中内容传给 k6。
2. 从 `courseinsight-server` 启动 local application：

   ```powershell
   .\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
   ```

3. 从仓库根目录启动监控组件：

   ```powershell
   docker compose --profile observability up -d prometheus grafana
   ```

4. 确认以下地址可用：

   - `http://localhost:8080/actuator/health`
   - `http://localhost:9090/targets`
   - `http://localhost:3000/d/courseinsight-overview/courseinsight-overview`

## 测试数据与 JWT

只使用独立合成账号，不要使用真实个人账号。密码只通过当前 PowerShell 进程环境变量传递，
不会写入脚本、runtime context 或结果文件。初始化脚本先通过公开注册 API 创建用户，再在本地测试
MySQL 中把该隔离用户设为 `TEACHER`；所有被测请求仍通过 `/api/auth/login` 获取 JWT，并由真实
Spring Security 和对象归属校验处理。

```powershell
$env:PERF_USERNAME = 'perf_p1_local'
$env:PERF_PASSWORD = '<仅用于本地压测的随机密码>'
.\performance\k6\scripts\prepare-data.ps1 -DatasetPrefix 'PERF_P1'
```

生成的 `performance/results/runtime-context.json` 只含用户名称、数据集前缀和课程 ID，不含密码或
JWT。脚本可重复执行；同一前缀的课程会复用并重置为固定内容。

## 场景和模型

### Course detail

Cold cache 使用 1 VU 对 50 个不同课程各请求一次。运行器只在测试开始前删除这 50 个课程对应的
`course:detail:<id>` key，不会清空 Redis，也不会在每次请求前删 key。Warm cache 先预热全部
课程，再运行 `0→2 VU / 5s`、`2→4 VU / 15s`、`4→8 VU / 10s`、`8→0 VU / 5s`，每次请求
间隔 0.1 秒。

```powershell
.\performance\k6\scripts\run.ps1 -Scenario course-detail -CacheState cold
.\performance\k6\scripts\run.ps1 -Scenario course-detail -CacheState warm
```

固定 threshold：错误率 `<1%`；cold p95 `<750ms`；warm p95 `<250ms`。

### Course analytics

Cache miss 同样使用 1 VU 对 50 个不同课程各查询一次，并且只在运行前删除本数据集对应的
`course:analytics:summary:<id>` key。Warm cache 先通过合法 JWT 预热，再使用与 detail 相同的
四阶段 VU 模型。

```powershell
.\performance\k6\scripts\run.ps1 -Scenario course-analytics -CacheState miss
.\performance\k6\scripts\run.ps1 -Scenario course-analytics -CacheState warm
```

固定 threshold：错误率 `<1%`；miss p95 `<1000ms`；warm p95 `<350ms`。

### 200-row batch

Batch 使用 1 VU、1 次上传；随后每秒轮询进度，直到 200 个任务全部为 terminal state，或达到
20 分钟 timeout。它不使用大量 VU 模拟异步吞吐，也不会改变 consumer concurrency。

```powershell
.\performance\k6\scripts\run.ps1 -Scenario batch-200
```

固定 threshold：创建/轮询错误率 `<1%`；创建 p95 `<5s`；总完成时间 `<20min`。单样本的 p95
等于该样本本身，保留这一写法是为了让后续相同场景能够自动比较，而不是为了让首次结果变绿。

## 结果格式

每次运行写入两个或三个小型 JSON：

- `*-k6.json`：时间、Git commit、场景、cache state、k6 镜像、VU 模型、consumer concurrency、
  AI backend 状态、HTTP 和自定义指标。
- `*-prometheus.json`：同一时间窗的 JVM heap/GC/thread、Hikari active/max、HTTP p95/5xx、
  AI request count/p95、analysis task 和 Outbox counter increase。
- `*-outcome.json`：仅 batch 产生，记录 batch ID、创建耗时、terminal 总耗时和成功/失败数。

Prometheus 抓取周期为 15 秒；运行器在 k6 结束后等待一次额外抓取再执行查询。原始服务日志和
Prometheus 全量数据不会复制到结果目录。

## 清理

先确认 batch 已经结束，再预览并清理本数据集。脚本只删除 runtime context 指定前缀的课程及其
关联分析数据、对应合成用户和精确 Redis cache key；不会删除卷或清空共享数据库。

```powershell
.\performance\k6\scripts\cleanup-data.ps1 -WhatIf
.\performance\k6\scripts\cleanup-data.ps1 -Confirm
```

保留数据可以让下一次在同一数据规模上直接复测；需要完全重新初始化时才执行清理。
