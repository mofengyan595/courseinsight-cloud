# CourseInsight 后端工程审计

审计日期：2026-10-13 至 2026-10-14。基线：`fbaf08f585de7839050899065ada3c36de5f3fd1`。
范围：Java 后端、Flyway V1–V15、AI HTTP 边界、Redis、RocketMQ、CSV、压测脚本及本地部署配置。
下文“原实现”指本次修改前的代码；最终仅新增 V16 迁移。

本次没有读取 `.env`、操作业务数据库、调用付费模型或重新运行性能压测。原有 `.gitignore` 改动保留。
测试中的数据库写入仅发生在隔离的 Testcontainers 中；压测清理脚本使用拦截 Docker 调用的离线测试。

## A. 当前已有设计总结

| 方向 | 已确认的实现与优势 |
| --- | --- |
| Redis Cache-Aside ✔ | `CourseService.getById()` 先读 Redis，未命中查数据库并回填；`__NULL__` 区分未命中和已知不存在。Redis 异常时回源。 |
| 提交后失效 ✔ | 课程写入、评论写入/删除、批次创建、分析成功持久化均注册 `afterCommit()`；回滚不触发失效。自动提交的任务状态更新完成后再删统计缓存。 |
| 排行榜发布 ✔ | ZSet + READY 标记通过 Lua 一次发布；空榜也有就绪标记，数据 TTL 比 READY 长，避免把空榜误作未初始化。 |
| JWT + 当前权限 ✔ | HS256、issuer、过期校验；`CurrentUserJwtAuthenticationConverter.convert()` 每个 Bearer 请求按主键查用户，以当前状态和角色授权。 |
| RBAC + 对象归属 ✔ | 写课程和教学分析限教师/管理员；Service 校验教师课程所有权；学生只能删除本人评价、读取本人任务，不能查看完整教学分析。 |
| Transactional Outbox ✔ | 评论/批次、任务、事件同事务；发布器的领取、成功和失败分别使用独立短事务，MQ 等待不占用这些事务。 |
| 发布尝试隔离 ✔ | 每次领取有 `publish_token`；迟到的发送结果不能覆盖新发布者状态。发送成功后写库失败允许超时重发。 |
| 消费与恢复 ✔ | `current_event_id` 隔离入队代次，`execution_token` 隔离执行所有者，数据库时钟判定租约；过期恢复与新 Outbox 同事务。 |
| 数据约束 ✔ | 课程代码、用户名、任务评论、结果任务/评论、事件 ID 均有相应唯一约束；活动评价用生成列唯一约束，软删除后允许重新提交。 |
| 分页与结果查询 ✔ | 请求 DTO 限制 size ≤ 100，MyBatis-Plus 再限制 100；排行榜最多 50；批次结果使用 JOIN，排行榜批量取课程，没有逐行查库的 N+1。 |
| 文件安全 ✔ | CSV 2 MB、200 行、每条 2000 字符、评分 1–5；不按上传文件名写磁盘；导出先鉴权，文件名来自服务端生成的批次号。 |
| 密钥和密码 ✔ | 主配置 JWT secret 来自环境且要求至少 32 字节，密码使用 DelegatingPasswordEncoder 默认 bcrypt；公开测试/Demo 配置不能作为部署密钥。 |
| 本地部署 ✔ | 数据卷持久化，基础服务有 healthcheck，端口绑定回环地址；Java 退出后依赖保留，避免意外删除数据。 |

### Redis 的完整性和边界

详情原 TTL 30 分钟、空值 5 分钟；统计 5 分钟、排行榜 READY 10 分钟。这些值是业务允许短暂旧数据的配置选择，不能仅凭代码证明是最优值。
本次将详情 TTL 改为 24–30 分钟，空值和统计改为 4–5 分钟，随机化只缩短原上限。

空值缓存只覆盖课程详情，不能表述为“所有不存在 ID 都已防穿透”。课程归属、评论、任务、批次等查询仍会访问数据库，尤其权限检查刻意读取当前数据。
对同一个不存在的课程详情 ID，空值缓存有效；大量不同的正数 ID 仍会各触发首次查询。非法非正 ID 已在入口拒绝，但这不是对全部随机 ID 流量的防护。
没有流量证据支持增加 Bloom Filter，受保护的内部查询也不宜为了统一空值缓存而扩大数据陈旧窗口。

