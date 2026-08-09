# CourseInsight Phase 5 - RocketMQ native backlog and Outbox capacity

- Experiment: P5_OUTBOX
- Git HEAD: b879acfcdff625851257cd51bbe1e4f1b58b2337
- Host: AMD Ryzen 7 5800H with Radeon Graphics; 13.87 GiB visible RAM; Docker 16 CPUs / 6.7 GiB
- Workload: five API uploads x 200 rows = 1000 tasks per run
- AI backend: deterministic FastAPI Stub, real HTTP, 100 ms delay, no BERT/LLM
- Repetitions: 3 per group
- RocketMQ: 5.3.2 built-in Prometheus exporter, 1 s scrape

## Current Outbox publisher implementation

- The scheduler uses fixed delay: the 1000 ms wait starts only after the previous `publishPending` call and all selected sends finish.
- The current query selects due PENDING/FAILED/PUBLISHING rows in ID order with `LIMIT 20`. It does not use `SKIP LOCKED`.
- Each row is conditionally claimed as PUBLISHING, synchronously sent with `rocketMQTemplate.syncSend`, then conditionally marked SENT. Sends are sequential within the scheduler invocation.
- A send exception marks the row FAILED, increments `retry_count`, and schedules a retry after 30 seconds. The publisher method has no encompassing `@Transactional` boundary.
- Therefore one cycle is 20 sequential claim/send/mark operations plus the following 1000 ms fixed delay. The repeated 15.2-15.4 PENDING events/s drain rate is consistent with this cadence.

## Repeated results

| Config | Consumer | Completion mean +/- SD (s) | Throughput (tasks/s) | Outbox drain (events/s) | MQ ready peak | MQ inflight/lag peak | Retry | Failure |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| current-b20 | 2 | 69.567 +/- 0.920 | 14.376 | 15.416 | 0 | 18.0/18.0 | 0 task / 0 Outbox | 0 |
| current-b20 | 4 | 69.618 +/- 0.653 | 14.365 | 15.222 | 0 | 16.3/16.3 | 0 task / 0 Outbox | 0 |
| candidate-b100 | 2 | 71.461 +/- 2.215 | 14.002 | 36.052 | 1 | 615.0/615.0 | 0 task / 1 Outbox | 0 |

## Native backlog versus database state

- Current batch=20: Outbox PENDING reaches 1000 and drains at the same approximately 15 events/s rate as completion. Broker ready remains zero; inflight/lag only reaches 16-18. Most queued work is therefore still on the database publishing side.
- Candidate batch=100: Outbox drain rate rises, while broker inflight/lag rises above 600 and ready remains approximately zero. The PushConsumer has pulled the published messages into inflight state, so ready alone would under-report backlog.
- WAITING + PROCESSING is retained only as an end-to-end database proxy. It is not reported as broker queue depth.
- Queueing-latency and lag-latency time series were unavailable in all formal runs even though the exporter exposed their metric descriptors.

## Single-variable A/B: Outbox batch 20 -> 100 at consumer=2

- Completion time change: 2.72%
- End-to-end throughput change: -2.60%
- Outbox PENDING drain-rate change: +133.86%
- Mean MQ inflight peak change: +597.0 messages
- AI p95 change: -0.03%
- Retry/failure delta: 0 task retry, 1 Outbox retry, 0 terminal failure
- The single candidate Outbox retry was a `syncSend` timeout (`RemotingTooMuchRequestException: sendDefaultImpl call timeout`) in run 1. The configured retry recovered; all 1000 tasks in that run succeeded.

## Why consumer=4 did not beat consumer=2

- With batch=20, c2 -> c4 changes completion by 0.07% and throughput by -0.08%.
- The publisher supplies only approximately 15 events/s, broker ready is continuously zero, and native lag stays small. Additional consumer threads are starved rather than saturated.
- Batch=100 proves that the publisher can be made faster, but the backlog then relocates to broker inflight at c2. This experiment does not isolate the next downstream limiter within consumer dispatch, AI HTTP, and result persistence.

## Resource and reliability observations

| Config | Consumer | AI avg/p95/p99 (ms) | CPU peak | Heap peak (MiB) | GC pause sum (s) | Hikari peak/max | HTTP 5xx | Outbox publish success |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| current-b20 | 2 | 105.59/111.00/115.37 | 6.1% | 96.17 | 0.125 | 2/10 | 0 | 100.000% |
| current-b20 | 4 | 105.55/110.92/111.83 | 8.4% | 81.66 | 0.162 | 3/10 | 0 | 100.000% |
| candidate-b100 | 2 | 105.82/110.97/115.12 | 10.4% | 124.52 | 0.115 | 5/10 | 0 | 99.967% |

## Recommendation

- Do not promote OUTBOX_BATCH_SIZE=100 to the production default from this experiment. It materially accelerates publication but does not improve total completion or throughput at c2 and introduces one recovered Outbox retry across 3000 tasks.
- The next discriminating experiment is batch=100 with consumer=2 versus 4, keeping everything else fixed. It tests whether the new broker inflight backlog can be drained by extra consumers.

## Resume-safe numbers

- Eligible: the three aggregate rows above, the batch 20 -> 100 A/B percentages, and zero terminal/task failures. Each is based on three 1000-task runs with the same machine, 100 ms deterministic HTTP Stub, and unchanged 1000 ms publisher interval.
- Not eligible as a numeric claim: RocketMQ queueing latency/lag latency, because this client/version combination emitted no samples.
