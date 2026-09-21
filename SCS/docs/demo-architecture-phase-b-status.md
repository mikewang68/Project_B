# 架构审计 Finding 状态：Phase B（Repository 契约与事务边界硬化）

> 本文只记录 Phase B 对《demo-architecture-review.md》既有 Finding 的处置状态，**不修改原审计事实**。
> Phase A 状态见《demo-architecture-fix-status.md》。
> 状态口径：RESOLVED（本阶段代码层面闭环）/ PARTIAL（Demo 层闭环，正式能力留待 DB/部署阶段）/ OPEN（本阶段明确不处理）。

## 总览

| Finding / 议题 | Phase B 前状态 | Phase B 后状态 | 处置位置 |
|---|---|---|---|
| F-03 跨聚合写无事务/编排边界、事件先于提交 | OPEN | **PARTIAL** | 单一顶层 workflow + LiveEventGate 先 save 后发布；真正 `@Transactional` / AFTER_COMMIT / Outbox 留 JDBC 阶段 |
| F-05 Repository 契约泄漏 InMemory 形态 | OPEN（Phase A 未处理） | **RESOLVED**（契约层） | 9 仓储接口/impl 重写、Demo 方法下沉、copy 语义、分页类型外移、Query 收口 |
| F-06 Edge 补传去重 JVM-local HashSet | OPEN | **PARTIAL** | 判重权威改为队列仓储（重启有效）；`UNIQUE(idempotency_key)` 与同键异 payload CONFLICT 留 openGauss |
| F-16 业务编号单 JVM 计数 + Edge 去重集合 | OPEN | **PARTIAL** | 4 个编号 Generator 接口 + Demo 实现，Service 不再自持计数；多实例唯一序列留 DB；Edge 去重同 F-06 |
| Repository 返回内部可变引用（F-05 子项） | OPEN | **RESOLVED**（InMemory 契约） | copy-on-read/write + `RepositoryCopySemanticsTest` 9 例 |
| Service 依赖 InMemory 具体类 / cast / findMutable（F-05 子项） | OPEN | **RESOLVED** | EdgeOpsService / EdgeReplayService 字段全部接口化；编译层扫描为 0 |
| Demo 方法（reset/clear/deleteById）污染正式契约（F-05 子项） | OPEN | **RESOLVED** | DemoResettableStore / DemoClearableStore / DemoAlertMaintenance；反射契约测试 |
| 事件发布边界（F-03 子项） | OPEN | **PARTIAL** | LiveEventGate 缓冲/flush/discard；AFTER_COMMIT 替换点已明确，未接事务/Outbox |
| F-04 Analytics 直写 AlertRepository（Phase A 前遗留） | Phase A 已部分处理 | **RESOLVED** | AnalyticsService 经 DemoAlertMaintenance（受 simulator guard）注入/回滚 surge 演示告警，不再直写正式仓储 |
| F-13 前端 OperationsView mock | OPEN | **OPEN** | 本阶段明确不处理（Phase B 只做后端 Repository 契约） |
| F-17 HTTP 写接口幂等覆盖不全 | OPEN | **OPEN** | 本阶段不扩范围；已接入的 Alert/AI 幂等行为未被重构破坏 |

## F-03 跨聚合事务 / 事件边界 — PARTIAL

**已完成（Demo 层闭环）：**
- AI confirm / assign / process / close、Collision simulate/linkage/takeover、Personnel simulateAbnormal、Edge replayOne 均有**单一顶层 workflow 方法**，Controller 不编排多 Service；
- `LiveEventGate`（ThreadLocal 缓冲）保证一个 workflow 内"全部 save 完成 → 统一 flush 广播"；action 抛异常则 discard 全部事件；单条广播失败不阻断其余；
- `AiConfirmEventOrderTest` 断言第一条广播之后 save 次数为 0；`LiveEventGateTest` 4 例覆盖立即执行/保序/异常 discard/单条失败隔离。

**留待 DB 阶段（不伪装已解决）：**
- 没有真正的数据库事务（InMemory 无法 rollback）；JDBC 阶段在既有 workflow 方法上加 `@Transactional`，gate flush 替换为 `afterCommit` 或 Outbox；
- Outbox / RocketMQ fan-out 未实现（F-09 多实例 WS 广播仍 OPEN）。

## F-05 Repository 契约 — RESOLVED（契约形态）

- 9 个正式接口均无 reset/clear/delete/deleteById/findMutable/saveAll，签名无 `java.util.concurrent` 类型；OpsEventLog 仅 append/recent（`RepositoryContractShapeTest` 反射断言）。
- find/list/filter/page 返回 detached 副本；复杂聚合（Alert/AI/Fence/Rule/EdgePendingEvent）嵌套集合与子对象 deep-copy；save 为 copy-on-write（`RepositoryCopySemanticsTest` 9 例）。
- EdgeOpsService、EdgeReplayService 不再出现 `InMemory*` 字段、`instanceof` cast 与 findMutable；service/controller 包对 InMemory 实现的 import 编译层扫描为 0（仅 support.demo 两个允许的例外类）。
- 分页结果类型外移：`AlertPageResult`、`AiPageResult`（module model 包，JSON 形状不变）；Query 对象独立（AlertQuery/RuleQuery/AiEventQuery），AlertQuery.from/to/timeRange 已真实过滤。
- 聚合整体 save 语义明确（见《pre-database-repository-hardening.md》第 2 节），未拆子表 Repository。
- **残留（不阻塞）**：Projection/metrics 仍读 `findAll()` 做内存聚合——这是刻意保留，JDBC 阶段改写为 SQL 聚合，不算契约缺陷。

## F-06 Edge 补传幂等 — PARTIAL

- 删除 EdgeReplayService 的 processedEventIds / processedIdemKeys HashSet 与 reset()；判重权威来自 `EdgeEventQueueRepository`：事件自身 SYNCED/DUPLICATE 状态 + `findByIdempotencyKey` 跨事件命中；服务重建后判重仍有效（`EdgeReplayIdempotencyTest` 4 例，含同 eventId、同 key 跨事件、重启、失败重试）。
- `findPendingForReplay` 由仓储按 edgeOccurredAt(nullsLast)+eventId 稳定排序；replayable 仅 PENDING/FAILED。
- **留待 DB**：同 key 不同 payload 的 CONFLICT 判定、数据库 `UNIQUE(idempotency_key)` 约束、跨实例并发消费。

## F-16 业务编号 — PARTIAL

- Alert / AI / Rule / Fence 四类编号抽为接口（各模块 service 包）+ Demo 实现（support.demo），格式与历史完全一致并有测试（`BusinessNumberGeneratorTest` 4 例）；Service 不再自持 AtomicInteger/Map。
- **留待 DB**：多实例唯一编号（日序列 / 号段 / UUIDv7 + 可读编号），DDL 已预留业务编号 UNIQUE。EDGE-EVT-* 为 Simulator-only 序号，不抽象。

## 未处理项（按任务约束保持 OPEN）

- F-13 OperationsView 前端 mock：Phase B 不动前端业务页面。
- F-17 Rule/Fence/Personnel/Collision/Ops 写接口 HTTP 幂等扩面：本阶段不扩范围。
- Repository 内部 synchronized / id.intern() 等单 JVM 锁：随 JDBC 阶段 lock_version + 唯一约束 + 事务替换，本阶段不模拟 CAS。
- 高频时序（人员轨迹、Collision 测量、Edge 指标）openGemini 边界、F-08 规则运行时化、F-09 MQ fan-out、IAM：均为后续阶段事项，本阶段不触碰。
