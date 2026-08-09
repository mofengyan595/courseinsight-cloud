# Phase 6: downstream consumer capacity

Phase 6 fixes the publishing and AI conditions established in Phase 5, then
changes only RocketMQ analysis-consumer concurrency:

```text
AI Stub delay = 100 ms
Outbox batch size = 100
Outbox publisher interval = 1000 ms
workload = 5 x 200 rows = 1000 tasks
consumer = 2, 4, conditionally 8
```

Consumer 8 is run only after three c2 and three c4 runs show material c4
scaling without additional retry or terminal failure.

## Metrics and definitions

Every run stores k6, application Prometheus, database backlog, RocketMQ native
and terminal-outcome JSON artifacts. Broker metrics use one-second samples.

The report uses two distinct drain definitions:

- End-to-end drain: from the end of burst production until all tasks terminal.
- MQ inflight drain: from the first native inflight peak sample to the final
  positive inflight sample.

The result-persistence-rate proxy is calculated from the 10th-to-90th
percentile `analysis_task.completed_at` window. The SUCCESS update and one
`analysis_result` insert commit in the same transaction, so this is a reliable
transactional-completion proxy, not a standalone insert timer.

## Run

Run c2/c4 first with an ephemeral password:

```powershell
$env:PERF_PASSWORD = '<local random password, 8-64 characters>'

.\performance\k6\scripts\run-phase4-experiment.ps1 `
  -ExperimentId P6_CONSUMER `
  -RunsPerConcurrency 3 `
  -Concurrencies 2,4 `
  -BacklogConcurrency 64 `
  -SkipDelayControl `
  -ExperimentPhase 6 `
  -ConfigurationLabel batch100 `
  -OutboxBatchSize 100 `
  -OutboxPublishIntervalMs 1000 `
  -UserPrefix perf_p6 `
  -DatasetPrefix PERF_P6
```

If c4 materially improves throughput and inflight drain with stable failures,
run c8. A separate isolated data context allows another ephemeral password:

```powershell
$env:PERF_PASSWORD = '<new local random password, 8-64 characters>'

.\performance\k6\scripts\run-phase4-experiment.ps1 `
  -ExperimentId P6_CONSUMER `
  -RunsPerConcurrency 3 `
  -Concurrencies 8 `
  -BacklogConcurrency 64 `
  -SkipDelayControl `
  -ExperimentPhase 6 `
  -ConfigurationLabel batch100 `
  -OutboxBatchSize 100 `
  -OutboxPublishIntervalMs 1000 `
  -UserPrefix perf_p6c8 `
  -DatasetPrefix PERF_P6C8 `
  -ContextPath .\performance\results\phase-6-c8-runtime-context.json
```

Aggregate after all selected consumer groups complete:

```powershell
.\performance\k6\scripts\aggregate-phase6.ps1 `
  -ExperimentId P6_CONSUMER
```

## Native-metric compatibility note

The first six Phase 6 runs exposed a performance-runner phase gate that wrote
native artifacts only for phase 5. The underlying one-second Prometheus samples
were retained. `backfill-native-metrics.ps1` queries the exact original
`testStartedAt` to `observedAt` window, marks each artifact as backfilled, and
updates only its artifact link. It does not synthesize metric values. The runner
now captures native files directly for phase 5 and later phases.

## Cleanup

Cleanup refuses to run while any isolated task is nonterminal:

```powershell
.\performance\k6\scripts\cleanup-phase4-data.ps1 `
  -ContextPath .\performance\results\phase-6-runtime-context.json

.\performance\k6\scripts\cleanup-phase4-data.ps1 `
  -ContextPath .\performance\results\phase-6-c8-runtime-context.json

docker compose -f .\performance\ai-stub\compose.yaml stop
```
