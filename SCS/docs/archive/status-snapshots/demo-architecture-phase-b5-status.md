# 架构审计 Finding 状态：Phase B.5（最终数据库设计前关键缺陷修复）

> 本文记录 Phase B.5 对影响数据库蓝图与事务语义的 5 项关键缺陷的处置状态。  
> 前序状态见《demo-architecture-phase-b-status.md》与《demo-architecture-fix-status.md》。  
> 状态口径：RESOLVED（本阶段代码层面闭环）/ PARTIAL（Demo 层闭环，正式能力留待 DB/部署阶段）/ OPEN（本阶段明确不处理）。

---

## 一、Phase B.5 缺陷处置总览

| 议题编号 / Finding | 议题描述 | Phase B.5 前状态 | Phase B.5 后状态 | 处置位置与落地代码 |
|---|---|---|---|---|
| **B5-01** | Alert Escalation 生命周期死锁 | OPEN | **RESOLVED** | `AlertService.escalate` 保持主状态正交，记录 `previousRiskLevelCode`，废弃 `AlertStatuses.ESCALATED`，前端 `AlertActionBar` 加防重 |
| **B5-02** | Collision stale activeAlertId / 旧告警残留 | OPEN | **RESOLVED** | `CollisionService.ensureCollisionAlert` 权威调用 `findOpenByDedupKey`，失效时置空 `pair.activeAlertId` 并重开告警，`resetPair` 显式置空 |
| **B5-03** | LiveEventGate 不支持嵌套可重入 | OPEN | **RESOLVED** | `LiveEventGate` 引入 `Context`（depth + failed 状态标记），最外层退出时统一 flush，内层失败传播至根上下文 discard |
| **B5-04** | Rule ACTIVE 编辑导致状态错误降级 | OPEN | **RESOLVED** | `RuleService.update` 感知 ACTIVE 状态，非 `asNewVersion` 时维持 ACTIVE 与 edgeNodes synced，生成新版本草稿；`publish` 放行 ACTIVE 规则 |
| **B5-05** | Demo Business Number Generator 静默覆盖风险 | OPEN | **PARTIAL** | 4 个生成器由 `count() + 1` 改为扫描现有最大数字后缀 `maxSeq + 1` 并加 `findById` 探测循环；多实例序列留 DB |

---

## 二、逐项处置详情

### B5-01 Alert Escalation 语义修正 — RESOLVED

- **核心变更**：
  - 彻底解耦“业务处置阶段状态（Status）”与“风险等级（Risk Level）”。
  - 升级操作仅升级 `alert.riskCode`（如 WARNING → SEVERE / URGENT），同步更新 `previousRiskLevelCode`；
  - 保持 `alert.statusCode` 不变（待认领/待处置/处理中等），后续流程流转通畅；
  - 增加防重校验：当前已为 URGENT 时拒绝再次升级（抛 409 STATE_CONFLICT）；
  - 测试覆盖：`AlertEscalationWorkflowTest` 覆盖待认领升级、待处置升级、处理中升级、后续处置流转、终态防重等 5 个用例。

### B5-02 Collision stale activeAlertId 清理 — RESOLVED

- **核心变更**：
  - 建立“以告警中心未关闭状态为单一事实源”的原则；
  - 碰撞检测入口首先查询 `alertService.findOpenByDedupKey("COLLISION:" + pair.deviceA + ":" + pair.deviceB)`；
  - 若已关闭（null），清理 `pair.activeAlertId = null` 并创建全新告警；若存在未关闭告警，对齐 `pair.activeAlertId` 并复用；
  - `resetPair` 显式重置 `activeAlertId = null`；
  - 测试覆盖：`CollisionAlertLifecycleTest` 验证告警关闭后复发告警正常生成、重置对状态清理。

### B5-03 LiveEventGate 嵌套重入与异常隔离 — RESOLVED

- **核心变更**：
  - `LiveEventGate` 将 ThreadLocal 队列重构为 `Context` 实例，内含 `depth` 计数与 `failed` 标记；
  - 外层与内层嵌套调用共享同一个根队列，广播仅在 `depth <= 0` 的最外层完成退出时触发；
  - 任一内层调用抛出异常调用 `discard()` 时，标记 `ctx.failed = true` 并清空队列；
  - 根上下文退出时若检测到 `failed == true`，坚决不发送任何事件；
  - 测试覆盖：`LiveEventGateReentrancyTest` 覆盖 6 类重入、深度嵌套、单条异常隔离、外层 catch 异常等场景。

### B5-04 Rule ACTIVE 编辑保护 — RESOLVED

- **核心变更**：
  - 修复已生效规则被普通编辑后主状态直接被降级为 DRAFT 的问题；
  - 普通编辑已生效规则时，主状态保持 ACTIVE，生效时间 `effectiveAt` 保留，边缘节点执行版本和 synced 状态不变；
  - 系统自动在 `versions` 头部追加 `bumpMinor(r.version)`（如 `v2.4` → `v2.5`）的新版本草稿记录，并对新参数执行快照；
  - `publish` 接口放行处于 ACTIVE 状态但含有待发布新版本草稿的规则；
  - 测试覆盖：`RuleActiveEditSemanticsTest` 5 例用例全部转绿。

### B5-05 业务编号生成器碰撞防范 — PARTIAL

- **已完成（Demo 层闭环）**：
  - 消除基于 `count() + 1` 计算编号产生的间隙冲突与覆盖隐患；
  - `DemoAlertNumberGenerator`, `DemoAiEventNumberGenerator`, `DemoRuleNumberGenerator`, `DemoFenceNumberGenerator` 全部改为扫描同前缀实体的数字后缀并取 `max + 1`；
  - 增加基于 `repository.findById(candidate).isPresent()` 的冲突检测递增循环；
  - 测试覆盖：`BusinessNumberCollisionSafetyTest` 4 例用例全部转绿。
- **留待 DB 阶段**：
  - 单 JVM 内存扫描在分布式多实例环境下不适用；
  - 最终实现由 openGauss 数据库序列（`CREATE SEQUENCE`）或发号器服务接管，数据表业务编号字段设置 `UNIQUE` 唯一约束。

---

## 三、未处理项（按任务约束保持 OPEN）

以下 9 项非数据库阻塞型技术债维持原有 OPEN 状态：

1. **LiveWebSocketTest WebSocketSessionRegistry 内存锁粒度**（OPEN）：单 JVM 内存并发优化。
2. **IdempotencyService race condition**（OPEN）：单机 ConcurrentHashMap 竞争，留待 Redis/Kvrocks 分布式锁/原子操作。
3. **AI time bucket 聚合口径**（OPEN）：时序与前端展示细节。
4. **AI simulate replay 连续重放**（OPEN）：模拟器控制。
5. **HTTP Idempotency Header 广度覆盖**（OPEN）：留待网关或全链路拦截器统一补齐。
6. **Collision field contract 冗余字段**（OPEN）：已定性为只读派生字段，不影响核心表。
7. **OperationsView 前端 mock**（OPEN）：前端运维视图 mock 数据。
8. **Personnel live 实时连接稳定性**（OPEN）：演示长连接。
9. **多实例全局唯一编号**（OPEN）：数据库 Sequence 阶段解决。
