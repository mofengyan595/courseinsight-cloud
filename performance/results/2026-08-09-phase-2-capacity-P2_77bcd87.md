# CourseInsight 第二阶段容量实验

- Experiment ID: P2_77bcd87
- Git HEAD: 77bcd87bf0e5ddb0302233d0c441eb0dc098197a
- Repetitions: 3 per concurrency
- AI backend: bert; BERT ready = True; LLM configured = True
- AI phase timing: structured FastAPI logs; no review text, prompt, or credential is logged

## 1. Test setup

- 主机：Windows 11 Home Chinese 10.0.26200，AMD Ryzen 7 5800H（8C/16T），可见内存 13.87 GiB。
- Docker Desktop Engine 29.6.2：16 CPU，6.70 GiB 内存；整个实验未调整 Docker、JVM、Hikari、MySQL、Redis 或 RocketMQ 配置。
- Java 17.0.20 / Spring Boot 3.5.16；k6 使用官方 `grafana/k6:1.3.0`，digest 为 `sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d`。
- AI backend 全程为 BERT CPU + 已配置的同一 LLM；AI 容器在 1/2/4 三组之间没有重启。正式实验前执行两次无 LLM 的 BERT 预热，第二次为 81.085 ms。
- 固定使用 `performance/k6/data/batch-200.csv`；consumer=1、2、4 各 3 次，每次只创建一个 200-row batch，batch terminal 后等待最终 Prometheus scrape，再冷却 30 秒。
- 每次只重启 Java 并设置对应的 `ROCKETMQ_ANALYSIS_CONSUME_THREAD_NUMBER`。数据库实测处理中任务数分别达到 1、2、4，确认配置实际生效。
- Prometheus target 全程为 UP，Grafana health 为 OK。所有 9 个 batch 均独立完成，无批次重叠。

FastAPI 原来没有分段指标。本轮只增加 `predict_sentiment` 与 `generate_review_advice` 的结构化耗时日志，不记录评论、提示词或凭据，也没有改变模型、fallback 或调用流程。容器内 AI 测试为 5/5 通过。标准镜像重建曾因下载依赖时的临时 DNS 失败中止，因此实际实验将这三处观测源码同步到原有 AI 容器后重启；底层依赖、模型和 LLM 配置仍与第一阶段相同。

## 2. Aggregate results

下表的 completion p95 使用 3 个 run 的线性插值；AI p95/p99 是每个 run 的 Micrometer histogram 值再取三次平均；JVM heap 与 Hikari 是三次运行中的观测峰值。

| Metric | 1 | 2 | 4 |
| --- | ---: | ---: | ---: |
| Batch completion mean (s) | 379.744 | 227.969 | 104.168 |
| Batch completion p95/min/max (s) | 430.095/347.719/438.679 | 260.276/207.665/265.815 | 106.487/101.357/106.716 |
| Completion CV | 0.1346 | 0.1439 | 0.0258 |
| Throughput mean (tasks/s) | 0.5326 | 0.8886 | 1.9208 |
| AI avg mean (ms) | 1855.578 | 2214.076 | 1961.134 |
| AI p95 mean (ms) | 3093.304 | 4451.200 | 3577.193 |
| AI p99 mean (ms) | 4056.358 | 6251.564 | 4632.997 |
| Task retry | 0 | 0 | 0 |
| Terminal failure | 0 | 0 | 0 |
| HTTP 5xx | 0 | 0 | 0 |
| JVM heap peak (MiB) | 97.862 | 78.419 | 79.049 |
| Hikari active/max | 1/10 | 1/10 | 1/10 |

三次 completion CV 分别为 13.46%、14.39%、2.58%；AI p95 CV 分别为 10.93%、32.94%、3.09%。consumer=2 的波动主要来自 run 2 的 LLM tail latency，不应把该单次异常当成 2 的固定性能。

### AI 分段观测

| Metric | 1 | 2 | 4 |
| --- | ---: | ---: | ---: |
| Sentiment/BERT avg mean (ms) | 79.381 | 80.961 | 105.427 |
| Sentiment/BERT p95 mean (ms) | 94.725 | 96.514 | 168.755 |
| LLM advice avg mean (ms) | 1766.977 | 2125.399 | 1847.784 |
| LLM advice p95 mean (ms) | 2983.113 | 4204.895 | 3455.050 |
| LLM share of full AI avg | 95.23% | 95.99% | 94.22% |

每个 run 都采集到 200 条 sentiment 和 200 条 advice 计时，共 1,800+1,800 条。每个 run 的结果源一致：193 条 `bert`、7 条 `bert+rule`，200 条 advice 全部为 `llm_api`，没有发生 local fallback。

## 3. Scaling efficiency

| concurrency | completion speedup | completion reduction | throughput improvement | AI p95 degradation | retry increase | failure increase |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1.000x | 0.00% | 0.00% | 0.00% | 0 | 0 |
| 2 | 1.666x | 39.97% | 66.84% | 43.90% | 0 | 0 |
| 4 | 3.646x | 72.57% | 260.62% | 15.64% | 0 | 0 |

