# 数据库前置阶段 Phase B.5：关键缺陷修复与语义加固报告

> 对应阶段：Database Pre-Migration Phase B.5（Critical Bug Fix Before Final Database Design）  
> 基线状态：Phase A 与 Phase B 已完成，Backend 仍为 InMemory Repository，Repository Contract JDBC-ready。未连接 openGauss，未执行任何 SQL / DDL，未引入 JPA/MyBatis/Flyway/@Transactional/Outbox。  
> 修复范围：**严格限定于直接影响数据库语义、事务模型、事件一致性和编号安全的 5 项关键缺陷**。其余非阻塞技术债严格保持现状。

---

## 一、执行总览

在完成 Phase B（仓储契约标准化与事务边界收口）后，代码审查识别出 5 项若不提前修复将直接污染最终 openGauss 数据库物理建模（表字段、状态机约束、外键设计、版本关系、唯一索引）的关键缺陷。

本阶段严格遵循 **“先写复现测试验证红灯（RED）→ 精确修复代码 → 验证测试转绿（GREEN）→ 全量回归验证”** 的流程完成修复。

| 缺陷编号 | 缺陷名称 | 严重等级 | 修复状态 | 复现测试文件 | 影响数据库设计模块 |
|---|---|---|---|---|---|
| **B5-01** | Alert Escalation 生命周期死锁 | CRITICAL | **RESOLVED** | `AlertEscalationWorkflowTest.java` (5例) | `safety_alert` 状态机枚举、风险等级跃迁、时间线事件 |
| **B5-02** | Collision stale activeAlertId / 旧告警残留 | CRITICAL | **RESOLVED** | `CollisionAlertLifecycleTest.java` (2例) | `collision_pair.active_alert_id` 弱引用定义、dedup_key 检索索引 |
| **B5-03** | LiveEventGate 不支持嵌套可重入 | HIGH | **RESOLVED** | `LiveEventGateReentrancyTest.java` (6例) | `@Transactional` 边界、AFTER_COMMIT 钩子、Outbox 投递语义 |
| **B5-04** | Rule ACTIVE 编辑导致状态错误降级 | CRITICAL | **RESOLVED** | `RuleActiveEditSemanticsTest.java` (5例) | `safety_rule` 主表 vs `safety_rule_version` 子表关系与字段 |
| **B5-05** | Demo Business Number Generator 静默覆盖风险 | HIGH | **PARTIAL (Demo闭环)** | `BusinessNumberCollisionSafetyTest.java` (4例) | 业务编号 `UNIQUE` 约束、openGauss 日序列/号段设计 |

---

## 二、五项关键修复详细报告

### 1. B5-01: Alert Escalation 生命周期死锁

- **问题现象与根因**：  
  在原代码中，告警升级接口 `escalate` 将告警状态 `alert.statusCode` 篡改为 `"ESCALATED"`（已升级）。然而在标准处置生命周期中，后续流转方法（`start`, `treatment`, `close` 等）均要求特定业务状态（如 `PENDING_PROCESS` 或 `PROCESSING`）。当状态被强制变为 `"ESCALATED"` 后，状态机前置校验（`requireStatus`）全部报 409 冲突，导致告警无法开始处理、无法提交处置、无法关闭，陷入死锁。此外，前端处置工具条（`AlertActionBar.vue`）对已紧急告警仍展示升级按钮，无冲突防重保护。
- **复现测试**：  
  创建 `AlertEscalationWorkflowTest.java`，构造待认领、待处置、处理中三种状态下的告警执行升级，并验证后续处置直至关闭。原代码在 `escalate` 后的下一步直接报 `STATE_CONFLICT`。
