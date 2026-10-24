# 落库前 Phase B：Repository 契约与事务边界硬化

> 阶段：Database Pre-Migration Phase B（Repository Contract & Transaction Boundary Hardening）
> 前置：Phase A《pre-database-semantic-hardening.md》（机器 code、assigneeUserCode、provenance、Timeline 结构化、OffsetDateTime、设备主数据、Seeder/Simulator 隔离）已完成。
> 范围：**不接 openGauss、不执行 SQL/DDL、不引 JPA/MyBatis/Flyway/Liquibase、不加 `@Transactional`、不接 RocketMQ/Outbox、不改数据库设计、不改前端业务页面、不改状态机、不改 REST/WS Contract。**
> 目标：在全量 InMemory 实现下，把 9 个 Repository 改造成未来 JDBC Repository 可以直接实现的形状，并把跨聚合事务边界、事件发布边界、业务编号生成、边缘补传幂等权威固定下来。

---

## 1. Repository Design Rules（九条硬规则）

1. **Persistence-neutral**：业务 Service 只依赖 Repository 接口，不知道底层是 InMemory 还是 JDBC；接口不出现 `ConcurrentHashMap` / `CopyOnWriteArrayList` 等实现类型（反射契约测试 `RepositoryContractShapeTest` 兜底）。
2. **Load → Mutate → Explicit Save**：`findById/findAll/filter/page` 返回 detached 快照；修改返回对象后**不调用 `save` 不得改变仓储权威状态**。JDBC 不存在"拿到内部引用原地改即持久化"的语义。
3. **Copy-on-read / Copy-on-write**：InMemory 实现读、写两侧都做独立副本（copy 构造 / `copy()` / 显式聚合 copier），嵌套集合与子对象 deep-copy；**禁止用 Jackson 序列化做通用深拷贝**。
4. **Demo 维护方法不进正式契约**：`reset / clear / deleteById / findMutable / saveAll` 不出现在 9 个正式 Repository 接口中。Demo 灌种、演示数据清理、测试夹具走 `support.demo` 维护接口。
5. **Repository 只负责持久化协议**：不发 WebSocket、不做 DTO 展示转换、不推进业务状态机。
6. **Query 可自然翻译成 SQL**：业务写路径与可增长数据的读路径不允许 `findAll()` 后内存 `stream().filter()`；主数据（少量固定行）与 Projection 聚合允许保留 `findAll()`，见第 6 节。
7. **聚合整体 save**：`save(aggregate)` 保存一个完整聚合当前快照；Service 不持有子表 Repository（不拆 TimelineRepository / EvidenceRepository / VersionRepository）。未来 JDBC 实现内部负责主表 + 子表级联。
8. **分页/查询结果类型不属于 Repository 层**：`AlertPageResult` / `AiPageResult` 移到各模块 `model` 包；Controller / Service 不再 import Repository 包内类型。JSON 形状保持 `{page, pageSize, total, list}` 不变。
9. **Ops 日志 append-only**：`OpsEventLogRepository` 只有 `append` 与 `recent(limit, nodeId, level, type, from, to)`，没有 clear/delete。

---

## 2. Aggregate Save Semantics（聚合 save 语义）

| 聚合 | save 语义 | 嵌套内容（InMemory deep-copy / 未来 JDBC 级联子表） |
|---|---|---|
| **Alert**（`AlertRepository.save(DemoAlert)`） | 保存完整告警聚合快照 | timeline（按 `sequenceNo` 稳定排序）、evidence、treatment、linkage + linkageSteps、edgeReplay |
| **AI Event**（`AiEventRepository.save(DemoAiEvent)`） | 保存完整 AI 事件聚合快照 | timeline、boxes（检测框） |
| **Fence**（`FenceRepository.save(DemoFence)`） | 保存完整围栏聚合快照 | polygon（FencePoint 列表）、nodes（边缘同步态）、版本信息 |
| **Rule**（`RuleRepository.save(DemoRule)`） | 保存完整规则聚合快照 | versions + versionSnapshots、params、actions、areas、edgeNodes、relatedModules |
| **Edge Pending Event**（`EdgeEventQueueRepository.save(EdgePendingEvent)`） | 保存单条离线事件 | payloadSummary、localLinkage（actions 列表） |
| Personnel / Collision Device / Edge Node | 单实体快照 save | 少量嵌套值对象（坐标、Pair 状态等）随实体 copy |

