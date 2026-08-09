# CourseInsight 第二阶段容量实验

本实验回答同一单机环境、同一 AI backend 下，`ROCKETMQ_ANALYSIS_CONSUME_THREAD_NUMBER=1/2/4` 对单个 200-row batch 的影响。每个值默认重复 3 次；不并发上传多个 batch。

## 固定条件

- 使用 `data/batch-200.csv` 和 `prepare-data.ps1` 生成的隔离教师/课程。
- AI 容器、BERT 模型、LLM 配置、Docker 资源、JVM、MySQL、Redis、RocketMQ 和 Hikari 配置保持不变。
- 每个 batch 完全 terminal 后才创建下一个；run 之间默认冷却 30 秒。
- k6 threshold 沿用第一阶段，不因某次 baseline 失败而放宽。
- 凭据只通过当前 PowerShell 进程的 `PERF_USERNAME`、`PERF_PASSWORD` 传入，不写入结果。

## AI 分段观测

FastAPI 在以下两个既有边界输出结构化 INFO 日志：

- `sentiment`：`predict_sentiment(...)`，即 BERT/规则合并阶段；
- `advice`：`generate_review_advice(...)`，即完整 LLM advice/fallback 阶段。

日志只包含 phase、task ID、耗时、source 和 device，不包含评论、提示词、JWT、密码或 LLM secret。模型逻辑、fallback 和请求流程不变。修改 AI 源码后按标准方式重建并启动：

```powershell
docker compose build ai-service
docker compose up -d ai-service
```

## 执行

从仓库根目录准备数据：

```powershell
$env:PERF_USERNAME = 'perf_p2_local'
$env:PERF_PASSWORD = '<仅用于本地实验的随机密码>'
.\performance\k6\scripts\prepare-data.ps1 -DatasetPrefix 'PERF_P2'
docker compose --profile observability up -d prometheus grafana
```

先在一个 PowerShell 窗口启动指定 consumer 的 Java 服务：

```powershell
$env:ROCKETMQ_ANALYSIS_CONSUME_THREAD_NUMBER = '1'
Set-Location .\courseinsight-server
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

在另一个位于仓库根目录的 PowerShell 窗口运行 3 次独立 batch：

```powershell
$env:PERF_USERNAME = 'perf_p2_local'
$env:PERF_PASSWORD = '<与准备数据时相同>'
.\performance\k6\scripts\run-capacity.ps1 `
    -ConsumerConcurrency 1 `
    -ExperimentId 'P2_LOCAL' `
    -Runs 3 `
    -CooldownSeconds 30
```

确认 Java 进程退出后，只修改环境变量为 `2`、`4`，分别重新启动 Java 并运行同一命令。`run-capacity.ps1` 会在每次上传前确认系统中不存在活动 analysis task 或待发布 Outbox；传入的 `ConsumerConcurrency` 同时写入结果元数据，必须与 Java 启动值一致。

三组全部完成后聚合：

```powershell
.\performance\k6\scripts\aggregate-capacity.ps1 `
    -ExperimentId 'P2_LOCAL' `
    -ExpectedRunsPerConcurrency 3 `
    -CooldownSeconds 30
```

聚合脚本将：

- 读取 9 组 k6、Prometheus 和 outcome JSON；
- 从 MySQL 核对 terminal、retry、Outbox 和实际 AI result source；
- 用 task ID 关联 FastAPI 分段计时日志；
- 计算 mean、sample standard deviation、CV、p50/p95/p99、speedup 和吞吐提升；
- 生成机器可读 JSON 与 Markdown 表格。

如果包装脚本中断但 k6/batch 已完成，不要盲目重跑；先确认 batch terminal、原始 k6/Prometheus 文件和数据库记录，再决定是否恢复 outcome。

## 结果与清理

单次文件命名包含 experiment、consumer 和 run，例如：

```text
*-batch-200-P2_LOCAL-c4-r3-k6.json
*-batch-200-P2_LOCAL-c4-r3-prometheus.json
*-batch-200-P2_LOCAL-c4-r3-outcome.json
```

本轮实测报告见 `performance/results/2026-08-09-phase-2-capacity-P2_77bcd87.md`，机器可读汇总为同名 JSON。确认所有 batch terminal 后清理隔离数据：

```powershell
.\performance\k6\scripts\cleanup-data.ps1 -WhatIf
.\performance\k6\scripts\cleanup-data.ps1 -Confirm
```

停止本轮启动的观测组件时使用 `docker compose stop prometheus grafana`，保留 volumes 和其他共享依赖。