详情缓存的互斥重建只限单实例；不同 ID 共享固定 256 个锁槽，少量碰撞会串行，但锁数量不会随随机 ID 增长。
统计和排行榜仍没有合并并发回源；它们的聚合更贵，但没有当前热点流量或重复回源计数，暂不批量扩展锁机制。
Redis 故障时仍回源，TTL 抖动不能解决 Redis 整体不可用导致的数据库压力。

### JWT 是否增加 Redis 用户权限缓存

当前流程是“验签/校验有效期 → 查询 `app_user` → 判断 status/role → 授权”。数据库变更提交后的后续请求会重新读取权限；已进入执行中的请求不会自动被中止。
JWT 的角色 claim 不作为最终授权依据。默认有效期 2 小时，没有 refresh token、单 token 撤销列表或密码变更自动撤销机制，不能宣传为已有功能。

本次保留数据库查询。主键查用户有明确索引，历史压测也已经把它计入命中路径；目前没有证明它是需要立即优化的独立瓶颈。
增加短 TTL 权限缓存会使禁用或降权在缓存存活期内延迟生效；仅做提交后删 Redis，仍有删除失败和旧查询回填窗口。
若以后确有压力，应只缓存 `{id, role, status}`，有界 TTL、Redis 失败回数据库，并把所有权限写入口和失效补偿一起设计。
若仍要求下一请求立即撤权，就需要可靠失效或权威版本校验；后者往往仍需额外权威读取。不能只删掉当前查询。

### 是否增加 Caffeine 二级缓存

本次不增加。详情已经有 Redis，缺少 Redis 网络延迟或 QPS 饱和证据；Caffeine 会增加各 JVM 的失效状态。
若未来证明 Redis 往返成为瓶颈，可先考虑公开课程展示信息或更新很少的静态字典，使用容量上限和很短 TTL。
用户状态、角色、课程归属不适合在保持即时撤权语义的前提下直接加入本地缓存。

多实例的一致性方案至少需要：数据库提交后删除 Redis、可靠广播失效到各 JVM、短本地 TTL 兜底，以及回填竞态控制。
Redis Pub/Sub 本身不补发离线期间的消息，单独使用只能提供尽力失效；不能宣称由它保证严格一致。
当前没有必要为本地缓存再增加一套可靠事件基础设施。

## B. 发现的问题

以下列出可定位的原实现问题。标注“保留边界”的项目没有在本次改成新的业务语义。

### B1. 发布等待占用共享调度线程（P1，已修复）

