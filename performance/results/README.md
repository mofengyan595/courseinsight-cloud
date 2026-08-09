# Performance results

该目录保存小型、可比较的 CourseInsight 性能结果：k6 summary、对应时间窗的 Prometheus 查询
结果、batch outcome 和人工基线报告。不要在此保存完整应用日志、Prometheus TSDB 或其他巨大
原始输出。

- Phase 1：`2026-08-09-phase-1-baseline.*`
- Phase 2：`2026-08-09-phase-2-capacity-P2_77bcd87.*`
- Phase 3：`2026-08-09-phase-3-http-cache-P3_HTTP.*`，严格 Cache A/B 与 open-model HTTP capacity；
  对应单次文件名包含 `http-P3_HTTP`，不会覆盖前两阶段。
