# Phase 5: RocketMQ native backlog and Outbox capacity

This experiment keeps the Phase 4 deterministic 100 ms HTTP AI Stub and the
same five-by-200 task burst. It adds RocketMQ 5.3.2 broker-native Prometheus
metrics and compares the database publishing backlog with broker state.

## Native metrics

The local broker enables its built-in `PROM` exporter on container port 5557.
The host mapping is loopback-only; Prometheus scrapes the container directly at
one-second intervals. The focused dashboard panels and raw artifacts cover:

- `rocketmq_consumer_ready_messages`
- `rocketmq_consumer_inflight_messages`
- `rocketmq_consumer_lag_messages`
- `rocketmq_consumer_queueing_latency`
- `rocketmq_consumer_lag_latency`
- `rocketmq_messages_in_total` / `rocketmq_messages_out_total`
- retry-ready and DLQ metrics when the broker emits matching series

Ready, inflight and lag are broker-native metrics. The existing database proxy
remains separately defined as `analysis_task WAITING + PROCESSING`; it is not
RocketMQ queue depth.

RocketMQ 5.3.2 exposes descriptors for queueing and lag latency, but the current
classic PushConsumer did not emit samples for those two metrics during the
formal runs. Empty series are stored as unavailable, never converted into
fabricated zero latency.

## Formal matrix

Every group uses the same HEAD, machine, Docker resources, 1000-task fixture,
100 ms Stub and 1000 ms publisher interval. Each group runs three times:

```text
OUTBOX_BATCH_SIZE=20, consumer=2
OUTBOX_BATCH_SIZE=20, consumer=4
OUTBOX_BATCH_SIZE=100, consumer=2
```

Only the Outbox batch size changes in the controlled publishing A/B. No Outbox
state, retry, fencing, lease, transaction or consumer semantics are changed.

## Publisher behavior under test

The current publisher is a Spring fixed-delay scheduler. It selects at most 20
due PENDING/FAILED/PUBLISHING rows in ID order, without `SKIP LOCKED`. It then
processes the selected rows sequentially: conditional claim to PUBLISHING,
`rocketMQTemplate.syncSend`, and conditional mark to SENT. A send exception
marks FAILED, increments `retry_count`, and delays the next attempt by 30
seconds. The scheduler method has no encompassing `@Transactional` boundary.

Because fixed delay begins after the invocation finishes, one current cycle is
20 sequential claim/send/mark operations followed by the configured 1000 ms
wait. Phase 5 changes only the existing `OUTBOX_BATCH_SIZE` configuration for
the A/B; it does not alter these correctness or transaction semantics.

## Run and aggregate

Use a local random password. The runner passes it only through the current
process environment and does not write it to artifacts.

```powershell
$env:PERF_PASSWORD = '<local random password, 8-64 characters>'

.\performance\k6\scripts\run-phase4-experiment.ps1 `
  -ExperimentId P5_OUTBOX `
  -RunsPerConcurrency 3 `
  -Concurrencies 2,4 `
  -BacklogConcurrency 64 `
  -SkipDelayControl `
  -ExperimentPhase 5 `
  -ConfigurationLabel current-b20 `
  -OutboxBatchSize 20 `
  -OutboxPublishIntervalMs 1000

.\performance\k6\scripts\run-phase4-experiment.ps1 `
  -ExperimentId P5_OUTBOX `
  -RunsPerConcurrency 3 `
  -Concurrencies 2 `
  -BacklogConcurrency 64 `
  -SkipDelayControl `
  -ExperimentPhase 5 `
  -ConfigurationLabel candidate-b100 `
  -OutboxBatchSize 100 `
  -OutboxPublishIntervalMs 1000

.\performance\k6\scripts\aggregate-phase5.ps1 -ExperimentId P5_OUTBOX
```

Each formal run writes k6, application-Prometheus, database-backlog,
RocketMQ-native and terminal-outcome JSON files. The aggregate script excludes
artifacts whose configuration label contains `pilot`.

## Cleanup

Cleanup verifies that all isolated tasks are terminal before deleting only the
five generated courses, their analysis rows and the isolated performance users.
It does not remove Docker volumes or shared application data.

```powershell
.\performance\k6\scripts\cleanup-phase4-data.ps1 `
  -ContextPath .\performance\results\phase-5-runtime-context.json

docker compose -f .\performance\ai-stub\compose.yaml stop
```