- 1 → 2：完成时间降低 39.97%，吞吐提高 66.84%，speedup 1.666x；AI p95 增加 43.90%，但主要由 consumer=2 run 2 的外部 LLM 波动造成。
- 2 → 4：完成时间再降低 54.31%，吞吐再提高 116.15%，speedup 2.188x；AI p95 反而降低 19.64%。这是不同时间窗口的 LLM 波动带来的“超线性”现象，不能解释为 Java/RocketMQ 本身超线性扩展。
- 1 → 4：完成时间降低 72.57%，吞吐提高 260.62%，speedup 3.646x；相对理想 4x 的 scaling efficiency 为 91.14%，AI p95 只比 consumer=1 高 15.64%。

## 4. Individual runs

| c | run | batch | create (ms) | completion (s) | AI avg/p95/p99 (ms) | success/failure | task/outbox retry | heap peak (MiB) | Hikari | HTTP 5xx |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 37 | 1780.611 | 438.679 | 2140.222/3453.870/3698.444 | 200/0 | 0/0 | 90.504 | 1/10 | 0 |
| 1 | 2 | 38 | 1457.528 | 347.719 | 1700.254/2783.775/3459.835 | 200/0 | 0/0 | 97.862 | 0/10 | 0 |
| 1 | 3 | 39 | 1548.031 | 352.834 | 1726.258/3042.268/5010.795 | 200/0 | 0/0 | 97.303 | 0/10 | 0 |
| 2 | 1 | 40 | 1629.918 | 210.427 | 2023.604/3459.835/4294.967 | 200/0 | 0/0 | 73.597 | 0/10 | 0 |
| 2 | 2 | 41 | 1550.246 | 265.815 | 2597.498/6135.668/8017.272 | 200/0 | 0/0 | 78.419 | 0/10 | 0 |
| 2 | 3 | 42 | 1441.597 | 207.665 | 2021.125/3758.096/6442.451 | 200/0 | 0/0 | 74.478 | 1/10 | 0 |
| 4 | 1 | 43 | 1632.384 | 106.716 | 1968.564/3551.608/4116.010 | 200/0 | 0/0 | 72.399 | 1/10 | 0 |
| 4 | 2 | 44 | 1484.731 | 101.357 | 1912.753/3481.527/4772.186 | 200/0 | 0/0 | 75.862 | 0/10 | 0 |
| 4 | 3 | 45 | 1595.323 | 104.430 | 2002.086/3698.444/5010.795 | 200/0 | 0/0 | 79.049 | 0/10 | 0 |

单次表中的 Hikari `0/10` 表示 15 秒 scrape 间隔没有捕获到极短的 active 窗口，不代表没有数据库访问；每组至少一次观测到 1，三组聚合峰值均为 1/10。三组累计 JVM GC pause 分别为 0.052 s、0.078 s、0.059 s，live thread 峰值为 120、119、121，均未表现为容量限制。

## 5. Conclusion

### A. 当前推荐 consumer concurrency

推荐 **4**。

依据是 consumer=4 在三次运行中完成时间为 101.357–106.716 秒，均值 104.168 秒，吞吐均值 1.9208 tasks/s；相对 consumer=1 达到 3.646x speedup，同时三次都是 200/200 成功，task retry、Outbox retry、terminal failure、AI failure 和 HTTP 5xx 全部为 0。consumer=4 的 completion CV 只有 2.58%，AI p95 CV 只有 3.09%，稳定性也优于本轮的 1 和 2。

### B. 是否出现饱和

在本轮测试上限 4 以内**没有观察到饱和**。concurrency 从 2 增至 4 后吞吐仍提升 116.15%，且没有伴随 timeout、429、5xx、retry 或 terminal failure。不能据此推断 6/8 仍会线性扩展，只能说明 4 还处于可用扩展区间。

### C. 当前真正的容量上限来源

1. **外部 LLM API latency / variability**：advice 阶段占完整 AI 平均耗时的 94.22%–95.99%，consumer=2 的慢样本也由 advice p95 升至 5.701 秒解释。当前容量主要受外部等待时间和其波动控制。
2. **BERT CPU contention（次要、尚未饱和）**：consumer=4 的 sentiment avg 从 consumer=1 的 79.381 ms 增至 105.427 ms，p95 从 94.725 ms 增至 168.755 ms，说明并发推理已经出现 CPU 竞争；但其绝对耗时仍远小于 LLM advice。
3. **Java / MySQL / RocketMQ 不是当前近端瓶颈**：Hikari 峰值仅 1/10，heap 峰值不超过 97.862 MiB，GC pause 很小；1,800 个任务和 Outbox 事件全部成功，无 retry/failure，HTTP 5xx 为 0。

### D. 下一步实验

1. 用相同 fixture 和分段计时比较 consumer=4/6/8，各至少 3 次；一旦出现 429、timeout、retry 或 AI p95 快速恶化就停止扩压，以定位首次饱和点。
2. 在两个不同时段各重复 consumer=4 五次，并增加 LLM 单次 attempt 的 status/latency 观测，区分提供方时段波动与本机 BERT 竞争。

## 6. Result integrity

- 9/9 batch 均 terminal；总计 1,800 success、0 terminal failure、0 task retry、0 Outbox retry。
- 9 个 k6 JSON、9 个 Prometheus JSON、9 个 outcome JSON 均已保存；聚合 JSON 保留每次原始指标、分段统计、数据库来源、环境和 scaling 结果。
- 第一阶段 baseline 文件未覆盖。