未来 JDBC 实现内部决定主表 + 子表的写入顺序与级联策略（本阶段不预设 delete-all-children + insert-all）；Service 层代码不需要任何改动即可获得该语义，这正是 Phase B 契约改造的目的。

---

## 3. Copy-on-read / Copy-on-write 实现

- 每个 InMemory 实现内部以 `ConcurrentHashMap<String, 聚合副本>` 存储；`save` 时 `store.put(id, aggregate.copy())`，`findById/findAll/filter/page` 返回 `copy()`。
- 已深化 copy 的聚合：`DemoAlert`（timeline/evidence/treatment/linkage/steps/edgeReplay 全深拷贝）、`DemoAiEvent`（boxes/timeline）、`DemoFence`（polygon/nodes）、`DemoRule`（versions/params/edgeNodes/versionSnapshots）、`DemoCollisionDevice`、`DemoPersonnel`、`EdgePendingEvent`（含 `EdgeLocalLinkage.actions`；`transient boolean failNextReplay` 为进程内一次性测试/模拟标记，copy 时保留、不参与持久化语义）。
- **派生字段不进仓储副本**：`DemoPersonnel.activeAlertIds`、`slaRemainingSec`、Collision 装饰字段等是请求级 Projection，由 Service 在**返回副本**上 decorate；`PersonnelService.get()` 计算后不 save。
- 契约测试：`RepositoryCopySemanticsTest`（9 个测试）对 Alert/AI/Personnel/Fence/Collision/Rule/EdgeNode/EdgePendingEvent 逐一验证——改 detached 副本不落库、save 后才落库、嵌套 List 不共享、save 后再改外部对象不反向污染仓储、补传队列稳定排序与幂等键查询。

---

## 4. Removed Demo Methods（Demo 维护能力迁移）

| 原位置 | 原方法 | 新归属 |
|---|---|---|
| 9 个 Repository 接口 | `reset()` / `clear()` | `support.demo.DemoResettableStore#resetDemoData()`（重灌种子）、`support.demo.DemoClearableStore#clearDemoData()`（清空）；仅 InMemory 实现实现这些接口 |
| AlertRepository | `deleteById(String)` | `module.alert.demo.DemoAlertMaintenance`（`@Component`，构造注入 AlertRepository 并校验 InMemory 实现；`save / deleteById / deleteByIdPrefix`），仅 Simulator（受 DemoFeatureGuard 保护）与测试使用 |
| EdgeEventQueue / EdgeNode | `findMutable(...)` 及 Service 中的 `(InMemoryXxxRepository) cast` | **删除**；统一 `findById` 副本 → 改 → `save` |
| InMemory 构造器 | 构造器内隐式灌种 | **全部移除**；由 Phase A 的 `DemoSeedInitializer`（`app.demo.seed-enabled=true` 时）统一 `resetDemoData()`，Repository 构造器只负责存储 |
| EdgeReplayService | `processedEventIds / processedIdemKeys` 两个 JVM HashSet、`reset()` | **删除**；判重权威来自队列仓储（见第 9 节） |

正式 Service 中对 `InMemory*` 的 import / cast 编译层扫描为 **0**（仅 `support/demo/DemoSeedInitializer.java`、`module/alert/demo/DemoAlertMaintenance.java` 两个 Demo 支持类允许依赖具体实现）。

---

## 5. 九个 Repository 的最终契约

