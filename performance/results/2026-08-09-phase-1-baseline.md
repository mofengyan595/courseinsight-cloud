# CourseInsight 第一阶段性能基线

## 1. Test setup

- 时间：2026-08-09 16:29–16:40（Asia/Shanghai）。
- Git HEAD：`b308586f8fd889b65be5965feff059eddd380bc4`；测试前工作区干净。
- 主机：Windows 11 Home 64-bit build 26200，AMD Ryzen 7 5800H（8C/16T），可见内存约 13.87 GiB。
- Docker Desktop Engine 29.6.2：16 CPU、约 6.70 GiB memory；Java 17.0.20；Spring Boot 3.5.16。
- k6：官方镜像 `grafana/k6:1.3.0`，digest `sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d`。
- AI：FastAPI `auto -> bert`，BERT ready、CPU；LLM 已配置。200 行结果中 193 行是 `bert + llm_api`，7 行是 `bert+rule + llm_api`，没有走本地 advice fallback。
- `ROCKETMQ_ANALYSIS_CONSUME_THREAD_NUMBER=1`，本轮未修改。
- Prometheus target 实测为 UP；Grafana 13.1.0 的 `CourseInsight Overview` 已实际打开并检查，HTTP、JVM、Hikari、AI、Task、Outbox 面板均正常渲染并有本轮曲线。

## 2. Course detail baseline

| 状态 | VU / duration | 目标请求 | RPS | p50 | p95 | p99 | 错误率 |
|---|---|---:|---:|---:|---:|---:|---:|
| cold | 1 VU；50 个唯一课程各 1 次 | 50 | 47.56 | 17.59 ms | 26.70 ms | 31.37 ms | 0% |
| warm | 0→2/5s，2→4/15s，4→8/10s，8→0/5s；0.1s think time | 1063 | 29.60 | 9.81 ms | 11.89 ms | 12.99 ms | 0% |

Cold 运行只在开始前删除这 50 个课程的精确 `course:detail:<id>` key 一次；warm 运行先预热同一组课程。warm p95 比 cold 低约 55.5%。两组固定 threshold 均通过：错误率 `<1%`，cold p95 `<750ms`，warm p95 `<250ms`。RPS 受两种不同负载模型影响，不能把 cold 的瞬时 RPS 与 warm 的带 think time RPS直接当作容量高低比较。

## 3. Analytics baseline

| 状态 | VU / duration | 目标请求 | RPS | p50 | p95 | p99 | 错误率 |
|---|---|---:|---:|---:|---:|---:|---:|
| cache miss | 1 VU；50 个唯一课程各 1 次 | 50 | 52.46 | 14.67 ms | 22.18 ms | 40.93 ms | 0% |
| warm | 0→2/5s，2→4/15s，4→8/10s，8→0/5s；0.1s think time | 1042 | 29.16 | 11.94 ms | 15.94 ms | 18.07 ms | 0% |

Miss 运行只在开始前删除 50 个精确 `course:analytics:summary:<id>` key 一次；warm 运行预热后再进入四阶段负载。warm p95 比 miss 低约 28.2%，p99 低约 55.8%。固定 threshold 均通过：错误率 `<1%`，miss p95 `<1000ms`，warm p95 `<350ms`。即使命中 summary cache，服务层对象归属校验仍会访问课程数据，因此不能把 warm 结果解释为完全不经过 MySQL。

## 4. 200-row batch baseline

| 指标 | 实测值 |
|---|---:|
| 模型 | 1 VU、1 次 200-row 上传、每 1s 轮询 |
| batch create HTTP latency | 1551.82 ms |
| k6 上传开始至全部 terminal | 375.10 s |
| DB `created_at` 至最后完成 | 374.112 s |
| success / terminal failure | 200 / 0 |
| task retry / Outbox retry | 0 / 0 |
| AI request count / rate | 200 / 0.533 req/s |
| AI average / p95 | 1.823 s / 3.162 s |
| AI timer 累计 | 364.576 s（端到端的 97.2%） |
| Outbox publish success / failure | 200 / 0 |
| HTTP error | 0% |

