# 04 - 仓储层（Repository）现状与改造契约审计

> **核心结论**：后端源码中严格存在且仅存在 **9 个业务 Repository 接口**和 **9 个 InMemoryRepository 实现**。**当前没有任何数据库实现（DB Repository 为 0）**。所有仓储当前均在 JVM 内存（`ConcurrentHashMap` 与 `CopyOnWriteArrayList`）中运行。

---

## 1. 九大 Repository 完整现状矩阵

| 业务模块 | Repository 接口全类名 | 当前实现类 | 底层存储方式 | 实体 ID 生成方式 | 业务编号 (Business No) 生成方式 | 是否必须落库 openGauss |
|---|---|---|---|---|---|---|
| **Alert** (安全告警) | `module.alert.repository.AlertRepository` | `InMemoryAlertRepository` | `ConcurrentHashMap<String, DemoAlert>` | 业务编号即 ID (`ALM-*`) | `DemoAlertNumberGenerator` (当日 `maxSeq + 1` 探测) | **是 (P0 核心)** |
| **AI Event** (AI 视觉) | `module.ai.repository.AiEventRepository` | `InMemoryAiEventRepository` | `ConcurrentHashMap<String, DemoAiEvent>` | 业务编号即 ID (`AI-E-*`) | `DemoAiEventNumberGenerator` (`maxSeq + 1`) | **是 (P1 重点)** |
| **Fence** (电子围栏) | `module.fence.repository.FenceRepository` | `InMemoryFenceRepository` | `ConcurrentHashMap<String, DemoFence>` | `FENCE-001` 格式 | `DemoFenceNumberGenerator` (`maxSeq + 1`) | **是 (P0 重点)** |
| **Rule** (安全规则) | `module.rule.repository.RuleRepository` | `InMemoryRuleRepository` | `ConcurrentHashMap<String, DemoRule>` | `RULE-<域>-001` | `DemoRuleNumberGenerator` (`maxSeq + 1`) | **是 (P0 重点)** |
| **Personnel** (人员定位) | `module.personnel.repository.PersonnelRepository` | `InMemoryPersonnelRepository` | `ConcurrentHashMap<String, DemoPersonnel>` | 种子固定工号 (`P-001` 等) | 关联主数据用户工号 | **是 (P0 主数据)** |
| **Collision** (防碰撞) | `module.collision.repository.CollisionRepository` | `InMemoryCollisionRepository` | `ConcurrentHashMap<String, DemoCollisionDevice>` | 设备编号 (`DEV-CRANE-01`) | 固定设备台账编码 | **是 (P0 台账)** |
| **Edge Node** (边缘节点) | `module.ops.repository.EdgeNodeRepository` | `InMemoryEdgeNodeRepository` | `ConcurrentHashMap<String, DemoEdgeNode>` | 节点编号 (`EDGE-01`) | 固定硬件网关编号 | **是 (P0 拓扑)** |
| **Edge Queue** (补传队列) | `module.ops.repository.EdgeEventQueueRepository` | `InMemoryEdgeEventQueueRepository` | `ConcurrentHashMap<String, EdgePendingEvent>` | 边缘发生单号 (`EDGE-EVT-*`) | 边缘网关本地生成 | **是 (P1 离线)** |
| **Ops Log** (运维日志) | `module.ops.repository.OpsEventLogRepository` | `InMemoryOpsEventLogRepository` | `CopyOnWriteArrayList<OpsEventLog>` (上限 300) | `seq_ops_event_log` 模拟递增 | 格式化时间戳字符串 | **是 (P1 审计)** |

---

## 2. 内存实现细节与深拷贝（Copy-on-Read / Copy-on-Write）

在 Phase B 架构硬化之后，当前 9 个 InMemory 仓储全部具备了**与未来 JDBC 实现语义对齐**的防御性拷贝机制：

1. **防可变对象逃逸（Mutable Escape Prevention）**：
   - 外部调用 `findById`、`findAll`、`filter` 返回的对象，均经过领域模型的 `.copy()` 深度拷贝；
   - Service 获取到对象后，如果就地修改字段但**未显式调用 `repository.save(entity)`，仓储内部状态绝不改变**；
   - 这一语义通过专门的单元测试 `RepositoryCopySemanticsTest`（9 个测试用例）在 CI 中得到 100% 严格保证。