| Repository | 正式方法 | Demo 维护接口 |
|---|---|---|
| AlertRepository | `findAll / filter(AlertQuery) / page(AlertQuery) / findById / findOpenByDedupKey / save / count` | DemoClearableStore |
| AiEventRepository | `findAll / filter(AiEventQuery) / page(AiEventQuery) / findById / save / count` | DemoClearableStore |
| PersonnelRepository | `findAll / findById / save` | DemoResettableStore |
| FenceRepository | `findAll / findById / save / count` | DemoResettableStore |
| CollisionRepository | `findAll / findById / save` | DemoResettableStore |
| RuleRepository | `findAll / filter(RuleQuery) / findById / save / count` | DemoClearableStore |
| EdgeNodeRepository | `findAll / findById / save / count` | DemoResettableStore |
| EdgeEventQueueRepository | `save / findByEventId / findByIdempotencyKey / query(nodeId,status) / findPendingForReplay(nodeId) / findAll / pendingCount / count`；静态 `queueOrder()`（edgeOccurredAt nullsLast + eventId）、`replayable(status)`（PENDING/FAILED） | DemoClearableStore |
| OpsEventLogRepository | `append / recent(limit,nodeId,level,type,from,to)`（append-only，内存上限 300 条） | DemoClearableStore |

查询对象独立成文件：`module/alert/repository/AlertQuery.java`（keyword/risk/status/area/eventType/source/assignee/timeRange/from/to/page/pageSize，`of(...)` 兼容 level/type/owner 别名与"全部"）、`module/rule/repository/RuleQuery.java`、AI 模块 `AiEventQuery`。AlertQuery 的 `from/to`（ISO-8601）与 `timeRange`（近2小时/今日）已在 InMemory `matchTimeRange` 中真实生效，未来直接翻译为 `occurred_at BETWEEN`。

---

## 6. Query Contract Changes（findAll 内存过滤的处置）

**已替换为显式 Query / 专用方法：**
- Alert 列表/分页：`filter(AlertQuery) / page(AlertQuery)`，时间范围、状态、风险、区域、事件类型、来源、责任人、关键字均在仓储侧过滤；
- Alert 建单去重：`findOpenByDedupKey(dedupKey)`（未来翻译为 `WHERE dedup_key=? AND status_code IN (开放状态集合)`）；
- Rule 列表：`filter(RuleQuery)`；AI 列表：`filter(AiEventQuery)/page(...)`；
- Edge 补传：`findPendingForReplay(nodeId)` 由仓储按 `queueOrder()` 稳定排序返回（不再依赖 Java List 偶然顺序），`findByIdempotencyKey` 支撑补传判重。

**保留 `findAll()` 及理由（不构成迁移阻塞）：**
- Projection 聚合：`AlertService.metrics`、`AiEventService.metrics/facets`、`AnalyticsService`、Overview/Screen/Mobile 投影——本质是全量聚合，未来翻译为 SQL `GROUP BY` 聚合查询而非逐行加载，迁移时在 Projection 层重写，Repository 无需为此加业务方法；
- 少量固定主数据：Fence 列表（3 条）、Collision 设备（4 对）、Edge 节点（4 个）、Personnel（7 人）等 Demo 规模数据的装饰/列表；
- Personnel 服务对 Alert 的只读投影（人员详情页 activeAlertIds / alerts 列表）：请求级派生，未来改为按 `assignee_user_id / object_id` 的显式查询。

---

## 7. Cross-Aggregate Transaction Boundaries（跨聚合事务边界）

本阶段**不添加 `@Transactional`**（仍是 InMemory），但每个跨聚合流程都已有**单一顶层 workflow 方法**作为未来事务边界，且持久化阶段与事件发布阶段已通过 `LiveEventGate` 分离（第 10 节）。