- **修复方案**：
  1. **明确升级的正交语义**：升级是**风险等级（Risk Level）的提升**（如 WARNING/SEVERE → URGENT），而不是主业务生命周期状态（Status）。升级后，主状态 `statusCode` 严格保持原状。
  2. **记录升级上下文**：在 `DemoAlert` 中新增 `previousRiskLevelCode` 和 `upgradedFromCode` 字段，深拷贝方法同步更新。
  3. **废弃旧状态枚举**：将 `AlertStatuses.ESCALATED` 标记为 `@Deprecated`，禁止作为主流程状态使用。
  4. **防重与终态保护**：在 `AlertService.escalate` 中校验若已为 `URGENT` 或目标等级不高于当前等级，抛出 409 CONFLICT。
  5. **种子与集成测试订正**：`AlertDemoSeeder` 中的 `a10` 种子修正为 `statusCode = PROCESSING`, `previousRiskLevelCode = WARNING`；修正 `AlertModuleIntegrationTest` 的断言。
  6. **前端保护**：在 `AlertActionBar.vue` 中对 `alert.risk === '紧急'` 隐藏升级操作，且主状态判定去掉 `已升级` 分支。
- **验证结果**：`AlertEscalationWorkflowTest` 5 例全绿，`AlertModuleIntegrationTest` 全绿。

---

### 2. B5-02: Collision stale activeAlertId / 旧告警残留

- **问题现象与根因**：  
  设备防碰撞对（`DemoCollisionPair`）内维护了一个 `activeAlertId`。当上一条告警在告警中心被处置并关闭（CLOSED）后，`pair.activeAlertId` 仍然保留了旧告警 ID。当再次触发预警时，`CollisionService.ensureCollisionAlert` 仅检查 `pair.activeAlertId != null`，便直接跳过创建新告警，甚至继续在已关闭告警上做升级，导致新风险事件完全丢失。在 `resetPair` 时也未清空 `activeAlertId`。
- **复现测试**：  
  创建 `CollisionAlertLifecycleTest.java`，模拟碰撞对产生告警 → 告警中心全生命周期处置并关闭 → 同一碰撞对再次发生风险。原代码未能生成新告警。
- **修复方案**：
  1. **权威去重检索**：以 `AlertService.findOpenByDedupKey(dedupKey(pair))` 作为唯一权威判定标准（`dedupKey` 格式为 `COLLISION:{devA}:{devB}`）。
  2. **状态感知与自动修复**：若存在未关闭告警，则更新 `pair.activeAlertId = open.id` 并复用；若不存在未关闭告警（即已关闭或尚未创建），立即将 `pair.activeAlertId` 置 `null` 并创建新告警。
  3. **重置对状态清理**：在 `resetPair` 时显式重置 `pair.activeAlertId = null`。
- **验证结果**：`CollisionAlertLifecycleTest` 2 例全绿，`CollisionModuleIntegrationTest` 全绿。

---

### 3. B5-03: LiveEventGate 不支持嵌套可重入

- **问题现象与根因**：  
  Phase B 引入的 `LiveEventGate` 用于确保“先全部 save、后统一发布广播”。原实现直接使用 `ThreadLocal<List<Runnable>> pending`。当存在嵌套 workflow 调用（如外层业务方法调用了内部封装了 `buffer` 的私有方法或子服务）时，内层 `flush()` 直接调用 `pending.remove()` 并执行广播，导致：
  - 内层提前执行了广播，破坏了外层的原子缓冲屏障；
  - 外层后续的 `emit` 失去缓冲（变成了未缓冲直接发送）；
  - 若内层发生异常，无法正确向上传播丢弃（discard）标记，外层捕获异常后可能将半流程事件错误广播。
- **复现测试**：  
  创建 `LiveEventGateReentrancyTest.java`，覆盖单层缓冲、内外层嵌套保序、内层异常根上下文级联丢弃、外层 catch 内层异常依然不得发送、三层深度嵌套、广播单条故障隔离等 6 个场景。原代码在嵌套场景直接失败。
- **修复方案**：
  1. **引入调用上下文对象**：
     ```java
     private static class Context {
         int depth = 0;
         boolean failed = false;
         final List<Runnable> queue = new ArrayList<>();
     }
     ```
  2. **基于深度的重入控制**：
     - `begin()`：`ctx.depth++`；
     - `flush()`：`ctx.depth--`。仅当 `ctx.depth <= 0` 时移除 ThreadLocal，且检查 `if (!ctx.failed)` 才按顺序执行广播；
     - `discard()`：标记 `ctx.failed = true; ctx.queue.clear(); ctx.depth--`。
  3. **异常污染防护**：若任何一层调用发生异常触发 `discard()`，整根链路被标记为 `failed`，外层即使 catch 异常也绝不会 flush 任何遗留事件。
