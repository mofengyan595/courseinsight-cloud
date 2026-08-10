# CourseInsight Phase 7 - bounded Outbox publisher

- Git HEAD: d51fe5844c08e3b0000e1d902357a34b5986bcf2 with uncommitted Phase 7 implementation
- Workload: 5 x 200-row API batches, 100 ms deterministic HTTP Stub, consumer 8, Outbox batch 100, fixed delay 1000 ms
- Repetitions: Phase 6 before = 3; Phase 7 concurrency 2 = 3; Phase 7 concurrency 4 = 3

## Before / after

| Metric | Before serial | After bounded c4 | Change |
| --- | ---: | ---: | ---: |
| Completion | 27.559 +/- 3.205 s | 20.326 +/- 1.883 s | -26.25% |
| Throughput | 36.595 tasks/s | 49.468 tasks/s | +35.18% |
| Outbox pending drain | 40.80 events/s | 59.97 events/s | +46.98% |
| MQ inflight peak | 22.7 | 76.0 | +235.29% |
| Retry/failure | 0 / 0 | 0 / 0 | unchanged |

## Candidate sensitivity

| Publish concurrency | Completion mean +/- SD | Throughput | Outbox drain | Send avg/p95/p99 | Observed peak | Hikari peak/max |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2 | 25.524 +/- 1.699 s | 39.299 | 45.44 | 3.91/3.44/5.03 ms | 2 | 5/10 |
| 4 | 20.326 +/- 1.883 s | 49.468 | 59.97 | 5.88/3.50/4.92 ms | 4 | 10/10 |

## Correctness and boundary

- Across the six Phase 7 candidate runs, all 6000 tasks reached SUCCESS with zero task retry, Outbox retry, terminal failure, Outbox failure and unexpected HTTP 5xx.
- A fixed-size executor and at most N submitted-but-unfinished sends bound concurrency; the fixed-delay scheduler is additionally guarded against overlapping cycles.
- Every claim writes a new 32-character publish token. SENT/FAILED updates require id + PUBLISHING + the current token, so stale attempt completion cannot mutate a newer owner.
- Broker acceptance followed by a mark-SENT crash deliberately leaves PUBLISHING for stale recovery; a duplicate send is allowed, preserving at-least-once rather than pretending exactly-once.
- With c4, RocketMQ ready remains zero and broker in/out rates match, while inflight grows and one run observes Hikari 10/10. The next boundary is downstream consumer/result persistence and shared DB-pool contention; this experiment cannot separate them further.

## Decision

- Keep the bounded publisher and attempt fencing. Explicit concurrency 4 produced a material repeated improvement; concurrency 2 alone was only a small gain.
- Keep the production default conservative at 2. The local c4 result is a benchmark setting, not enough evidence to promote 4 globally because Hikari reached 10/10 once.

## Resume-safe numbers

- Under the stated local Stub workload, bounded c4 changed mean completion from 27.559 s to 20.326 s (-26.25%) and throughput from 36.595 to 49.468 tasks/s (+35.18%).
- Safe caveat: single Windows machine, deterministic 100 ms HTTP Stub, three 1000-task runs per compared setting; no real BERT/LLM and no production cluster claim.
