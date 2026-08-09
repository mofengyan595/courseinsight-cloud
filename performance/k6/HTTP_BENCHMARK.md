# CourseInsight Cache A/B 与 HTTP capacity benchmark

本阶段只测试现有业务实现，不修改 Controller、Service、SQL、索引、Redis、Hikari、JVM 或线程池。

## 冻结的实验定义

在任何正式结果产生前，固定以下稳定容量 SLO：

- HTTP error rate `< 1%`；
- k6 端到端 query p95 `< 200 ms`；
- achieved RPS `>= target RPS × 99%`；
- `dropped_iterations = 0`。

`max stable RPS` 是持续 30 秒且同时满足上述四项条件的最高 arrival rate；下一档首次失败值单独
记录，不在测试后修改 threshold。

正式 Cache A/B 固定为 100 RPS、30 秒、`constant-arrival-rate`、100 个预分配 VU、1000 个最大
VU。每组使用按课程代码排序的同一批 3000 个课程 ID，`iterationInTest` 保证请求顺序一致，且每个
ID 每次测量最多访问一次；另准备 10 个边界 guard key，覆盖 duration 边界可能调度的最后一次
arrival。每个 endpoint 的 miss/hit 都重复 3 次，并按 `miss → hit` 成对交替。

容量阶梯预先定义为 `100 / 200 / 400 / 800 / 1200 / 1600 / 2000 RPS`，每档 30 秒。每个 cache
state 到达第一档 SLO failure 后停止继续加压。Course detail 是容量主接口；Analytics 只做严格 A/B，
避免把额外接口混入同一个容量数字。

若粗阶梯出现 `400 PASS / 800 FAIL`，保持 SLO 与 30 秒 duration 不变，先补测 600；600 PASS 时再
测 700，600 FAIL 时为该 cache state 补测 500。该自适应二分只缩小 breakpoint 区间，不改变正式
判定标准。

`achieved RPS` 统一按 query 专用 counter `query_requests / 30s` 计算，排除 setup 登录、预热和
graceful-stop 等待；p50/p95/p99/error 也使用 query 专用 metric，不混入登录请求。

## Cache 状态控制

- miss：测量前只 `UNLINK` 本轮将访问的精确 `course:detail:<id>` 或
  `course:analytics:summary:<id>` key，等待固定 20 秒，并用 `EXISTS` 确认 0 个 key 存在；测量中
  每个 ID 只访问一次。
- hit：先删除同一批 key，再通过合法 JWT 和正常 HTTP 查询逐个预热；等待同样的 20 秒，并确认
  全部 key 存在后开始测量。
- 预热本身要求零 HTTP/check failure；若发生极少量传输失败，最多重试 3 轮正常 HTTP GET，最终
  仍必须由 `EXISTS` 确认全部精确 key 存在，预热流量不计入正式时间窗。
- 不 flush Redis、不在每次请求前删 key、不绕过 Spring Security，也不增加 cache bypass。

注意：Course detail 命中时不再查课程表，但 JWT converter 仍会查询 `app_user`；Analytics 在缓存
读取前还会执行课程归属校验。因此 hit 表示现有实现的真实 warm-cache 路径，并不等于零 MySQL。

## 数据准备与执行

凭据仅放在当前 PowerShell 进程环境变量中；context 和结果不保存密码或 JWT。

```powershell
$env:PERF_USERNAME = 'perf_http_p3'
$env:PERF_PASSWORD = '<本地随机密码>'
.\performance\k6\scripts\prepare-http-data.ps1 `
    -DatasetPrefix 'PERF_HTTP_P3' `
    -CourseCount 60010

# 可选 pilot；正式实验参数保持不变
.\performance\k6\scripts\run-http-benchmark.ps1 `
    -Endpoint course-detail -CacheState miss -TargetRps 100 `
    -DurationSeconds 10 -ExperimentPhase pilot -ExperimentId P3_HTTP

.\performance\k6\scripts\run-http-experiment.ps1 `
    -Mode Ab -ExperimentId P3_HTTP
.\performance\k6\scripts\run-http-experiment.ps1 `
    -Mode Capacity -ExperimentId P3_HTTP
.\performance\k6\scripts\aggregate-http-benchmark.ps1 `
    -ExperimentId P3_HTTP
```

数据准备通过公开注册/登录获取 JWT，仅在隔离的本地 MySQL 中把合成用户提升为 `TEACHER`。课程
使用唯一前缀直接批量生成，所有被测和预热请求仍走真实 Spring Security、权限和 cache 实现。

## 结果与清理

每次运行生成 `*-k6.json` 和同名 `*-prometheus.json`。后者包含精确测试窗口的 JVM/CPU/GC、
Tomcat thread、Hikari、HTTP 指标，以及 Redis INFO 与 MySQL GLOBAL STATUS 的前后差值。

```powershell
.\performance\k6\scripts\cleanup-data.ps1 `
    -ContextPath .\performance\results\phase-3-runtime-context.json `
    -WhatIf
.\performance\k6\scripts\cleanup-data.ps1 `
    -ContextPath .\performance\results\phase-3-runtime-context.json `
    -Confirm:$false
```

清理脚本只删除 context 中前缀对应的数据库行、合成用户和精确 cache key，Redis key 按 500 个一批
异步 `UNLINK`；不会 flush Redis、删除共享卷或影响其他数据集。
