# 数据库设计待决问题与决策清单（openGauss 6.x 最终修订版）

> 本文档将原有待决事项明确划分为两大部分：
> 1. **已决决策（Phase A / B / B.5 架构事实固化）**：已在 Java 契约与最终 DDL 中严格落地，不可擅自回退；
> 2. **待决业务与外部集成问题（Open Questions）**：需外部业务方、HR/IAM/GIS 规范或硬件团队介入后最终拍板；
> 3. **openGauss 兼容性实测清单（Compatibility Spike Checklist）**：必须在真实 openGauss 实例上验证的物理与语法项。

---

## 一、Phase A / B / B.5 已决决策（Resolved Decisions）

以下 12 项决策已在代码重构与 DDL 最终设计中得到彻底固化：

| # | 决策主题 | 最终代码事实与物理设计落地 | 涉及模块与表 |
| :-: | :--- | :--- | :--- |
| 1 | **状态与风险机器代码化** | 全面废除中文字面量作为业务判断键。统一采用 `NORMAL`/`WARNING`/`SEVERE`/`URGENT`，告警主状态收敛为 8 态，AI 收敛为 7 态。 | 全系统所有表 |
| 2 | **Alert 升级风险正交性 (B5-01)** | 告警升级仅提升 `risk_level_code`，主状态 `status_code` 不变；正式主状态集彻底剔除 `ESCALATED`；通过 `previous_risk_level_code` 记录升级历史。 | `safety_alert` |
| 3 | **防碰撞告警关系与判重 (B5-02 / Patch 4)** | `collision_device` 物理表彻底删除 `active_alert_id` 列（单设备可同时参与多个 Pair，存在 1:N 基数冲突）；防碰撞去重与当前告警查询唯一权威在 `safety_alert`（`findOpenByDedupKey`）。 | `collision_device`<br>`safety_alert` |
| 4 | **事件网关嵌套可重入 (B5-03)** | `LiveEventGate` 已支持嵌套可重入；JDBC 阶段事件发布收敛于事务提交后（`afterCommit`），不新增任何运行时临时持久化实体。 | 事务与事件编排 |
| 5 | **规则编辑版本保护 (B5-04)** | 编辑 `ACTIVE` 规则不降级主表状态；主表维持 `ACTIVE`，向版本子表追加新草稿版本（`status_code='DRAFT'`），发布时才晋升生效。 | `safety_rule`<br>`safety_rule_version` |
| 6 | **离线事件幂等与防重 (B5-05)** | `edge_pending_event.idempotency_key` 施加全局物理 `UNIQUE` 约束；新增 `payload_hash` 列识别 409 载荷冲突。 | `edge_pending_event` |
| 7 | **仓储深拷贝与事务隔离 (Phase B)** | 9 个 Repository 统一实现聚合根级保存契约（Aggregate Save），返回深拷贝独立对象，与内存边界解耦。 | 9 个 Repository |
| 8 | **责任人以系统账号关联** | 实体间一律采用 `assignee_user_code`（`USR-*`）及姓名快照，废除中文字符串外键。 | `safety_alert`<br>`ai_event` |
| 9 | **现场人员状态三维正交** | 严格拆分在岗态（`online_status_code`）、手环态（`bracelet_status_code`）、感知风险（`person_risk_code`）；`person_state` 划为派生计算。 | `safety_personnel` |
| 10 | **防碰撞传感器健康与风险正交** | 彻底拆分业务风险（`risk_level_code`）与硬件健康（`sensor_health_code`），`UNCERTAIN` 归入健康态，不再污染风险字段。 | `collision_device` |
| 11 | **高频时序严禁入 openGauss** | 人员轨迹点、碰撞毫米波雷达高频点阵、边缘 CPU/内存秒级指标，全面划归 **openGemini**，坚决不建在 openGauss 中。 | 架构存储分工 |
| 12 | **发号与并发安全设计** | 核心事件表采用客户端全局 UUID（兼顾离线自治）；正式多实例按日循环业务编号通过专用计数表 `sys_business_number` 排他行锁控制。 | `sys_business_number` |

---

## 二、待决业务与外部集成问题（Real Open Questions）

以下问题涉及外部系统集成、企业级规范与业务流程，需在后续系统集成阶段决策：

### A. 主数据与组织架构
1. **系统用户与外部 IAM / SSO 对接（OPEN）**：
   - 当前 `sys_user` 仅作为本地影子台账；未来接入企业统一身份认证后，用户主键是否映射工号，权限与角色是否由 IAM 实时下发。