| Use Case | 顶层 workflow（未来 @Transactional 候选） | 涉及 Repository | 聚合数 | 未来事务 | 事件 After Commit |
|---|---|---|---|---|---|
| AI confirm（确认违规建单） | `AiEventService.confirm` | AiEventRepository、AlertRepository | 2 | 是：校验 AI 状态→建 Alert（含 timeline）→回写 linkedAlertId + AI timeline | alert.new、ai.reviewed |
| AI assign / process / close | `AiEventService.assign/process/close`（assign 内部经 AlertService.assign） | AiEventRepository、AlertRepository | 2 | 是：AI 派单与 Alert 派单必须同成败 | alert.changed、ai.assigned 等 |
| Alert 处置闭环（confirm/assign/transfer/start/treatment/review/close/upgrade） | `AlertService` 各命令方法（单聚合 `mutate`） | AlertRepository | 1 | 是（聚合内主表+子表级联） | alert.changed/closed |
| Personnel 越界建单 | `PersonnelService.simulateAbnormal`（真实判定路径同构） | PersonnelRepository、FenceRepository（只读）、AlertRepository（经 AlertService） | 1 写 + 投影 | Alert 业务事实强事务；人员实时态允许最终一致 | person.*、alert.new |
| Collision 风险建单/同单升级 | `CollisionService.simulate`（severe→建单 / urgent→同单升级）、`linkage`、`takeover` | CollisionRepository、AlertRepository（经 AlertService） | 2 | Alert create/upgrade/linkage 强事务；PairState 为运行态不持久化 | collision.changed、alert.* |
| Rule publish / rollback | `RuleService.publish/rollback` | RuleRepository（+边缘同步态在聚合内） | 1 | 是（聚合级联 + 未来 rule_edge_sync 行） | rule.published、rule.sync.changed |
| Fence publish | `FenceService.publish` | FenceRepository（+nodes 同步态在聚合内） | 1 | 是（聚合级联 + 未来 fence_edge_sync 行） | fence.changed |
| Edge 单条补传 replay | `EdgeReplayService.replayOne`（`replayPending` 循环调用，单条为原子单元） | EdgeEventQueueRepository、AlertRepository（经 AlertService）、OpsEventLogRepository | 2~3 | 是：PENDING/SYNCING→建 Alert 或判 Duplicate→回写 linkedAlertId→SYNCED/DUPLICATE + Ops 日志；失败→FAILED/retryCount+1 | ops.*、alert.*（补传场景） |
| Edge 批量恢复 | `EdgeOpsService` 恢复流程（RecoveryPhase 为运行态，不建表） | EdgeNodeRepository、EdgeEventQueueRepository、OpsEventLogRepository、RuleRepository（只读对账） | 3~4 | 单节点恢复整体允许逐条事务；RecoveryPhase 不持久化 | ops.node.* |

编排方式结论：**现有 Service 方法本身已是自然的顶层 workflow，不新增 CommandBus/Mediator/Saga/UnitOfWork/CQRS 框架，也暂不新建 Application Service**；JDBC 阶段直接在上述方法上加 `@Transactional`（或薄应用服务包装），并把 gate flush 点替换为事务提交后发布。

---

## 8. Number Generator（业务编号生成抽象）

| 编号 | 接口（各模块 service 包） | Demo 实现（support.demo） | 格式（与历史完全一致） |
|---|---|---|---|
| Alert | `AlertNumberGenerator` | `DemoAlertNumberGenerator(AlertRepository, Clock)` | `ALM-yyyyMMdd-NNN`，按当日同前缀数量 +1 |
| AI Event | `AiEventNumberGenerator` | `DemoAiEventNumberGenerator(AiEventRepository, Clock)` | `AI-E-yyyyMMdd-NNN`（上海时区自然日） |
| Rule | `RuleNumberGenerator#nextRuleNumber(category)` | `DemoRuleNumberGenerator(RuleRepository)` | `RULE-<域前缀>-NNN`：人员安全 RULE-PER / 设备安全 RULE-DEV / AI识别 RULE-AI / 告警策略 RULE-ALM / 联动策略 RULE-LNK / 通知策略 RULE-NTF |
| Fence | `FenceNumberGenerator` | `DemoFenceNumberGenerator(FenceRepository)` | `FENCE-%03d`（count+1，占用顺延） |