- **验证结果**：`LiveEventGateReentrancyTest` (6例) 与 `LiveEventGateTest` (4例) 共 10 例全部通过。

---

### 4. B5-04: Rule ACTIVE 编辑导致状态错误降级

- **问题现象与根因**：  
  在原 `RuleService.update` 中，只要调用了更新接口（无论是否显式传递 `asNewVersion`），均无条件执行：
  ```java
  r.statusCode = bool(req.submitReview()) ? RuleStatuses.REVIEW : RuleStatuses.DRAFT;
  r.effectiveAt = "—";
  ```
  这导致在线正在运行的已生效规则（`ACTIVE`）只要被修改了名称、说明或阈值，主状态立即被强行降级为草稿（`DRAFT`），生效时间被清空，边缘节点同步状态失效，严重违反生产安全卡控原则。
- **复现测试**：  
  创建 `RuleActiveEditSemanticsTest.java`，验证已生效规则（如 `RULE-DEV-003 v2.4`）在普通修改时：
  - 主状态必须保持 `ACTIVE`（已生效）；
  - `effectiveAt` 不得被篡改；
  - 边缘节点必须继续执行当前 `v2.4` 且保持 `synced`；
  - 新版本草稿（`v2.5`）被妥善记录在版本列表中；
  - 调用 `publish` 后能顺利将生效版本升级至 `v2.5` 并同步边缘节点；
  - `DRAFT` 规则修改仍保持 `DRAFT`。
- **修复方案**：
  1. **状态感知分支**：在 `RuleService.update` 中区分 `isActive = RuleStatuses.ACTIVE.equals(r.statusCode)`。
  2. **在生效规则上创建版本草稿**：当 `isActive && !asNew` 时：
     - 不修改 `r.statusCode`，保持 `ACTIVE`；
     - 不修改 `r.effectiveAt`，保留原生效时间戳；
     - 边缘节点 `r.edgeNodes` 保持旧版本的 `EDGE_SYNCED` 状态；
     - 计算新版本号 `newVersion = bumpMinor(r.version)`（如 `v2.4` → `v2.5`），将其作为新草稿推入 `r.versions`，并做参数快照 `snapshot(r, newVersion)`。
  3. **发布流程放行**：`RuleService.publish` 放行当前为 `ACTIVE` 且版本列表包含新版本的规则，顺利切换 `r.version` 至新版本并下发边缘节点。
- **验证结果**：`RuleActiveEditSemanticsTest` (5例) 与 `RuleModuleIntegrationTest` (12例) 共 17 例全部通过。

---

### 5. B5-05: Demo Business Number Generator 静默覆盖风险

- **问题现象与根因**：  
  Phase B 抽象出的 4 个编号生成器（`DemoAlertNumberGenerator`, `DemoAiEventNumberGenerator`, `DemoRuleNumberGenerator`, `DemoFenceNumberGenerator`）此前均使用简单的 `repository.count() + 1` 或 `findAll().count() + 1`。  
  当系统由于数据删除、手工指定 ID、或预置种子数据存在间隙时（例如已有 `001, 003, 005`，此时 count 为 3），下一次生成将得到 `004`。如果 `004` 已存在，或者后续再次生成可能出现碰撞，由于 InMemory 仓储是 `Map.put`，新实体会直接将老数据在内存中**静默覆盖**。
- **复现测试**：  
  创建 `BusinessNumberCollisionSafetyTest.java`，分别针对 Alert、AI Event、Rule、Fence 构造编号间隙（如 `001, 003, 007`）与异常格式（如 `FENCE-BAD_FORMAT`），验证生成器绝不生成重复或回退的编号。
- **修复方案**：
  1. **基于最大序号递增**：在 4 个生成器中遍历同前缀的现有 ID，解析出末尾数值后缀，取 `max(seq) + 1` 作为候选序号。
  2. **碰撞安全兜底循环**：生成候选编号后，通过 `while (repository.findById(candidate).isPresent()) candidateSeq++` 进行主动冲突探测，确保无论如何不会生成已存在的主键。
  3. **异常格式容错**：在解析后缀时对 `NumberFormatException` 进行静默或告警处理，遇到非标准格式数据不崩溃。
