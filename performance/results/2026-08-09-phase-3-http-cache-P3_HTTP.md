# CourseInsight 第三阶段 Cache A/B 与 HTTP Capacity（P3_HTTP）

- 时间：2026-08-09T12:05:13.9156380Z；Git HEAD：`2134d241734f24595c48d49df7b679a5200a1f7b`。
- 机器：AMD Ryzen 7 5800H，16 logical CPUs，13.87 GiB RAM；Windows 10.0.26200.0，Docker Desktop。
- 固定 SLO：error `<1%`、p95 `<200 ms`、achieved RPS `>=99% target`、`dropped_iterations=0`。
- 正式 A/B：100 RPS × 30 秒，`constant-arrival-rate`，相同 ID 顺序、每个 key 每次只请求一次，miss/hit 各 3 次。
- AI 容器健康，active backend 为 BERT、LLM configured；本轮 HTTP 查询不调用 AI。Prometheus target 全程为 UP。

## Cache A/B：Course analytics

| Metric | Miss | Hit | Change |
| --- | ---: | ---: | ---: |
| p50 | 12.97 ms | 9.94 ms | -23.38% |
| p95 | 15.25 ms | 12.36 ms | -18.98% |
| p99 | 17.38 ms | 13.90 ms | -19.99% |
| max | 39.76 ms | 20.64 ms | n/a |
| achieved RPS | 100.02 | 100.03 | 0.01% |
| error rate | 0.0000% | 0.0000% | n/a |
| Hikari peak (mean) | 1.67 | 1.00 | -40.00% |
| MySQL Com_select / run | 9040 | 6041 | -33.18% |

p95 的 run-to-run sample SD：miss 0.33 ms，hit 0.97 ms。Analytics 命中前仍会执行课程归属查询，
因此 hit 不是零 MySQL 路径；缓存使 p95 降低 18.98%，每 run 的 `Com_select` 降低 33.18%。

## Cache A/B：Course detail

| Metric | Miss | Hit | Change |
| --- | ---: | ---: | ---: |
| p50 | 11.11 ms | 8.51 ms | -23.43% |
| p95 | 14.31 ms | 10.69 ms | -25.34% |
| p99 | 17.37 ms | 12.30 ms | -29.16% |
| max | 31.87 ms | 17.26 ms | n/a |
| achieved RPS | 100.03 | 100.02 | -0.01% |
| error rate | 0.0000% | 0.0000% | n/a |
| Hikari peak (mean) | 1.00 | 0.67 | -33.33% |
| MySQL Com_select / run | 6039 | 3039 | -49.68% |

p95 的 run-to-run sample SD：miss 2.64 ms，hit 0.16 ms。固定 arrival rate 下 achieved RPS 本应相同；
收益体现在 p95 降低 25.34%、p99 降低 29.16%，以及每 run 的 `Com_select` 降低 49.68%。JWT
converter 在 hit 路径仍会查询 `app_user`，所以数据库查询不会降到零。

## Course detail 容量

### miss

| Target RPS | Achieved RPS | p95 | p99 | Error | Dropped | Result | CPU max | Hikari peak |
| ---: | ---: | ---: | ---: | ---: | ---: | :---: | ---: | ---: |
| 100 | 100.00 | 12.48 ms | 13.69 ms | 0.0000% | 0 | PASS | 2.9% | 2 |
| 200 | 200.00 | 14.21 ms | 16.63 ms | 0.0000% | 0 | PASS | 5.7% | 3 |
| 400 | 400.00 | 19.01 ms | 26.64 ms | 0.0000% | 0 | PASS | 14.1% | 5 |
| 600 | 600.03 | 46.95 ms | 64.41 ms | 0.0000% | 0 | PASS | 21.0% | 10 |
| 700 | 658.13 | 355.08 ms | 454.76 ms | 0.0000% | 1256 | FAIL | 25.6% | 10 |
| 800 | 652.20 | 589.11 ms | 688.99 ms | 0.0000% | 4435 | FAIL | 23.8% | 10 |

确认的 max stable RPS：600；首次失败：700 RPS（p95、achieved RPS、dropped iterations 均失败）。