2. **禁止 Jackson 序列化深拷贝**：
   - 所有模型的深拷贝均使用手写的显式构造函数或 `copy()` 方法（深拷贝内部的 `List<TimelineEvent>`、`List<LinkageStep>`、`AlertEvidence` 等），避免因序列化损耗性能或丢失类多态信息。
3. **请求级派生字段剥离**：
   - 人员的 `activeAlertIds`、告警的 `slaRemainingSec`、防碰撞的运行态 `steps` 等，不进入仓储持久化副本，完全由 Service 在返回给前端前动态计算。

---

## 3. Demo 维护方法与正式契约的严格隔离

经代码审计，正式的 9 个 Repository 接口中**没有任何破坏性或演示专用的维护方法**（如 `clear()`, `reset()`, `deleteById()`）：
- **演示灌种接口**：`support.demo.DemoResettableStore#resetDemoData()`（由 `DemoSeedInitializer` 统一调度）；
- **演示清空接口**：`support.demo.DemoClearableStore#clearDemoData()`；
- **模拟删除**：`module.alert.demo.DemoAlertMaintenance`（独立组件，受 `DemoFeatureGuard` 保护）；
- **契约形状看护**：`RepositoryContractShapeTest` 利用 Java 反射扫描 9 个接口，断言接口中绝不存在 `clear / reset / delete / findMutable` 等方法。未来编写 `Jdbc*Repository` 时，**绝对不需要也不允许实现这些 Demo 接口**。

---

## 4. 业务编号（Business Number）生成机制深度审计

当前内存版本中，四大实体（Alert / AI / Rule / Fence）的编号生成由对应的 `Demo*NumberGenerator` 承担：

```java
// 以 DemoAlertNumberGenerator 为例：
String prefix = "ALM-" + OffsetDateTime.now(clock).format(DAY) + "-";
long maxSeq = 0;
for (var a : repository.findAll()) {
    if (a.id != null && a.id.startsWith(prefix)) {
        long seq = Long.parseLong(a.id.substring(prefix.length()));
        if (seq > maxSeq) maxSeq = seq;
    }
}
long next = maxSeq + 1;
// 探测冲突避免间隙覆盖
while (repository.findById(candidate).isPresent()) { next++; ... }
```

### 当前发号缺陷与 openGauss 解决对齐：
- **单实例安全**：方法加了 `synchronized` 关键字，单 JVM 内多线程并发安全；
- **多实例/重启不安全**：全量内存遍历，在大数据量下存在严重性能退化（$O(N)$ 扫表）；在多节点部署时，两个 JVM 实例会同时算出相同的 `next`，产生主键冲突；
- **openGauss 解决对齐**：
  - 数据库中已规划了专用发号表 `safety.sys_business_number`；
  - 物理表设置了 `business_no UNIQUE` 约束；
  - Phase 1 改造时，应直接以 openGauss `SELECT ... FOR UPDATE` 行锁发号器接管 `Demo*NumberGenerator`。

---

## 5. 硬化文档（Hardening Doc）与源码核对结论

对照 `docs/pre-database-repository-hardening.md` 中的设计规范与当前源码，核对结果如下：

| 硬化规范要求 | 源码实现状态 | 证据与位置 |
|---|---|---|
| **Persistence-neutral 接口** | ✅ **已解决** | 9 个 Repository 接口无任何 Spring/JDBC/内存实现类型，`RepositoryContractShapeTest` 通过 |
| **显式 save 变更落库** | ✅ **已解决** | 9 个 InMemory 仓储均通过 `store.put(id, entity.copy())` 落地，未 save 不生效 |
| **移除 `(InMemoryXxx) cast`** | ✅ **已解决** | `EdgeReplayService` 等业务类中已 100% 移除对 InMemory 类的强制转型 |
| **Edge 补传幂等权威在仓储** | ✅ **已解决** | `EdgeReplayService` 彻底移除内存 `HashSet`，判重完全查询 `EdgeEventQueueRepository` |
| **Demo 端点安全守卫** | ✅ **已解决** | 所有模拟端点统一调用 `demoGuard.requireSimulator()`，`server` profile 返回 403 |
| **Spring `@Transactional` 事务** | ⚠️ **未解决 (留待落库)** | 全局 0 处事务注解，当前依靠单机 `synchronized` 保证顺序，落库时必须补齐 |
| **真实 openGauss JDBC 仓储** | ⚠️ **未解决 (留待落库)** | 当前 0 个 JDBC 仓储，全量为 InMemory |