2. **现场人员与 HR 系统同步（OPEN）**：
   - `safety_personnel` 工号（`P-24018`、外协 `V-10012`）由何方主导同步；外协施工人员进出场的黑白名单机制。
3. **UNRESOLVED 作业区域层级划分（OPEN）**：
   - 货场箱区 A/B、箱区通道 C、铁路装卸线 B 与机房的具体空间包含关系尚未提供蓝图；目前 DDL 中维持 `parent_id=NULL` 各自独立，待现场 GIS 规范明确后再行回填外键。
4. **FENCE-003“检修班”归属（OPEN）**：
   - 是否等同于“设备维保班（EQUIPMENT_MAINTENANCE）”；确认前维持结构化快照文本，不强制物理约束。

### B. 空间地理信息与算法
5. **GIS 坐标系统规范与 PostGIS 演进（OPEN）**：
   - 当前地图点位与围栏多边形采用 0~100 百分比相对坐标；未来接入无人卡车/北斗定位后，何时演进为 WGS84 / CGCS2000 空间坐标系；openGauss 6.x 的 PostGIS 空间插件支持情况。
6. **AI 误报与撤销流程（OPEN）**：
   - 若由 AI 识别并确认的告警在事后被人工确认为误报，当前系统仅支持关闭（`CLOSED`），是否需要新增“作废（VOID/CANCELLED）”业务状态。

### C. 基础设施与存储方案
7. **统一对象存储（MinIO / S3）接入时机（OPEN）**：
   - 现场抓拍截图、处置照片、视频片段何时接入正式对象存储；目前 `safety_attachment` 表已延期（DEFERRED），待选型落地后再行启用。
8. **openGemini 保留周期与下采样策略（OPEN）**：
   - 高频轨迹与测距时序数据在 openGemini 中的 TTL（如 30 天/90 天）及历史降采样归档规则。
9. **RocketMQ Outbox 部署与广播规模（OPEN）**：
   - 生产环境微服务化拆分后，Outbox 投递器的实例数与 RocketMQ Topic/Tag 规范。

---

## 三、openGauss 兼容性实测验证清单（Compatibility Spike Checklist）

在正式连接真实 openGauss 6.x 数据库并执行迁移前，必须逐项验证以下物理语法与行为特性：

- [ ] **1. SEQUENCE 独立序列行为**：
  - 验证 `CREATE SEQUENCE safety.seq_* START WITH 1000 INCREMENT BY 1` 语法；
  - 测试高并发下 `nextval()` 的性能与号段连续性。
- [ ] **2. 部分索引（Partial Index）兼容性**：
  - 验证 `CREATE INDEX idx_safety_alert_dedup_open ON safety.safety_alert (dedup_key, status_code) WHERE status_code <> 'CLOSED' AND status_code <> 'CANCELLED';` 是否被 openGauss 6.x 原生支持；
  - 若不支持，启用备选方案（普通复合索引 + 业务层排他校验）。
- [ ] **3. JSONB 类型与 GIN 索引**：
  - 验证多态证据 `payload` 与多边形 `polygon` 的 JSONB 存取性能；
  - 验证在 openGauss 6.x 下对 JSONB 内部路径建立索引的能力。
- [ ] **4. 排序索引 NULLS LAST**：
  - 验证 `CREATE INDEX idx_edge_pending_replay_order ON safety.edge_pending_event (edge_node_code, status_code, edge_occurred_at ASC NULLS LAST, event_id ASC);` 是否正确保序。
- [ ] **5. TIMESTAMPTZ 与时区处理**：
  - 测试 Java `java.time.Instant` / `OffsetDateTime` 通过 JDBC 写入 `TIMESTAMPTZ` 时，数据库时区与应用时区（Asia/Shanghai）的自动转换行为。
- [ ] **6. 驱动选型与连接兼容性**：
  - 对比官方 `org.opengauss.Driver` 与通用 `org.postgresql.Driver` 在 Spring Boot 3.5.5 (HikariCP) 环境下的性能、连接探针与异常转译差异。
- [ ] **7. 模式权限与 search_path**：
  - 确认应用专用账号对 `safety` schema 的 `USAGE`, `CREATE`, `SELECT, INSERT, UPDATE, DELETE` 权限配置规范。
- [ ] **8. 幂等插入语法（ON CONFLICT vs MERGE INTO）**：
  - 验证基于 `UNIQUE` 索引执行 `INSERT ... ON CONFLICT (...) DO UPDATE` 的语法兼容性。
