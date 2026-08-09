# CourseInsight Phase 4 - AI Stub and RocketMQ async-chain benchmark

- Experiment: P4_STUB100
- Git HEAD: b0a929f071c198ac820443cf7a2204a54cc3b1c3
- Host: AMD Ryzen 7 5800H with Radeon Graphics; 13.87 GiB visible RAM; Docker 16 CPUs / 6.7 GiB
- Workload: 5 concurrent API uploads x 200 rows = 1000 tasks per run
- AI backend: deterministic FastAPI Stub, real HTTP, 100 ms delay, no BERT/LLM
- Repetitions: 3 per consumer concurrency
- Backlog proxy: WAITING + PROCESSING sampled every 0.5 s; not broker-native queue depth

## Consumer scaling

| Consumer | Completion mean (s) | Throughput mean (tasks/s) | Speedup | Efficiency | Retry | Failure |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 131.689 | 7.594 | 1.000x | 100.0% | 0 | 0 |
| 2 | 70.128 | 14.261 | 1.878x | 93.9% | 0 | 0 |
| 4 | 70.934 | 14.100 | 1.856x | 46.4% | 0 | 0 |
| 8 | 72.152 | 13.870 | 1.825x | 22.8% | 0 | 0 |

## Latency and resources

| Consumer | Task p50/p95/p99 mean (ms) | AI avg/p95/p99 mean (ms) | AI req/s | CPU peak | Heap peak (MiB) | Hikari peak/max | GC pause sum (s) | HTTP 5xx |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 66502.17/122654.63/127635.25 | 105.39/110.99/115.56 | 7.59 | 4.0% | 81.94 | 2/10 | 0.181 | 0 |
| 2 | 35888.83/63408.40/65830.48 | 105.65/110.97/114.82 | 14.26 | 8.1% | 81.95 | 5/10 | 0.168 | 0 |
| 4 | 36715.17/64766.12/67215.85 | 105.93/111.03/117.76 | 14.10 | 11.0% | 81.30 | 2/10 | 0.167 | 0 |
| 8 | 37760.17/66178.22/68708.79 | 105.93/110.98/117.01 | 13.87 | 9.2% | 150.20 | 4/10 | 0.103 | 0 |

## Backlog burst

- Consumer: 4; tasks: 1000
- Production duration: 3.992 s
- Peak proxy backlog: 996
- Peak Outbox pending: 990
- Drain time: 66.650 s
- Steady completion throughput: 15.039 tasks/s
- Success/failure/retry: 1000/0/0
- Observed Outbox pending drain slope: 15.197 events/s

## 10 ms delay control (single diagnostic run)

- c8: completion 69.142 s; throughput 14.463 tasks/s
- This single run is diagnostic only and is not resume-safe.

## Bottleneck conclusion

- Recommended consumer concurrency: **2**. It has the highest repeated mean throughput and retains 93.9% scaling efficiency versus c1; c4 and c8 are slightly slower with no reliability benefit.
- 1 -> 2: completion time falls 46.75% and throughput rises 87.80%; AI p95 changes -0.01%.
- 2 -> 4: completion time changes +1.15% and throughput -1.13%; this is the first clear saturation point.
- 4 -> 8: completion time changes +1.72% and throughput -1.63%.
- Primary limit: Transactional Outbox dispatch throughput under the current fixed configuration. The c4 backlog run reached 990 pending Outbox events and drained near 15.2 events/s, matching the approximately 14-15 tasks/s plateau.
- The 10 ms c8 control improves completion by only 4.17% versus the 100 ms c8 mean. Combined with stable AI p95, low CPU, Hikari <= 5/10, and zero retry/failure, Stub latency, Java CPU, and the DB pool are not the plateau limit.
- RocketMQ broker versus result persistence cannot be separated further because no broker-native consumer-lag metric is available; the database proxy must not be presented as native queue depth.

## Recommended next experiments

1. At fixed c2 and 1000 tasks, compare the current Outbox dispatch batch/cadence with one larger-batch or shorter-cadence setting.
2. Add broker-native RocketMQ consumer lag, then repeat c2 versus c4 to distinguish broker lag from the database task-state proxy.

## Resume-safe numbers

- Eligible: c1/c2/c4/c8 completion and throughput means, speedup/efficiency, AI p95, and zero retry/failure. Each uses the same 100 ms deterministic Stub and 1000-task workload with three repetitions.
- Not eligible as a repeated number: the single 10 ms control. It is diagnostic only.
- Scope limitation: one local machine; task backlog is a database-state proxy, not RocketMQ native queue depth.
