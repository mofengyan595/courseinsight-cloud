# Performance results

该目录保存小型、可比较的 CourseInsight 性能结果：k6 summary、对应时间窗的 Prometheus 查询
结果、batch outcome 和人工基线报告。不要在此保存完整应用日志、Prometheus TSDB 或其他巨大
原始输出。

- Phase 1：`2026-08-09-phase-1-baseline.*`
- Phase 2：`2026-08-09-phase-2-capacity-P2_77bcd87.*`
- Phase 3：`2026-08-09-phase-3-http-cache-P3_HTTP.*`，严格 Cache A/B 与 open-model HTTP capacity；
  对应单次文件名包含 `http-P3_HTTP`，不会覆盖前两阶段。
- Phase 4：`2026-08-09-phase-4-async-stub-P4_STUB100.*`，固定 100 ms AI Stub 的异步链路基线。
- Phase 5：`2026-08-09-phase-5-rocketmq-outbox-P5_OUTBOX.*`，RocketMQ 原生 inflight 与 Outbox backlog。
- Phase 6：`2026-08-10-phase-6-consumer-capacity-P6_CONSUMER.*`，固定发布能力后的 consumer scaling。
- Phase 7：`2026-08-10-phase-7-outbox-publisher-P7_OUTBOX.*`，bounded publisher before / after。
- Test infrastructure：`2026-08-08-test-infrastructure.md`，测试反馈周期记录，不属于业务性能。

## 可用于项目展示的结果

| 主题 | 测试条件 | 结果 | 报告 |
| --- | --- | --- | --- |
| Redis course detail A/B | 本机，100 RPS × 30s，miss/hit 各 3 次 | p95 -25.34%，p99 -29.16%，MySQL SELECT -49.68% | [Phase 3](2026-08-09-phase-3-http-cache-P3_HTTP.md) |
| Bounded Outbox publisher | 本机，1000 tasks，100 ms deterministic Stub，consumer 8，batch 100，各 3 次 | throughput +35.18%，completion -26.25% | [Phase 7](2026-08-10-phase-7-outbox-publisher-P7_OUTBOX.md) |
| Test infrastructure | 本机，历史完整 Java 测试 before / after | 214.378s → 67.426s，约 -68.5% | [Measurement](2026-08-08-test-infrastructure.md) |

Phase 4–7 使用 `performance/ai-stub` 的 deterministic HTTP Stub，不加载 BERT、不调用 LLM。
这些结果不能表述为真实 AI 性能、生产容量或分布式集群容量。Phase 3 的 Redis hit 也不是零数据库
路径：JWT current-user 校验仍查询 `app_user`。

## 方法与证据链

```text
baseline
→ controlled workload
→ Prometheus / RocketMQ native metrics
→ bottleneck localization
→ single-variable experiment
→ optimization
→ same-workload retest
```

异步链路的判断顺序为 Outbox backlog → MQ inflight → consumer capacity → Outbox publisher。
每个阶段报告都记录 Git commit、固定 workload、重复次数、指标来源、排除项和可用于简历的边界；
机器可读汇总通过 `runs[].files` 指向对应 k6、Prometheus、RocketMQ、backlog 与 outcome 原始文件。
