# SCS 项目 AI 协作与研发交接总指南（AI-HANDOFF）

> **致接手本项目的 AI Agent / 工程师**：  
> 本文档是整个 `Project_B/SCS`（智慧货场装卸作业安全卡控系统）的代码库**最高优先级交接中枢**。阅读完本文后，你无需反复翻找零碎的历史文档，即可全面掌握系统真实运行状态，并立即展开下一阶段的 **openGauss 数据库持久化重构、事务编排与分布式演进**。

---

## 一、SCS 是什么？

*   **业务定位**：**SCS（Safety Control System）**是智慧货场数智装卸管控平台中的 **S3 子系统**，面向门吊、翻箱机作业面及股道区域，负责现场人身安全与设备防碰撞卡控。
*   **两大铁律**：
    1.  **纵深联锁卡控**：感知风险后毫秒级联动声光、手环与 PLC 继电器切断动力，主动停机避险；
    2.  **云边协同自治**：中心网络中断时，现场边缘网关（EDGE-01~04）依据本地围栏与规则独立执行毫秒级安全联锁，**断网不瘫痪**，恢复后幂等补传重放。
*   **技术基线**：Java 17 + Spring Boot 3.5.5（模块化单体）+ Vue 3 / TS 严格模式 + 原生 WebSocket `/ws/live` + openGauss 6.x / Kvrocks。

---

## 二、当前做到什么程度？（代码真实事实）

1.  **工程已自闭环**：`.tools/` 预置隔离 JDK 17、Maven 3.9.16、Node 24.18、pnpm 10.34.5，已消除对外部机器路径的所有依赖；后端全量 **205 个测试 100% BUILD SUCCESS**；前端 29 个测试套件全部转绿。
2.  **前端 11 个业务视图全量落地**：态势首页、人员定位（轨迹回放）、多边形电子围栏、设备防碰撞（六级联动）、AI 视觉抓拍复核、告警全流程中心、规则热发布、运维监控、监控大屏及移动巡检端全部可用，配备 11 个纯函数 Adapter 与后端 DTO 解耦。
3.  **Alert 唯一主链确立**：所有安全事件（AI、碰撞、越界、断网补传）统一汇总至 `AlertService` 建单闭环，无旁路状态机。
4.  **架构缺陷已硬化（Phase B.5 闭环）**：
    *   风险等级与业务状态彻底解耦，消除升级死锁（B5-01）；
    *   防碰撞以告警中心未关闭状态为单一事实源，清除幽灵告警（B5-02）；
    *   `LiveEventGate` 实现嵌套可重入与失败丢弃（B5-03）；
    *   生效规则编辑自动生成次版本草稿，保护边缘节点（B5-04）；
    *   发号器消除间隙冲突（B5-05）。
5.  **权威 openGauss DDL 就绪**：`openGauss-schema-final-draft.sql` 定义了 **25 张物理活动表**与 **21 个序列**，高频时序数据已划归 openGemini。

---

## 三、当前数据存储状态（最核心现状！）

> 🔴 **关键认知**：  
> **当前系统内 0 个数据库 Repository，9 个仓储全部为 `InMemoryRepository`！**  
> 全量数据保存在 JVM 的 `ConcurrentHashMap` 中；已做防御性深拷贝（Copy-on-read / write），未调用 `save` 不改变状态。

*   **当前 9 个仓储接口与内存类**：
    1. `AlertRepository` → `InMemoryAlertRepository`
    2. `AiEventRepository` → `InMemoryAiEventRepository`
    3. `CollisionRepository` → `InMemoryCollisionRepository`
    4. `FenceRepository` → `InMemoryFenceRepository`
    5. `PersonnelRepository` → `InMemoryPersonnelRepository`
    6. `RuleRepository` → `InMemoryRuleRepository`
    7. `EdgeNodeRepository` → `InMemoryEdgeNodeRepository`
    8. `EdgeEventQueueRepository` → `InMemoryEdgeEventQueueRepository`
    9. `OpsEventLogRepository` → `InMemoryOpsEventLogRepository`
*   **事务现状**：全工程 **0 个 `@Transactional`**，并发完全靠单机 Java `synchronized`。
*   **硬件现状**：UWB/雷达/PLC/AI 视频流目前全部为**纯业务状态模拟**，无真实通信驱动。

---

## 四、当前最重要的问题与风险

1.  **物理落库阻断**：缺少真实的 openGauss 6.0 测试实例执行 DDL 兼容性验证（P0 阻塞）。
2.  **事务脏事件风险**：`LiveEventGate` 目前是纯 ThreadLocal 逻辑缓冲，未接入 Spring 事务；如果 DB 回滚而 WebSocket 已经发出，会造成客户端脏事件。落库阶段必须接入 `afterCommit`。
3.  **多实例发号碰撞**：当前 `Demo*NumberGenerator` 为单机内存扫描，多实例部署时会重复发号；需由 openGauss `sys_business_number` 接管。

