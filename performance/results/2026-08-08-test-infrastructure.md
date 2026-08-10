# CourseInsight Test Infrastructure Refactor Measurement

该记录整理测试基础设施重构时已经实际测量的命令输出，用于说明开发反馈周期变化。它不是业务 API、
异步链路或 AI 推理性能 benchmark。

## Measurement scope

- 环境：同一台本地 Windows 开发机；Java 17 / Maven；完整 Java 测试。
- 变更：commit `f8308ac`（Testcontainers 分层、外部依赖隔离、测试 profile 与统一测试入口）。
- wall-clock 为命令外部计时；Maven time 为 Maven 自身输出。
- 前后测试数量从 198 增为 203，因此不是严格同一测试集合 A/B；重构记录确认未删除、禁用或弱化测试。

## Before / after

| 阶段 | 结果 | Wall-clock | Maven time |
| --- | ---: | ---: | ---: |
| Before | 198/198 passed | 214.378 s | 3:30 |
| After | 203/203 passed | 67.426 s | 1:04 |

按 wall-clock 计算，反馈周期缩短约 **68.5%**。重构后的附加分层测量为：

- fast：172/172 passed，42.877 s；
- integration：31/31 passed，69.928 s（独立运行，包含容器启动成本）。

收益来自：只在 MySQL / Redis 语义确有必要时使用 Testcontainers、普通 AI HTTP / RocketMQ / cache
边界使用 mock，以及减少无关 Spring Boot 上下文和外部服务启动。它不代表业务运行性能提升。

## Current HEAD cross-check

2026-08-10 在 `main` HEAD `6ab7d15` 从仓库根目录实际运行：

```powershell
.\scripts\test.ps1 -Suite all
```

结果为 266/266 passed，0 failure、0 error、0 skipped；Maven total time 1:33。当前数量用于说明
测试能力继续增长，不与 2026-08-08 的 198 → 203 测量拼接成新的耗时 A/B。
