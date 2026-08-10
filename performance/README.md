# CourseInsight Performance

该目录保存 CourseInsight 的可重复本地性能实验。结论只在报告写明的 Git HEAD、单机环境、
workload、重复次数和依赖配置下成立，不外推为生产或分布式集群容量。

## 目录

- [`k6/`](k6/README.md)：场景、固定数据、运行/聚合/清理脚本与 Phase 3–7 实验说明；
- [`ai-stub/`](ai-stub/README.md)：符合真实 HTTP contract 的 deterministic Stub，不加载 BERT、不调用 LLM；
- [`results/`](results/README.md)：阶段报告、机器可读汇总，以及对应 k6 / Prometheus / RocketMQ / backlog / outcome 文件。

## 方法

```text
baseline
→ controlled workload
→ Prometheus / RocketMQ native metrics
→ bottleneck localization
→ single-variable experiment
→ optimization
→ same-workload retest
```

异步链路的 Phase 4–7 依次定位 Outbox backlog、MQ inflight、consumer capacity 和 Outbox
publisher 供给边界。`WAITING + PROCESSING` 只作为端到端数据库状态 proxy；存在 RocketMQ 原生
指标时，报告会单独区分 ready、inflight 与 lag。

## 代表性结果

- [Phase 3 Cache A/B](results/2026-08-09-phase-3-http-cache-P3_HTTP.md)：
  本机 100 RPS × 30s、各 3 次，course detail p95 -25.34%、p99 -29.16%、MySQL SELECT -49.68%。
- [Phase 7 Bounded Publisher](results/2026-08-10-phase-7-outbox-publisher-P7_OUTBOX.md)：
  本机 1000 tasks、100 ms Stub、各 3 次，throughput +35.18%、completion -26.25%。
- [Test Infrastructure](results/2026-08-08-test-infrastructure.md)：
  历史完整 Java 测试 wall-clock 214.378s → 67.426s（约 -68.5%）；不属于业务性能。

Phase 3 的 cache hit 仍包含 JWT current-user 数据库查询；Phase 4–7 的吞吐使用 AI Stub，不能写成
真实 BERT / LLM 性能。完整边界与项目结论见仓库根目录 [README](../README.md)。