- **验证结果**：`BusinessNumberCollisionSafetyTest` (4例) 与 `BusinessNumberGeneratorTest` (4例) 共 8 例全部通过。

---

## 三、未修复项（明确界定与留待后续）

按照任务指令，本阶段严格不做全面 Bug Fix，以下 9 项非数据库阻塞型技术债维持原有状态：

1. **LiveWebSocketTest / WebSocketSessionRegistry pong 锁粒度**：属于单 JVM 内存并发优化，不影响数据语义。
2. **IdempotencyService race condition**：单 JVM 阶段使用 `ConcurrentHashMap`，正式环境由 Redis/Kvrocks 统一保证原子 SETNX。
3. **AI time bucket 与时序对齐**：属于前端/统计口径优化。
4. **AI simulate replay 连续调用**：属于前端演示控制逻辑。
5. **HTTP Idempotency Header 广度覆盖**：核心 Alert/AI 已接入，其余模块待业务需要扩展。
6. **Collision field contract 轻量冗余字段**：已在 PBF-05 中明确为派生字段，不作为权威设计。
7. **OperationsView 前端 mock**：属于前端页面技术债。
8. **Personnel live 实时连接稳定性**：属于长连接与模拟器范畴。
9. **多实例全局唯一编号**：由 openGauss 序列 / 分布式号段在数据库落地时天然解决。

---

## 四、对最终数据库设计的关键反向输入

本次 5 项修复对数据库物理设计（DDL）提供了极具价值的确定性结论：

1. **`safety_alert` 状态表定义**：
   - 状态机字典/枚举字段中，**绝对不要包含 `ESCALATED` 状态**；
   - `safety_alert` 表增加 `previous_risk_level`（升级前风险等级，可空）列，用于追踪升级历史；或由 `safety_alert_timeline` 的 `UPGRADE` 事件独立承载。
2. **`collision_pair` 关联关系**：
   - `collision_pair.active_alert_id` 为只读派生缓存列，**严禁添加 FOREIGN KEY 物理强约束**；
   - 告警表中 `dedup_key = 'COLLISION:{devA}:{devB}' AND status != 'CLOSED'` 是唯一权威去重凭据，数据库应为 `(dedup_key, status)` 建立复合索引。
3. **事务边界与事件发布**：
   - 验证了“顶层事务单一入口 + `afterCommit` 发布”模型的完备性。openGauss 阶段使用 Spring `@Transactional(rollbackFor = Exception.class)` 即可天然实现嵌套异常的根回滚与事件抛弃。
4. **`safety_rule` 多版本模型**：
   - 必须采用**主表 `safety_rule` + 版本子表 `safety_rule_version`** 的物理结构；
   - 在线编辑活跃规则仅向 `safety_rule_version` 插入 `status = 'DRAFT'` 的行，主表 `status`、`effective_at` 与边缘同步表 `safety_rule_edge_sync` 保持不动，直至发布事务提交。
5. **主键与编号策略**：
   - 各业务表主键保持 UUID 或 BIGINT；业务编号（`alert_no`, `rule_code`, `fence_code` 等）设置 `UNIQUE` 索引，并由 openGauss `SEQUENCE` 或应用号段服务生成，彻底杜绝并发覆盖。

---

## 五、回归测试与验证汇总

### 1. 后端测试（Backend）
- 执行命令：`mvn test "-Denforcer.skip=true"`
- 测试套件：32 个测试类，包含所有单元测试、模块集成测试、架构契约测试及 Phase B.5 新增测试。
- **测试结果：Tests run: 205, Failures: 0, Errors: 0, Skipped: 0（100% 通过）**。

### 2. 前端验证（Frontend）
- 类型检查：`npm run typecheck` → **通过（0 errors）**。
- 单元测试：`npx vitest run` → **29 test files, 117 tests passed（100% 通过）**。
- 生产构建：`npm run build` → **构建成功（dist 输出正常，0 errors）**。

系统已具备进入最终数据库物理设计与评审的完备条件。