- Service 不再自持 `AtomicInteger/Map` 计数；`EDGE-EVT-*` 时间戳序号是 Simulator-only，不抽象。
- Demo 实现仍是单 JVM 语义，**多实例唯一性明确留待 DB 阶段**（日序列 / 号段 / UUIDv7 + 可读编号；DDL 已预留业务编号 UNIQUE）。测试：`BusinessNumberGeneratorTest`（4 类格式与递增）。

---

## 9. Edge Idempotency（补传判重仓储化）

- **删除** `EdgeReplayService` 内 `processedEventIds / processedIdemKeys` 进程内 HashSet 与 `reset()`。判重权威：
  1. 事件自身状态已是 `SYNCED/DUPLICATE` → DUPLICATE（回指原 `linkedAlertId`）；
  2. `findByIdempotencyKey(key)` 命中另一条**已消费**事件 → DUPLICATE（回指该事件的告警）；
  3. 未命中 → 建单并回写 `linkedAlertId`，状态 SYNCED。
- 同一 eventId / 同一 idempotencyKey 在**重建 Service（模拟重启）后**判重依然有效——`EdgeReplayIdempotencyTest` 专门验证。
- `failNextReplay`（一次性、transient）模拟平台不可用：首次 → FAILED + `retryCount=1` + `lastError=PLATFORM_TEMPORARILY_UNAVAILABLE`；重试 → SYNCED。
- 同 key 不同 payload 的 CONFLICT 语义本阶段**明确不实现**（Demo 无法可靠比较），留待 openGauss `UNIQUE(idempotency_key)` + 请求哈希落库阶段处理，记入数据库 findings。
- 业务事件去重（dedupKey）与 HTTP 请求幂等（Idempotency-Key / IdempotencyService）仍为两套机制，不混用；F-17（仅 Alert/AI 接入 HTTP 幂等）本阶段不扩范围。

---

## 10. Event Publish Boundary（事件发布边界）

- 新增 `common/realtime.LiveEventGate`（`@Component`，ThreadLocal 缓冲）：
  - `buffer(Supplier/Runnable)`：区间内所有 `emit` 入队；action 正常返回 → flush（先移除 ThreadLocal，再按入队顺序逐条执行，单条广播异常只 warn 不阻断其余）；action 抛异常 → discard 全部缓冲事件并原样抛出。
  - 未缓冲时 `emit` 立即执行（函数式端口 `AlertChangeNotifier.NOOP` 等单测行为不变）。
- 已接入：`DomainLivePublisher`（构造增加 gate；OpsLiveNotifier 经其自动受益）、`WebSocketAlertChangeNotifier`、`WebSocketAiChangeNotifier`；跨聚合/多步写 workflow 全部包在 gate 内：`AiEventService.mutate`（confirm/assign/process/close/falsePositive/uncertain）、`CollisionService.simulate/linkage/takeover`、`PersonnelService.simulateAbnormal`、`EdgeReplayService.replayOne/markDuplicate`。
- 固定顺序：**validate → mutate 聚合副本 → 全部 repository.save → flush LiveEvent**。`AiConfirmEventOrderTest` 用记录型仓储包装器 + lambda 通知器断言：第一条广播发出后 save 计数为 0，alert.new 与 ai.reviewed 均在两聚合落库后发出。
- Repository save 异常必须传播（不吞）；Notifier 异常 best-effort。
- **JDBC 阶段唯一替换点**：把各 workflow 末尾的 gate flush 换成 `TransactionSynchronization.afterCommit`（或 Outbox 投递），业务状态机与 Service 结构不需要再改。本阶段不实现 Outbox、不接 RocketMQ。

---

## 11. Future JDBC Mapping Notes（给 JDBC 实现的备注）

