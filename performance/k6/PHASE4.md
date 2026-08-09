# Phase 4: AI Stub and RocketMQ async-chain benchmark

This experiment isolates BERT and LLM variance while retaining the real path:

```text
Batch API -> MySQL -> Transactional Outbox -> RocketMQ
-> Java consumer -> HTTP AI Stub -> result persistence
```

The performance-only FastAPI Stub is in `performance/ai-stub/`. It implements
the existing AI JSON contract, uses a deterministic response, performs no BERT
or LLM work, and sleeps asynchronously for `AI_STUB_DELAY_MS` (100 ms by
default). The normal AI service and Java AI client configuration are unchanged.

## Workload

One run creates five 200-row batches concurrently through the real API, for
1000 tasks total. Five isolated teacher principals are used because the normal
upload rate limit is enforced. Each principal owns one isolated course and all
logins, JWT checks, object-ownership checks, CSV parsing, transactions and
message handling remain enabled.

`batch-burst.js` uses one k6 VU because this is one asynchronous burst, not a
closed-model request-throughput test. Batch creation uses `http.batch`; progress
polling continues until every batch is terminal.

## Run

From the repository root, choose a local-only random password. It is passed via
the process environment and is never written to the runtime context or result
files.

```powershell
$env:PERF_PASSWORD = '<local random password, 8-64 characters>'
.performance\k6\scripts\run-phase4-experiment.ps1 `
  -ExperimentId P4_STUB100 `
  -RunsPerConcurrency 3 `
  -Concurrencies 1,2,4,8 `
  -BacklogConcurrency 4 `
  -MainStubDelayMs 100 `
  -ControlStubDelayMs 10

.performance\k6\scripts\aggregate-phase4.ps1 `
  -ExperimentId P4_STUB100 `
  -ExpectedRunsPerConcurrency 3
```

The experiment runner starts the existing Docker dependencies and observability
profile, starts/restarts the local Java process for each consumer concurrency,
and stops the Java process when the matrix finishes. Shared Docker dependency
data is not removed.

## Backlog definition

Backlog is sampled from MySQL every 0.5 seconds:

```text
proxy backlog = analysis_task WAITING + PROCESSING
```

Retryable `FAILED` tasks and Outbox `PENDING`/`PUBLISHING` counts are recorded
separately. This proxy is deliberately not described as RocketMQ native queue
depth. A broker-native consumer-lag metric was not available in the current
observability stack.

## Artifacts

Every run writes four small JSON files to `performance/results/`:

- `*-k6.json`: API/poll metrics and fixed run metadata.
- `*-prometheus.json`: the exact PromQL queries and returned window values.
- `*-backlog.json`: 0.5-second task/Outbox state samples.
- `*-outcome.json`: terminal DB outcome, task latency percentiles and artifact links.

The aggregate JSON and Markdown report are generated separately. They do not
contain JWTs, passwords, comment text, prompts or application logs.

## Cleanup

Only run cleanup after aggregation. It refuses to run while any Phase 4 task is
non-terminal and deletes only the five course IDs and isolated user prefix from
the runtime context.

```powershell
.performance\k6\scripts\cleanup-phase4-data.ps1
docker compose -f performance\ai-stub\compose.yaml stop
```