k6 共发出 370 个 HTTP 请求：1 次登录、1 次创建、368 次进度轮询。进度轮询 p95 为 17.38 ms；创建和轮询 threshold 全部通过。最终 API 再次确认 `COMPLETED`、completion 100%、waiting/processing/retrying 均为 0；MySQL 也确认 200 个任务全部 SUCCESS，task/Outbox retry 总数均为 0。Prometheus 精确 counter delta 为 200 次 AI success、200 次 Task success、200 次 Outbox success，所有 failure/recovery/DLQ 增量为 0。

## 5. Resource observations

- Batch 时间窗 JVM heap 峰值 97,875,040 bytes（约 93.34 MiB），配置 max 3,722,444,798 bytes（约 3.47 GiB）；内存不是当前瓶颈。
- Batch GC pause 增量约 23 ms，live threads 峰值 118；没有 GC 或线程耗尽迹象。
- Hikari active 峰值 1 / max 10。普通 miss 查询太短，15 秒 Prometheus scrape 多次落在 active=0 的空闲点；这只是采样分辨率限制。Batch 已捕捉到 1/10，离池上限很远。
- Batch 全路由 Prometheus HTTP p95 约 109.6 ms；k6 显示进度轮询 p95 17.38 ms，唯一创建请求 1.552 s。所有 k6 `http_req_failed=0`，Prometheus 未观察到 5xx 增量。
- AI 请求累计 364.576 s，占端到端 375.1 s 的 97.2%；Grafana 中 AI rate 峰值约 0.55 req/s、p95 约 3.2 s，与结构化查询一致。

## 6. Bottleneck ranking

1. **完整 AI 请求路径**：BERT CPU 推理与配置的 `llm_api` advice 在同一次 FastAPI 请求内，Java timer 无法继续拆分，但它们合计占 batch 时间的 97.2%，是明确第一瓶颈。
2. **同步 200 行创建事务**：单次 HTTP 创建耗时 1.552 s，明显高于普通 GET p95（11.9–22.2 ms）；它影响上传响应，但只占总流程很小一部分。
3. **剩余异步协调与持久化**：端到端减去 AI timer 后约 10.5 s，包含 Outbox 调度、MQ、结果持久化和轮询分辨率。Outbox 200/200 成功且无 retry，当前数据不足以把这部分继续归因到某一个组件。

JVM heap、GC、Hikari 和 HTTP GET 均未接近资源上限，不属于本轮主要瓶颈。

## 7. Recommended next experiment

1. 在 consumer=1、同一 fixture 和同一机器上重复单 batch 3 次，先得到完成时间与 AI p95 的方差，避免用单样本决定调优方向。
2. 用同一 200-row batch 比较 consumer concurrency `1 / 2 / 4`，同时固定 LLM backend，观察完成时间、AI p95、429/5xx/retry 与 CPU；这是当前最有价值的容量实验。
3. 增加一次不改变业务路径的 AI 分段观测或 AI-only 对照（BERT 与 `llm_api` advice 分别计时），区分 1.823 s 平均 AI latency 中 CPU 推理和外部建议生成各自占比。

## 8. Files changed

- k6 脚本：`performance/k6/scenarios/*.js`、`performance/k6/lib/*.js`。
- 数据与运行器：`performance/k6/data/batch-200.csv`、`performance/k6/scripts/*.ps1`、`performance/k6/README.md`。
- 结构化结果：5 个 `*-k6.json`、5 个 `*-prometheus.json`、1 个 batch outcome、runtime context、本报告和汇总 JSON，均位于 `performance/results/`。

本轮未修改任何业务实现、consumer/Hikari/JVM/Redis/SQL 参数，也未执行 branch、worktree、commit、push、stash 或 reset。