1. 接口方法可直接映射：`save` = upsert 聚合（主表 + 子表级联，建议事务内）；`findById`=主键/业务键查询 + 子表批量装载重建聚合；`filter/page`=动态 WHERE + LIMIT/OFFSET（或 keyset）；`findOpenByDedupKey`、`findByIdempotencyKey`=唯一/索引查询；`append`=INSERT；`recent`=ORDER BY occurred_at DESC LIMIT。
2. JDBC Repository 内部做 Row/Entity ↔ Domain 聚合映射；**Domain POJO 不得加 `@Table/@Column/@Entity`**（本阶段已核查模型无持久化注解）。
3. `count()` 映射为 `SELECT count(*)`；Projection（metrics/facets/analytics/overview/screen/mobile）未来直接写 SQL 聚合，不复用 `findAll()`。
4. 乐观锁：Alert/Rule/Fence 聚合未来加 `lock_version`，`save` 时带版本条件更新；本阶段不模拟 CAS，InMemory 继续 synchronized。
5. Demo 维护接口（reset/clear/delete）**JDBC 实现不得实现**；生产 profile 下 DemoSeedInitializer/Simulator 本就不装配（Phase A 已完成开关隔离）。
6. 时间字段统一 `OffsetDateTime` ↔ `timestamp with time zone`（Phase A 已收口）；Edge 事件区分 edgeOccurredAt / receivedAt / serverReceivedAt / syncedAt / lastRetryAt。

### Repository 迁移评估表

| Repository | Contract Ready | InMemory Ready | JDBC Complexity | Remaining Work |
|---|---|---|---|---|
| AlertRepository | 是 | 是（copy 语义 + 契约测试） | **HIGH**：主表 + timeline/evidence/treatment/linkage/steps 多表级联重建；dedupKey/开放状态查询；分页 | 子表装载顺序、lock_version、alert_no 日序列 |
| AiEventRepository | 是 | 是 | **MEDIUM**：主表 + timeline/boxes（boxes 可 JSONB 或子表） | linked_alert_id 软引用、camera 关联 |
| PersonnelRepository | 是 | 是 | **LOW~MEDIUM**：单表 + track 时序边界（track 不无限写 openGauss） | activeAlertIds 保持派生不落库；track 走 openGemini 边界 |
| FenceRepository | 是 | 是 | **MEDIUM**：主表 + version + edge_sync；polygon JSONB | team_scope code 化（Phase A findings 已记） |
| CollisionRepository | 是 | 是 | **LOW~MEDIUM**：设备主数据表；Pair 运行态/高频测量不长期落库 | latestAlertId 保持派生镜像（Q2 结论）；测量进 openGemini |
| RuleRepository | 是 | 是 | **MEDIUM~HIGH**：主表 + version + edge_sync；params/actions JSONB；versionSnapshots | rollback 语义、rule_code |
| EdgeNodeRepository | 是 | 是 | **LOW**：单表（最新状态） | CPU/内存等高频指标不进主表 |
| EdgeEventQueueRepository | 是 | 是（稳定排序 + 幂等查询 + 契约测试） | **MEDIUM**：pending 表 + idempotency_key UNIQUE + 状态索引 | 同键异 payload CONFLICT、payload JSONB、重试调度 |
| OpsEventLogRepository | 是 | 是（append-only） | **LOW**：单表 append + 分页查询 | 保留周期/归档策略 |

---

## 12. 本阶段验证

- 后端：`mvn clean test`（项目自带 .tools：JDK 17 + Maven 3.9.16），**183 tests / 0 failures / 0 errors**（Phase A 基线 158 + Phase B 新增 25，分布于 6 个测试类：LiveEventGateTest 4、RepositoryCopySemanticsTest 9、RepositoryContractShapeTest 3、EdgeReplayIdempotencyTest 4、BusinessNumberGeneratorTest 4、AiConfirmEventOrderTest 1）。
- 编译层扫描：`findMutable` 生产运行代码 0（仅 3 处 javadoc 文字）；service/controller 对 `InMemory*` 的 import/cast 0；正式 Repository 接口 reset/clear/deleteById/findMutable/saveAll 0（反射测试断言）。
- 前端：零改动；typecheck / vitest / build 全部通过（结果见 Phase B 汇报）。
- 未连接 openGauss、未执行 SQL、未改 DDL/数据库设计文档、未引 ORM/Migration 库、未加 `@Transactional`、未接 RocketMQ/Outbox、未改状态机与 API/WS Contract。
