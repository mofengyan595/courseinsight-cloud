# CourseInsight Phase 6 - downstream consumer capacity

- Experiment: P6_CONSUMER
- Git HEAD: 00ba94e140d35c638ac2322142cd7232be35b583
- Host: AMD Ryzen 7 5800H with Radeon Graphics; 13.87 GiB visible RAM; Docker 16 CPUs / 6.7 GiB
- Workload: 5 x 200-row API batches = 1000 tasks per run
- Fixed: 100 ms deterministic HTTP AI Stub, Outbox batch 100, publisher interval 1000 ms
- Repetitions: 3 per consumer concurrency
- RocketMQ native metrics: 1 s scrape

## Repeated results

| Consumer | Completion mean +/- SD (s) | Throughput (tasks/s) | MQ inflight peak | MQ inflight drain (s) | Persistence-rate proxy (/s) | Retry | Failure |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2 | 68.211 +/- 1.692 | 14.666 | 648.7 | 43.333 | 15.569 | 0 task / 0 Outbox | 0 |
| 4 | 36.406 +/- 2.760 | 27.569 | 272.7 | 10.667 | 30.720 | 0 task / 0 Outbox | 0 |
| 8 | 27.559 +/- 3.205 | 36.595 | 22.7 | 6.000 | 38.254 | 0 task / 0 Outbox | 0 |

## Scaling

- c2 -> c4: completion -46.63%; throughput +87.97%; speedup 1.874x; scaling efficiency 93.7%; inflight peak -57.97%; inflight drain -75.38%; AI p95 0.01%.
- c4 -> c8: completion -24.30%; throughput +32.74%; speedup 1.321x; scaling efficiency 66.1%; inflight peak -91.69%; inflight drain -43.75%; AI p95 0.01%.

## Resource and broker observations

| Consumer | AI avg/p95/p99 (ms) | Outbox drain (/s) | MQ in/out active avg (/s) | CPU peak | Heap peak (MiB) | GC sum (s) | Hikari peak/max | HTTP 5xx |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2 | 105.41/110.97/115.38 | 42.36 | 37.51/37.51 | 12.3% | 83.50 | 0.154 | 2/10 | 0 |
| 4 | 105.61/110.98/115.85 | 40.28 | 36.19/36.19 | 7.6% | 76.51 | 0.170 | 1/10 | 0 |
| 8 | 105.35/111.00/116.05 | 40.80 | 35.36/35.36 | 6.5% | 80.64 | 0.152 | 3/10 | 0 |

## Call-chain interpretation

- The listener customizer sets both RocketMQ consumer thread bounds to the requested c2/c4/c8 value.
- Each message stays on one consumer thread through claim, blocking AI HTTP, and the transactional SUCCESS/result insert. No shared serial persistence scheduler exists.
- c2 -> c4 nearly doubles completion throughput and sharply reduces inflight, proving that c2 was constrained by available consumer workers around the synchronous 100 ms HTTP call.
- c8 still improves, but native inflight falls to only about 20-24 messages and task throughput converges on Outbox publish supply. CPU, Hikari, GC and AI p95 remain stable, so they are not the observed c8 boundary.

## Recommendation

- c8 was worth testing and remains faster than c4, but shows diminishing scaling. Testing above c8 is not justified until publisher supply is increased in a separate experiment.
- Batch size 100 has value only together with higher consumer concurrency: it unlocks c4/c8 scaling, while Phase 5 showed no benefit at c2 alone.
- If one implementation optimization is attempted next, target the sequential Outbox claim -> syncSend -> mark-sent path with bounded pipelining and unchanged correctness/retry fencing.

## Native-metric collection note

- c2/c4 native artifacts were backfilled from retained one-second Prometheus samples using each original run window after a phase-gating bug was found. c8 artifacts were written directly by the corrected runner. No metric value was synthesized.

## Resume-safe numbers

- Eligible: all three consumer aggregate rows and c2->c4/c4->c8 scaling comparisons. Each is based on three runs under the fixed Phase 6 conditions.
- Scope caveat: single local machine and deterministic Stub; these are not real-LLM or production-cluster capacity numbers.