### hit

| Target RPS | Achieved RPS | p95 | p99 | Error | Dropped | Result | CPU max | Hikari peak |
| ---: | ---: | ---: | ---: | ---: | ---: | :---: | ---: | ---: |
| 100 | 100.03 | 10.99 ms | 13.00 ms | 0.0000% | 0 | PASS | 2.3% | 1 |
| 200 | 200.03 | 11.36 ms | 12.90 ms | 0.0000% | 0 | PASS | 5.0% | 2 |
| 400 | 400.03 | 12.26 ms | 15.32 ms | 0.0000% | 0 | PASS | 10.0% | 4 |
| 600 | 599.93 | 18.52 ms | 32.12 ms | 0.0000% | 0 | PASS | 16.6% | 10 |
| 700 | 578.50 | 530.77 ms | 736.69 ms | 0.0000% | 3646 | FAIL | 19.3% | 10 |
| 800 | 786.30 | 157.56 ms | 217.67 ms | 0.0000% | 411 | FAIL | 24.7% | 10 |

确认的 max stable RPS：600；首次失败：700 RPS（p95、achieved RPS、dropped iterations 均失败）。

600 RPS hit 的 k6 控制台显示 2 个 iteration 在 10 秒 graceful stop 后中断；完成的 17,998 次请求
仍达到 599.93 RPS，满足预先固定的 `>=99% target` 条件，因此保留 PASS，但单独披露该现象。700 和
800 的 hit 都 FAIL；虽然单次 800 hit 的 p95 低于 700，但它仍有 411 dropped iteration 且 achieved
RPS 不足，不能据此把 800 判断为更稳定，也说明边界附近存在明显的单机资源波动。

## Resource observations 与瓶颈

容量边界同时出现 Hikari/MySQL 请求路径饱和和主机整体 CPU 争用：

- miss 700：achieved 658.13，p95 355.08 ms，主机 CPU max 93.7%，Java process CPU max 25.6%，Hikari 10/10，GC pause 增量 0.235 s。
- hit 700：achieved 578.50，p95 530.77 ms，主机 CPU max 98.8%，Java process CPU max 19.3%，Hikari 10/10，GC pause 增量 0.199 s。
- 600 PASS 时 miss/hit 的 Hikari 也已到 10/10，但主机 CPU max 仅 84.1%/74.5%，p95 为 46.95/18.52 ms；继续升压后连接池排队与主机 CPU 争用同时放大 tail latency。
- 所有容量 run 的 HTTP 5xx、MySQL slow query、InnoDB buffer-pool physical read 增量均为 0；700 边界 heap 约 135–158 MiB，GC 增量很小，JVM heap/GC 不是主要限制。
- Redis 在 700 边界的 CPU time 增量约为 miss 4.42 s、hit 2.12 s，且无 Redis error 证据，不支持把 Redis 判断为首要瓶颈。

限制：当前没有 MySQL/Redis container CPU exporter，Tomcat thread 指标也未返回可用样本，因此无法把
93.7%–98.8% 的主机 CPU 精确归因到 MySQL、k6 或其他容器，也无法仅凭本轮区分“Hikari 上限 10”
与“下游 MySQL/主机 CPU”谁是单一根因。可确认的是二者在容量边界同时出现，而不是 GC 或 Redis 饱和。

## Resume-safe numbers

- 可安全用于简历/面试：在本机、同一 HEAD、100 RPS × 30 秒、严格相同 open-model workload、每组 3 次下，Course detail Redis hit 使 p95 降低 25.34%、p99 降低 29.16%；Analytics p95 降低 18.98%。必须同时带上测试条件。
- 不建议作为简历容量数字：600 RPS 只是本机单次阶梯的 confirmed stable level；边界档未做 3 次重复，且 hit 700/800 有非单调波动，不能外推为生产或跨机器容量。

机器可读汇总：`2026-08-09-phase-3-http-cache-P3_HTTP.json`。所有单次 k6/Prometheus 文件名见
`runs[].files`；pilot 排除和 600 hit 中断记录见 `P3_HTTP-console-observations.json`。