- 文件：`courseinsight-server/src/main/java/com/courseinsight/server/message/AnalysisOutboxPublisher.java`，类 `AnalysisOutboxPublisher`，方法 `publishPending()/publishBatch()`。
- 关联：同目录 `AnalysisTaskLeaseRecoveryScheduler.java`，类 `AnalysisTaskLeaseRecoveryScheduler`，方法 `recoverExpired()`；主类仅启用了 `@EnableScheduling`。
- 原行为：调度方法在 `completions.take()` 等待整批结束，没有单独调度配置。Java 17 下 Spring Boot 默认调度线程池只有 1 个线程。
- 风险：Broker 慢、批次耗时长时，租约恢复也排队；增加发送线程数不能解决调度线程占用。
- 修改：发布和恢复各自指定独立单线程调度器；清理启用时也使用独立调度器。固定发送池、在途数量上限、周期防重叠保留。
- 框架依据：[Spring Boot 3.5 调度说明](https://docs.spring.io/spring-boot/3.5/reference/features/task-execution-and-scheduling.html)。

### B2. 多课程缓存固定 TTL（P1，已修复）

- 文件：`courseinsight-server/src/main/java/com/courseinsight/server/cache/CourseDetailCache.java`，类 `CourseDetailCache`，方法 `put()/putNotFound()`；同目录 `CourseAnalyticsCache.java`，类 `CourseAnalyticsCache`，方法 `put()`。
- 原行为：同类数据使用完全相同的固定 TTL。
- 风险：集中预热或写入后可能集中失效。这里确认的是风险条件存在，没有声称发生过生产缓存雪崩。
- 修改：增加 80%–100% TTL 抖动，不扩大原有旧值存活上限。排行榜只有一组 key，保留其 Lua 中数据/READY 的 TTL 关系。

### B3. 热点详情未合并并发回源（P1，已修复）

- 文件：`courseinsight-server/src/main/java/com/courseinsight/server/service/CourseService.java`，类 `CourseService`，方法 `getById()`。
- 原行为：并发请求同时未命中后，各自查询并回填；整个方法还有只读事务，缓存命中和等待路径也被包在事务中。
- 风险：单个热点过期时会重复查询。原有空值缓存解决的是回填之后的重复访问，不能自动合并同时发生的首次请求。
- 修改：先读缓存，未命中进入有界锁槽，再读一次缓存；只由仍未命中的请求回源。单次详情 SELECT 不需要跨多条查询的一致性快照，移除该读方法的事务，避免缓存路径/锁等待占用数据库事务。
- 边界：多实例每个实例仍可能各回源一次；Redis 不可用时，也不会变成可用的本地数据缓存。

### B4. 提交后删除仍可能早于旧值回填（P1，部分收敛）

- 文件：`courseinsight-server/src/main/java/com/courseinsight/server/service/CourseService.java`，类 `CourseService`，方法 `getById()/evictCacheAfterCommit()`。
- 原行为：旧查询取到数据后，更新事务可以先提交并删除缓存，旧查询随后把旧值写回。
- 风险：仅保证 afterCommit 不足以排除该竞态。
- 修改：本实例的详情回填与提交后删除使用同一锁槽。旧回填完成后才删除，避免该窗口在单实例详情路径中留下旧值。
- 边界：统计、排行榜以及不同实例之间仍有并发回填窗口；本次没有引入全局一致性协议。

### B5. 成功 Outbox 无生命周期清理（P1，提供可选机制）

- 文件：`courseinsight-server/src/main/java/com/courseinsight/server/mapper/AnalysisOutboxEventMapper.java`，类 `AnalysisOutboxEventMapper`，方法 `markOwnedAttemptSent()`；表结构 `V6__create_analysis_outbox_event_table.sql`。
- 原行为：成功后保留为 `SENT`，仓库没有删除或归档路径。
- 风险：消息量增加后表、索引和备份持续增长；不是投递丢失问题。
- 修改：新增 `AnalysisOutboxCleanup.cleanupSent()`，按数据库时间，仅删除保留期外的 SENT，单次最多 1000 条（默认 500）；V16 增加 `(status, sent_at, id)` 索引。
- 默认：关闭，保留期 30 天、间隔 1 小时；开启前应确定审计保留/外部归档要求。没有实现归档，也没有清理真实库。
- 可靠性：PENDING、FAILED、PUBLISHING 不清理；任务当前代次保存在 `analysis_task.current_event_id`，执行幂等不依赖成功 Outbox 行永久存在。

### B6. 非法 ID 和部分输入异常未统一处理（P1，已修复）

- 文件：`courseinsight-server/src/main/java/com/courseinsight/server/controller/` 下课程、评论、任务、批次、用户管理等 9 个 Controller 的路径参数方法；`exception/GlobalExceptionHandler.java`，类 `GlobalExceptionHandler`。
- 原行为：`Long` 仅负责类型转换，0/负数仍进入 Service；类型溢出、错误 JSON、缺少文件 part 等异常没有统一 ApiResponse 处理。
- 风险：无效请求进入数据库，错误响应结构不稳定；不应把“返回 404”误认为已经完成正数参数校验。
- 修改：路径 ID 增加 `@Positive`；统一处理 MVC 方法校验、参数类型、JSON 和缺少必填参数/part 的 400，不回传解析异常原文。

具体路径均位于 `courseinsight-server/src/main/java/com/courseinsight/server/controller/`：

| 文件/类 | 增加路径校验的方法 |
| --- | --- |
| `CourseController.java` / `CourseController` | `update()/getById()` |
| `CommentController.java` / `CommentController` | `create()/page()/delete()` |
| `CommentAnalysisController.java` / `CommentAnalysisController` | `getByCommentId()` |
| `CourseAnalyticsController.java` / `CourseAnalyticsController` | `getSummary()` |
| `AnalysisTaskController.java` / `AnalysisTaskController` | `getByCommentId()` |
| `AnalysisTaskEnqueueController.java` / `AnalysisTaskEnqueueController` | `enqueue()` |
| `AnalysisExecutionController.java` / `AnalysisExecutionController` | `execute()` |
| `AnalysisBatchController.java` / `AnalysisBatchController` | `upload()/getProgress()/getResults()/exportResults()/retryFailed()` |
| `AdminUserController.java` / `AdminUserController` | `updateRole()` |

### B7. CSV 畸形内容处理缺口（P1，已修复）

- 文件：`courseinsight-server/src/main/java/com/courseinsight/server/service/AnalysisBatchCsvParser.java`，类 `AnalysisBatchCsvParser`，方法 `parse()/openUtf8ReaderWithoutBom()/parseRow()`。
- 原行为：迭代中的 CSV 读取错误会包装成 `UncheckedIOException`，原 catch 未覆盖；重复表头、多余列被接受；UTF-8 解码器会替换非法字节。
- 风险：错误文件可能返回 500、取错同名列或静默改变评价文本。
- 修改：统一转为 `InvalidCsvFileException`，禁止重复表头，检查行列数一致性，UTF-8 使用严格解码；读取 BOM 时发生错误也关闭输入流。保留 BOM、引号内逗号和多行内容支持。
- 类型策略：扩展名检查后必须通过实际 CSV 内容解析；不依赖可伪造且常被浏览器误报的 MIME 类型。

### B8. CSV 公式防护不完整（P0，已修复）

- 文件：`courseinsight-server/src/main/java/com/courseinsight/server/service/AnalysisBatchResultService.java`，类 `AnalysisBatchResultService`，方法 `writeCsv()/safeForSpreadsheet()`。
- 原行为：只处理评论和失败原因；仅看首字符且漏掉 LF。其他来自外部 AI 响应的文本字段未处理。
- 风险：导出后用表格软件打开时，某些文本可能被识别为公式。CSV 正确加引号只能解决列/换行转义，不能代替公式防护。
- 修改：对全部导出文本字段应用防护，覆盖 LF/TAB/CR 和前导空白后的公式标记；只改变导出展示，不改变数据库原文。
- 边界：这是文本导出的防御措施，不能承诺所有表格软件在任意二次保存/导入后仍保持相同行为。

### B9. 登录长度与 bcrypt 字节边界未完整约束（P1，已修复）

- 文件：`courseinsight-server/src/main/java/com/courseinsight/server/dto/UserLoginRequest.java`、`UserRegisterRequest.java`；对应登录/注册 Controller 和密码编码服务。
- 原行为：登录只检查非空；注册限制的是 8–64 个字符，没有约束 UTF-8 字节数。25 个中文字符虽然不足 64 个字符，却有 75 字节。
- 风险：超长请求进入限流、查库和密码处理路径；密码编码器的长度边界没有在 API 明确表达。
- 修改：登录用户名 ≤32 字符、密码 ≤64 字符；登录/注册都要求密码 UTF-8 ≤72 字节。注册原有最短长度保留。

### B10. 课程默认分页的索引候选未证明收益（P1，保留评估结论）

- 文件：`courseinsight-server/src/main/java/com/courseinsight/server/service/CourseService.java`，类 `CourseService`，方法 `page()`；表结构 `V1__create_course_table.sql`、`V10__add_course_owner.sql`。
- 原行为：按 `created_at DESC, id DESC` 排序；原索引是主键、code、name、owner/status，没有该排序前缀。
- 风险：无关键词分页可能需要额外排序；不能据此断言现网已经存在慢查询。
- 试验：临时增加 `(created_at, id)`，在隔离 MySQL 的 2000 条已提交合成记录上更新统计信息，对比正常 EXPLAIN 与忽略该索引的控制查询；两者仍均为全表扫描 + filesort。没有给被测查询加 FORCE INDEX。
- 决策：撤回该候选迁移和只针对候选的新增测试，保留执行计划证据。未修改原有测试、未放宽断言，不把没有证实的索引收益作为交付。以后应结合真实数据规模、分布和耗时再决定。
- 边界：`LIKE '%keyword%'` 的 OR 模糊查询并不能依靠普通 name/code 索引完成高效前缀定位；本次不改变搜索语义、不增加全文搜索组件。分页 COUNT 和深 offset 的成本仍存在。

### B11. 压测数据清理可能越出前缀、漏判重试任务（P0，已修复）

- 文件：`performance/k6/scripts/cleanup-data.ps1`，顶层 active-task 检查和清理 SQL；`cleanup-phase4-data.ps1` 的用户清理 SQL；`prepare-http-data.ps1` 的课程 ID 查询。
- 原行为：允许 `_` 的前缀直接放入 LIKE；phase4 仅转义分隔符 `_`，没有转义前缀自身的 `_`。通用清理只拦截 WAITING/PROCESSING，未拦截非终止 FAILED，且多表删除未包事务。
- 风险：如 `PERF_X` 也可能匹配 `PERFAX`；等待 MQ 重试的任务可能被当成已结束；中途 SQL 失败可能留下部分删除状态。
- 修改：使用显式 `ESCAPE '!'` 匹配字面前缀；把 `FAILED AND dead_lettered_at IS NULL` 纳入活动检查；通用多表删除使用事务。
- 验证边界：离线测试拦截全部 Docker 调用，验证生成 SQL 和拒绝分支，不执行真实清理。并发上传与维护清理仍须错开，脚本不是在线数据生命周期服务。

### B12. 压测控制台摘要口径与正式报告不一致（P1，已修复）

- 文件：`performance/k6/lib/summary.js`，函数 `buildConsoleSummary()`；对照 `scripts/aggregate-http-benchmark.ps1` 和 `scenarios/http-cache-open.js`。
- 原行为：控制台使用含 setup 登录的 `http_reqs/http_req_duration`，正式汇总使用查询专用指标；缺失 error rate 也显示成 0。
- 风险：查看控制台时可能把总 HTTP 指标当成查询接口成绩，缺失数据看似零错误。
- 修改：存在 query 指标时显示 query 专用 count/latency/error，并按配置测量时长计算 RPS；缺失数据显示 n/a。其他场景明确标注 all HTTP。
- 历史：正式报告的原始指标口径已是 query 专用，本次没有修改历史成绩。

### B13. 缓存删除失败仍由 TTL 收敛（P1，保留边界）

- 文件：`cache/CourseDetailCache.java`、`CourseAnalyticsCache.java` 的 `deleteQuietly()`；`CoursePopularityRankingCache.evict()`。
- 当前行为：删除异常只记录日志，不回滚已经提交的数据库事务，也没有持久化失效任务。
- 风险：Redis 恢复后可能读到尚未过期的旧值。详情上限 30 分钟、统计 5 分钟、排行约 10 分钟，不能称为“更新后必定立即一致”。
- 决策：保留尽力删除 + TTL，文档明确边界。不用有限次数立即重试冒充可靠补偿；若业务要求保证最终删除，需持久化失效事件和恢复机制，超出本次低风险修补范围。
- 权限：课程归属和用户角色/状态仍直接读数据库，不使用这些展示缓存授权。

### B14. 相同 CSV 重复上传不是业务幂等请求（P1，保留业务语义）

- 文件：`service/AnalysisBatchService.java`，类 `AnalysisBatchService`，方法 `upload()`；`AnalysisBatchCreationService.create()`。
- 当前行为：每次上传生成新 batchNo，CSV 导入评论的 user_id 为空，不受单用户活动评价唯一约束限制。
- 风险：重复点击或超时后重传会生成两个批次和两组评价。限流是频率限制，不是幂等。
- 决策：不按文件内容哈希自动去重，因为相同内容可能是合法的再次导入。需要时新增调用方 Idempotency-Key，并以“用户 + 课程 + key”建立数据库唯一约束，重复请求返回同一批次；这需要明确协议和保留期。

### B15. Broker 持久化故障不在当前租约恢复范围内（P1，部署边界）

- 文件：`deploy/rocketmq/broker.conf`，配置 `ASYNC_MASTER/ASYNC_FLUSH`；`mapper/AnalysisOutboxEventMapper.java` 的 `selectPublishable()`；`mapper/AnalysisTaskMapper.java` 的 `selectExpiredExecutionsForUpdate()`。
- 当前行为：单 Broker 异步刷盘；Outbox 标为 SENT 后不再派发；租约恢复只扫描 PROCESSING，不扫描长期 WAITING。
- 风险推演：若 Broker 接收并返回成功、应用标记 SENT，但主机/存储故障使尚未持久化的消息丢失，任务可能停在 WAITING。这不是旧执行覆盖问题，generation/token 不负责恢复已经丢失的入站消息。
- 决策：保留本地开发拓扑，不把它宣称为可承受 Broker/磁盘故障的完整 at-least-once 保证。生产应确定同步持久化/复制策略，并按需要设计长期 WAITING 与投递状态对账；调整刷盘有吞吐成本，不能直接套用原压测数字。
- 证据边界：这里是基于配置、查询条件和 ACK 语义的故障窗口分析，没有进行 Broker 宕机故障注入。[RocketMQ 官方可靠性与刷盘说明](https://rocketmq.apache.org/docs/bestPractice/01bestpractice/)。

## C. 修改计划与取舍

| 等级 | 本次结论 |
| --- | --- |
| P0 必须修改 | CSV 导出公式防护；压测清理的前缀匹配、重试状态和原子性。均已实施，真实清理未执行。 |
| P1 建议修改，已实施 | 调度隔离、TTL 抖动、详情并发回源与失效互斥、可选 Outbox 清理、输入/CSV/密码边界、压测摘要口径。 |
| P1 后续按要求推进 | 课程排序索引先用真实规模数据复核收益；若要求缓存更新必达，设计持久化失效补偿；若要求上传重传幂等，设计 Idempotency-Key；生产确定 MQ 持久化/复制及 WAITING 对账；CI 当前只运行 fast，可增加 integration 作业。 |
| P2 当前不建议 | Caffeine、用户权限 Redis 缓存、MQ 异步回调、Bloom Filter、全文搜索组件、根据经验批量加索引/扩连接池/调线程数。没有对应负载或语义证据。 |

### 数据库、事务和权限补充结论

| 主要查询 | 现有支持及结论 |
| --- | --- |
| 用户认证/登录 | 用户主键、username 唯一索引；登录前按来源和账号限流。 |
| 课程详情/归属 | 课程主键；归属直接查询，展示缓存不参与授权。 |
| 课程分页 | 候选排序索引未改变本次执行计划，已撤回；模糊搜索不误称已有覆盖索引。 |
| 课程评论/本人评论 | `(course_id, created_at, id)`、`(user_id, created_at, id)` 支持定位和排序，status 是残余过滤。大规模软删除场景可用 EXPLAIN 再判断是否加 status，不盲目重复索引。 |
| 任务点查/按评论查 | 主键、comment_id 唯一索引。 |
| 任务恢复 | `(status, lease_until, id)` 配合过期条件和顺序，`FOR UPDATE SKIP LOCKED` 避免恢复者相互等待。 |
| 批次结果 | `(batch_id, status, id)` 可按批次定位；跨状态按 id 排序可能需额外排序，但单批最多 200 条，不再额外加近似索引。 |
| 统计/排行榜 | 课程前缀索引和结果/任务关联主键、唯一索引存在；排行榜按聚合 COUNT 排序无法用普通课程排序索引直接消除，已有 Redis 缓存合理。 |
| Outbox 派发/清理 | 既有 `(status, next_retry_at, id)` 支持到期筛选，多状态按 id 全局排序可能额外排序；本次只为新增清理条件增加必要索引。 |

没有发现当前主要列表路径的逐条查库 N+1；这不表示所有 JOIN 都已经针对生产规模验证最优。
SQL 使用 MyBatis `#{}` 或参数化 Wrapper，未发现把接口输入直接拼进 Java SQL 的路径。压测脚本拼接输入有字符白名单，本次另外修复 LIKE 的字面匹配问题。

AI HTTP 调用位于无事务的 `AnalysisExecutionService` 中；领取是条件更新，成功结果由独立 Service 事务写入，没有跨 HTTP 的长数据库事务或这些调用之间的自调用事务失效。
CSV 先校验权限和解析，再进入独立创建 Service 的事务；最多 200 行，逐行 3 次写入有明确上界，未改成引入新批处理层。
用户登录/注册的密码计算处于事务方法内，可能占用事务时间，但没有当前证据表明已成为需要改造的瓶颈。

创建单条评论由数据库活动评价唯一约束防并发重复，冲突返回 409；任务、结果也有唯一约束。
执行成功的任务重复执行返回已有结果；新代次重入队可能产生多个投递事件，但不会创建多个任务，旧代次不能覆盖结果。
批次失败重试通过行锁和条件更新只恢复终止失败，重复调用不会把已成功任务重新入队。

权限检查覆盖教学统计、完整评论分析、手动执行/入队、批次进度/结果/导出/恢复和评价删除。
未发现允许教师跨课程管理或学生跨用户删除评价的当前入口；结论基于可见代码与测试，不等于形式化安全证明。
Redis 限流在 Redis 故障时放行，这是明确的可用性选择：故障时防暴力登录能力下降；需要更严格防护时应单独确定代理层限流或失败关闭策略。

### 性能结果真实性

核对了 k6 开放到达率脚本、合法 JWT 查询、精确 key 删除/预热、合成课程生成、正式汇总和 12 份 A/B 原始 k6 JSON。
正式测试每组 100 RPS × 30 秒、相同 ID 顺序、每 key 一次、miss/hit 各 3 次；下面重新计算的是历史数据，不是本次修改的性能成绩。

| 历史场景 | miss p95 三次均值 | hit p95 三次均值 | achieved RPS（miss / hit） |
| --- | ---: | ---: | ---: |
| 课程详情 | 14.3135 ms | 10.6859 ms | 100.0333 / 100.0222 |
| 课程统计 | 15.2518 ms | 12.3569 ms | 100.0222 / 100.0333 |

p95 是单次请求时延分布的 95 分位，表中是三个 run 的 p95 均值，不是合并请求后的总体 p95。
固定 arrival rate 下 RPS 接近是预期现象，不能宣传为缓存把吞吐提升了同样百分比；MySQL `Com_select` 是全局计数窗口差值，带背景查询，不能直接当成接口精确查询次数。
容量档同时检查错误、p95、achieved RPS 和 dropped iterations；历史 600 RPS 是本机单档结果，不能当生产容量。

Phase 7 的任务吞吐是 5 × 200 任务、100 ms HTTP Stub、消费者 8、发布并发 4 的历史实验；不是 BERT/LLM 能力，也不是当前默认发布并发 2 的成绩。
本次不运行数据准备/真实压测，不生成新的 p95、RPS 或“优化百分比”。

### Docker 与部署

检查 `compose.yaml`、`compose.demo.yaml`、`deploy/rocketmq/broker.conf`、应用 profiles、AI Dockerfile 和监控配置。
MySQL/Redis/AI 有各自 healthcheck；NameServer/Broker 检查的是 TCP 端口，不能等同于完整消息投递探针。
NameServer 等待卷初始化完成，Broker 等待 NameServer 健康；AI 不依赖数据库，没有必要添加无意义的 depends_on。
主配置凭据从环境注入，`.env` 不参与测试；本次未读取其内容，也未验证真实部署所用密钥的随机性。

这套 Compose 是本地开发基线：Redis/MQ 未配置对外访问认证，Broker 使用异步刷盘和单节点，Grafana 有本地匿名 Viewer；Java Actuator health/prometheus 允许读取。
这些选择与回环地址的本地用途一致。公网部署时需要独立的网络、认证、TLS、监控访问限制和持久化/复制决策，不能仅改端口就宣称可生产部署。
AI HTTP 边界没有应用层认证，依靠本地网络隔离；其 Dockerfile 也包含开发测试依赖和 models 目录，不是已经优化过的生产镜像。
本次没有修改部署拓扑、共享卷、镜像依赖或 Broker 持久化语义。

## D. 实际修改与测试

| 修改原因 | 修改文件/内容 | 验证方式 |
| --- | --- | --- |
| 共享调度相互阻塞 | `AnalysisSchedulingConfig`、两个调度入口；启用清理时独立维护调度器 | 真实 Spring 定时调度，阻塞 producer，用 latch 证明恢复仍执行；清理调度器默认不存在。 |
| 集中失效和热点并发回源 | `CacheTtl`、两类缓存、`CourseService` | TTL 范围捕获；8 个请求被强制同时未命中，存在/不存在都只查一次；旧回填与更新失效竞态；真实事务提交/回滚。 |
| Outbox 增长 | `AnalysisOutboxCleanup`、Mapper、application、V16 | MySQL 验证 SENT 保留期、批大小、全部未发送状态和 sent_at 为空的记录不被删除，检查索引列顺序。 |
| 请求边界 | 9 个 Controller、全局异常、登录/注册 DTO | MVC 验证 0/负数/非数字/溢出、错误 JSON、密码字节数和过长登录；既有安全测试继续验证角色和归属。 |
| CSV 解析/导出安全 | parser、result service | 非闭合引号、重复表头、非法 UTF-8、多余列；多种公式起始字符和全部导出文本字段；既有解析、分页和鉴权测试。 |
| 压测工具准确性和清理范围 | summary、3 个脚本、离线回归测试 | Node 指标摘要测试；PowerShell 拦截 Docker，验证清理拒绝分支、转义前缀和事务 SQL。 |

### 验证记录

修复前原有 fast：212 项通过。新增边界回归在修复前复现 11 个失败，修复后纳入回归。
最终验证日期：2026-10-14。

| 检查 | 实际结果 |
| --- | --- |
| `./scripts/test.ps1 -Suite all` | **317 项，0 失败，0 错误，0 跳过**；真实 MySQL 8.4 / Redis 7.2 Testcontainers，RocketMQ/AI 使用 mock/fake。 |
| `node --test performance/k6/tests/summary.test.mjs` | 3 项通过；仅验证指标展示口径，不生成性能成绩。 |
| `./performance/k6/tests/cleanup.test.ps1` | 3 组离线场景通过：拒绝重试中任务、精确课程前缀与事务、精确用户前缀；Docker 调用全部拦截。 |
| 修改的 PowerShell 脚本及测试解析 | 4 个文件无语法错误。 |
| 两个 Compose 文件 `config --quiet` | 通过；使用单独的占位环境文件，没有读取根目录 `.env`。这是配置检查，不是服务健康/完整链路验证。 |
| 历史性能原始文件复算 | 12 份 A/B k6 JSON 的 p95/RPS 与历史报告一致；没有重新压测。 |
| `git diff --check` 和审计文档链接 | 通过；测试日志已放入忽略的构建目录。 |

完整 Java 日志保存在 `courseinsight-server/target/backend-audit-2026-10-14/audit-final-all.log`；
未采用的索引候选计划保存在 `courseinsight-server/target/backend-audit-query-plans.json`，两份计划均为 `ALL + using_filesort=true`。
这些是本地构建证据，不纳入正式源码。Docker 停止期间的失败、候选索引测试失败均未计为通过；没有跳过既有测试或放宽断言。

未验证：真实 RocketMQ + AI 端到端链路、Broker 宕机/磁盘故障、真实模型效果、多实例缓存一致性、当前修改后的性能指标以及远程 CI。
AI Python 源码未修改，本次没有运行其 pytest；相关判断限于 HTTP 边界、输入输出和部署代码审查。
V16 已在隔离测试库验证，未应用到业务数据库；Outbox 清理默认关闭，未执行真实数据清理。

### 可用于面试的解释

- “数据库保存待办，线程池只处理有界在途发送。调度器和发布池职责不同，因此把恢复调度独立出来。”
- “Outbox 保证提交后有可恢复的发送意图。Broker 接收后写库失败会重发，所以是 at-least-once；代次和执行 token 让重复消息不能覆盖新结果。”
- “缓存是可重建的展示数据。提交后删缓存是必要条件，但不是强一致协议；本次只收敛单实例详情回填竞态，失败仍有 TTL 上限。”
- “保留每请求读取用户，是为了下一请求按当前禁用状态和角色授权。没有吞吐证据前不把这项语义换成短 TTL 权限缓存。”
- “索引候选先看 EXPLAIN 和真实收益；这次候选没有改变计划，就没有保留。历史 p95 和任务吞吐必须连同机器、负载和 Stub 条件一起解释。”
