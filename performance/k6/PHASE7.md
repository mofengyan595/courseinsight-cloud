# Phase 7: bounded Transactional Outbox publisher

Phase 7 compares the repeated Phase 6 serial-publisher baseline with a bounded
parallel synchronous-send pipeline. The fixed workload is 1000 tasks (five real
200-row API uploads), 100 ms HTTP AI Stub, consumer concurrency 8, Outbox batch
100 and publisher fixed delay 1000 ms.

The implementation has a conservative default publish concurrency of 2. The
formal sensitivity experiment repeats both concurrency 2 and 4 three times.
Each run refuses to start unless the previous tasks and Outbox rows are fully
terminal.

```powershell
$env:PERF_PASSWORD = '<ephemeral test password>'
.\performance\k6\scripts\run-phase4-experiment.ps1 `
  -ExperimentId P7_OUTBOX `
  -RunsPerConcurrency 3 `
  -Concurrencies 8 `
  -ExperimentPhase 7 `
  -ConfigurationLabel bounded-c4 `
  -OutboxBatchSize 100 `
  -OutboxPublishIntervalMs 1000 `
  -OutboxPublishConcurrency 4 `
  -MainStubDelayMs 100 `
  -BacklogConcurrency 4 `
  -SkipDelayControl `
  -ContextPath .\performance\results\phase-7-c4-runtime-context.json
```

Aggregate the raw k6, Prometheus, RocketMQ-native, database-backlog and terminal
outcome artifacts with:

```powershell
.\performance\k6\scripts\aggregate-phase7.ps1 -ExperimentId P7_OUTBOX
```

The result metadata stores Git HEAD and configuration but never stores the
password or JWT. `WAITING + PROCESSING` remains a database proxy; RocketMQ ready,
inflight and lag values come from the broker's native Prometheus endpoint.

After confirming the batches are terminal, either isolated dataset can be
removed with the existing guarded cleanup script:

```powershell
.\performance\k6\scripts\cleanup-phase4-data.ps1 `
  -ContextPath .\performance\results\phase-7-runtime-context.json
.\performance\k6\scripts\cleanup-phase4-data.ps1 `
  -ContextPath .\performance\results\phase-7-c4-runtime-context.json
```