---

## 五、下一步 Phase 1 要做什么？（执行入口）

根据 [`07-phase1-database-migration-plan.md`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/docs/ai-handoff/07-phase1-database-migration-plan.md)，按如下顺序实施：

1.  **Step 0**：在 openGauss 测试实例执行 `openGauss-schema-final-draft.sql`，做兼容性验证（Spike）；
2.  **Step 1**：编写 `JdbcCollisionRepository`、`JdbcEdgeNodeRepository`，加载主数据；
3.  **Step 2**：基于 `sys_business_number` 实现数据库分布式发号器；
4.  **Step 3**：编写 `JdbcRuleRepository` 与 `JdbcFenceRepository`（处理 JSONB 多边形与版本快照）；
5.  **Step 4**：编写 `JdbcPersonnelRepository`、`JdbcEdgeEventQueueRepository`、`JdbcOpsEventLogRepository`；
6.  **Step 5**：编写 `JdbcAiEventRepository`（处理事件主表与时间线子表级联）；
7.  **Step 6**：编写 `JdbcAlertRepository`（处理告警聚合根、证据 JSONB、联动 7 步与处置记录）；
8.  **Step 7**：在 Service 顶层工作流添加 `@Transactional`，改造 `LiveEventGate` 接入 `afterCommit`；
9.  **Step 8**：优化 Projection 投影层，将内存全表扫描改为 SQL `GROUP BY` 聚合。

---

## 六、阅读源码时最重要的类

| 维度 | 最核心的类 | 路径 / 职责 |
|---|---|---|
| **处置主链** | `AlertService.java` | `module/alert/service/AlertService.java`（全系统业务中枢，状态机流转与建单） |
| **告警领域根**| `DemoAlert.java` | `module/alert/model/DemoAlert.java`（聚合根，含多态证据与时间线） |
| **实时门控** | `LiveEventGate.java` | `common/realtime/LiveEventGate.java`（防脏广播缓冲门，未来接入 afterCommit） |
| **AI 业务流** | `AiEventService.java`| `module/ai/service/AiEventService.java`（复核确认、转告警、联动派单） |
| **防碰撞** | `CollisionService.java`| `module/collision/service/CollisionService.java`（间距曲线、六级联动停机、去重） |
| **离线补传** | `EdgeReplayService.java`| `module/ops/service/EdgeReplayService.java`（断网补传、幂等去重、重放建单） |
| **权威 DDL** | `openGauss-schema-final-draft.sql` | `backend/src/main/resources/db/design/`（25 张活动表与 21 个序列） |
| **安全守卫** | `DemoFeatureGuard.java`| `support/demo/DemoFeatureGuard.java`（生产与 Demo 模拟端点隔离门） |

---

## 七、交接文档清单与推荐阅读顺序

请按以下顺序系统性查阅 `docs/ai-handoff/` 下的专题文档：

1.  [`01-project-architecture.md`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/docs/ai-handoff/01-project-architecture.md) - **项目目录与系统架构全景**（技术栈、分层模型与前后端协同）
2.  [`02-domain-and-alert-flow.md`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/docs/ai-handoff/02-domain-and-alert-flow.md) - **业务领域模型与 Alert 处置主链**（真实代码调用链、状态机与证据链）
3.  [`03-database-model.md`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/docs/ai-handoff/03-database-model.md) - **openGauss 数据库模型与映射**（25 张表、21 个序列与 DTO 映射）
4.  [`04-repository-analysis.md`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/docs/ai-handoff/04-repository-analysis.md) - **仓储层现状与改造契约**（9 个 Repository 分析与深拷贝机制）
5.  [`05-transaction-and-events.md`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/docs/ai-handoff/05-transaction-and-events.md) - **事务边界分析与实时事件机制**（`LiveEventGate` 与 afterCommit 演进）
6.  [`06-infrastructure-and-profiles.md`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/docs/ai-handoff/06-infrastructure-and-profiles.md) - **基础设施探针与 Profile 配置**（`/health/ready` 探针与运行边界）
7.  [`07-phase1-database-migration-plan.md`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/docs/ai-handoff/07-phase1-database-migration-plan.md) - **Phase 1 openGauss 持久化实施路线图**（八步可执行开发方案）
8.  [`08-open-questions-and-blockers.md`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/docs/ai-handoff/08-open-questions-and-blockers.md) - **未决问题与阻塞项清单**（P0/P1/P2 外部依赖与责任方）
