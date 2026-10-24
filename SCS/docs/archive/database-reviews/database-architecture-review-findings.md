# 架构审计对数据库设计的影响（database-architecture-review-findings）

- 来源：全局 Demo 可扩展性架构审计（见 `docs/demo-architecture-review.md`，2026-09-19）
- 性质：**只记录 findings 与建议，不修改任何现有数据库设计文件 / DDL 草案**；所有变更待人工评审后另行处理。
- 核对对象：`docs/database-design/` 全部 6 份设计文档 + `backend/src/main/resources/db/design/openGauss-schema-draft.sql`（29 张活动表 + safety_personnel_track 注释模板），逐表反查当前 Java 代码中的真实事实。
- 结论先行：**未发现 Projection/DTO 被误建表，未发现 unresolved 主数据被外键提前合并，USR-*/P-* 分表正确。** 下列条目是落库前需要在设计中定案的调整点。

---

## 1. 表级处置建议（KEEP / RECONSIDER / DEFER）

### P0（第一轮落地）

| 表 | 建议 | 审计依据 / 说明 |
|---|---|---|
| sys_team | **KEEP** | DemoMasterData 6 个稳定 teamCode，唯一权威 |
| safety_area | **KEEP** | 12 个稳定 areaCode；unresolved 区域（箱区 A/B、箱区通道 C、维修通道、铁路装卸线 B、机房）保持独立行、parent_id 为空，不加外键合并 |
| sys_user | **KEEP** | 7 个 USR-*；需保留 demo_unverified 列（USR-005~007） |
| safety_personnel | **KEEP** | P-* 与 USR-* 分开正确；**剔除派生列**（见 §2） |
| safety_alert | **KEEP，需字段修订** | 见 §2（assignee、upgradedFrom、rule 血缘、mobileStage、派生时间、risk code） |
| safety_alert_timeline | **KEEP，需补结构化列** | 当前 TimelineEvent record 只有 (time, at:OffsetDateTime, text, state)；响应/到场/关闭时长靠**文本关键字匹配**计算。建议加 `event_type`（机器 code）、`sequence_no`（DDL 已有 sequence，需确保写入侧不再依赖 List 顺序），让时长统计脱离文本匹配 |
| safety_alert_evidence | **KEEP（单表 + JSONB 方案认可）** | sealed 五型（personnel/collision/ai/device-metric/system-metric）通用字段关系化、特有字段 JSONB 是正确取舍；注意 AiEvidence.boxes、PersonnelEvidence.track、CollisionEvidence.trend 进 JSONB 而非子表 |
| safety_alert_treatment | **KEEP** | 复核驳回后可再次处置，1:N 正确；attachment 当前仅"N 张（Mock）"计数文本，正式改 safety_attachment 引用 |
| safety_alert_linkage / _step | **KEEP** | 代码证实 Linkage 权威在 Alert 侧（Collision PairState.steps 仅回显缓存，所有写入经 AlertService.linkage），7 步（detect/alarm/driver/slow/stop/plc/人工接管）落 step 表成立 |
| ai_event | **KEEP，需字段修订** | risk 高/中/低与 Alert 四级是**刻意不同词表**，不要强行统一为同一 code 集；linked_alert_id 软引用（见 §3 Q4） |
| ai_event_timeline | **KEEP** | AI 有独立 AiTimelineNode，两套 timeline 保持独立，领域边界清晰 |
| safety_fence | **KEEP** | 逻辑围栏身份/当前状态；teams 当前是自由文本（FENCE-003 含未确认"检修班"），见 §2 team_scope |
| safety_fence_version | **KEEP** | polygon JSONB 合理；编辑已生效对象回退待评审 + minor bump 的语义用版本行表达正确 |
| safety_rule | **KEEP** | 配置实体；status/风险 code 化同 F-01 |
| safety_rule_version | **KEEP** | params/actions JSONB、source_version 回滚来源，与代码 Version/VersionDiff/versionSnapshots 对应 |
| safety_rule_area | **KEEP** | DemoRule.areas 为 List<String>，多对多关联表正确（不要把区域列表塞 JSONB，因为要按区域筛选规则） |

### P1（第二轮）

| 表 | 建议 | 审计依据 / 说明 |
|---|---|---|
| device_camera | **KEEP，主数据收口** | 8 路 CameraInfo 当前种子在 **AiDemoSeeder**（不在 DemoMasterData），与 OpsInventory 设备台账是两套来源。落库前应明确设备主数据归口（建议 OpsInventory/设备主数据统一生成 camera 与 collision device），否则 device_camera 与 collision_device 会再次双份 |
| collision_device | **KEEP，剔除派生列** | 不存 latest_alert_id（见 §2）；设备主数据（status/通信/雷达态）与 PairState 运行态分开 |
| edge_node | **KEEP** | 资产 + 最新状态；CPU/内存/延迟/温度等高频指标不进本表（DDL 已按此处理，保持） |
| safety_fence_edge_sync / safety_rule_edge_sync | **KEEP，保持独立** | 审计确认 Rule/Fence 版本应继续独立（Q5），两表不合并；"边缘配置包版本"是真实边缘 Agent 阶段的事，可在 edge_node 留概念位 |
| edge_pending_event | **KEEP，强化约束** | idempotency_key UNIQUE 正确；补"同键不同负载=冲突"的约束说明；当前 processedEventIds/processedIdemKeys 是 EdgeReplayService 的 JVM HashSet（重启丢失、多实例不共享），落库后由本表 + 唯一约束取代 |
| ops_event_log | **KEEP** | 14 类事件、MAX_KEEP 截断是内存策略；正式保留周期在 open questions 定 |
| outbox_event | **RECONSIDER（Demo 可不建，生产建议建）** | 当前广播在 Service 保存成功后同步进行、best-effort 吞异常，单实例 Demo 工作良好。**单实例 + InMemory/JDBC 阶段不需要 outbox**；一旦多实例 + RocketMQ（事务提交后 fan-out），outbox 是保证"提交了但没广播"的标准解。建议保留在 P1，启用 RocketMQ 时落地 |
| safety_attachment | **RECONSIDER（延后到文件服务）** | 当前只有 Mock 计数文本、照片 dataURL 在前端本地，没有任何真实附件事实。没有对象存储/文件服务前，此表为空壳，建议 P1 末或 P2 随文件服务一起做；字段设计（storage_key/url/metadata，不存 BLOB）保持不变 |
| sys_dictionary / sys_dictionary_item | **RECONSIDER（第一轮可不建）** | User/Team/Area 已由正式主数据表承载并据此生成 /meta/dictionaries，不应再字典化一份。纯 UI 枚举（事件类型大类等）当前由 RiskClassifier + 前端常量承担且仍在演进。**建议第一轮不建**，等枚举口径稳定、确有后台动态配置需求时再建，避免与主数据表双源 |

### P2 / 时序边界

| 表/对象 | 建议 | 审计依据 |
|---|---|---|
| sys_idempotency_record | **DEFER** | 幂等当前由 Kvrocks（TTL 600s）承担，定位是短期快速幂等缓存；openGauss 长期幂等记录只在需要跨长周期审计/对账时才有价值。先观察 Kvrocks 方案，不进第一轮 |
| safety_personnel_track | **DEFER（DDL 已注释，认可）** | TrackPoint 由 PersonnelService.track() 确定性即时生成、不落存储；大规模轨迹归 openGemini。openGauss 只在需要短期关系型回溯时再启用 |
| edge_node_metric / collision_measurement（注释模板） | **DEFER 到 openGemini** | 代码确认：边缘指标为按请求确定性样本、无后台采样线程；Collision PairState 的 distance/relSpeed/trend 是短周期运行态。高频序列长期归 openGemini，openGauss 只保留关键事件/状态变化（已在 Alert） |
| RecoveryPhase 对应表 | **不建（新增明确结论）** | RecoveryPhase 是一次恢复流程的运行态（5 阶段），结束即终态，权威审计已在 ops_event_log；需要最新态放 Kvrocks/内存 |
| PairState / 运行配对表 | **不建** | PairState 是可由设备 + 未关闭告警重建的运行态（含 steps 缓存、approachIndex、trend） |

### 明确不建表（再次确认）

Overview 全部 DTO（summary/map/feed/trend/distribution）、Screen 全部 DTO、Mobile Home/ViewModel、Analytics dataset/KPI/趋势/排行、AlertMetrics、AiMetrics、Rule metrics、Ops overview/topology 聚合——全部为 Projection，从核心表 SQL 聚合，不建表。simulate-surge 注入的 ALM-SURGE- 告警是**真实写入 AlertRepository 的演示告警行**（不是独立表），落库后它们就是 safety_alert 中带演示前缀/来源标记的行，profile 隔离后生产不存在。

---

## 2. 字段级 findings（按表）

### safety_alert

| 字段/主题 | 代码事实 | 对设计的影响 | 关联 |
|---|---|---|---|
| assignee 关联 | AssignRequest 同时有 assignee/assigneeId/assigneeName，但 **AlertService.assign 完全不读 assigneeId**，只把姓名写入 `alert.assignee`；AI assign 还把姓名传进 assigneeId 位（AiEventService:184） | `assignee_user_id` 设计正确，但**迁移前必须先改 Service 真正落 ID**，否则该列恒 NULL；`assignee_name_snapshot` 同时保留（外键+快照模式不变） | F-02 |
| upgraded_from | 代码语义是**"升级前的风险等级文案"**（AlertService.upgradeRisk: `alert.upgradedFrom = old`，old 是 risk 字符串），不是来源告警的 ID | 列名/注释不要按"来源告警外键"设计；应为 `upgraded_from_level`（风险等级 code），与风险快照同义；若未来需要升级链再另加 parent_alert_id | 审计新增 |
| rule_id / rule_version | 普通风险告警 ruleId 可为 null；**人员越界时 ruleId 固定写 "RULE-PER-001"、ruleVersion 写的是 fence.version**（PersonnelService:181-185）；AI 来源告警 ruleId=null（模型版本与规则版本两条血缘） | rule_id/rule_version 必须允许 null；在 Fence/Rule 版本血缘定案前，人员越界告警的规则版本列会写入围栏版本值——建议增加证据来源类型（rule vs fence）或先按现状落 snapshot 并在注释标 DEMO，不要加外键 | F-07 |
| risk / status | risk 四级中文散落 ~20 处业务判断且无常量类；status 八态有 AlertStatuses 常量类（持中文值） | 两列均按**稳定机器 code** 设计（如 NORMAL/WARNING/SEVERE/URGENT；状态 code 同构），中文展示走字典/前端；**DDL 的 check 约束/索引必须等 code 命名定稿后再 apply**，当前草案若按中文写约束需改 | F-01 |
| mobile_stage + accepted_at/arrived_at | mobileStage 是 Demo 工作流子状态（PENDING/ACCEPTED/ARRIVED/PROCESSING，已是英文 code），与主 status 正交；"已升级"无回归处理链标准路径 | 作为**独立子状态列 + 两个时间戳列**落 safety_alert（Q1 结论），不进主状态枚举，不另建工单表（Phase 1）；主状态机"已升级"共存规则在 open questions 保留 | Q1/F-24 |
| sla_remaining_sec | 后端读取时按 slaDeadline 重算，是派生快照；关闭后 NON_NULL 剔除 | **不落列**（或仅作不落库的响应 DTO 字段）；只落 sla_deadline，剩余秒前端/查询计算 | 已知技术债确认 |
| time（HH:mm:ss 字符串） | 展示串，由 occurredAt 格式化；另多个模型有 lastUpdated/effectiveAt/expiresAt String | 不搬 String 时间列，统一 `timestamp with time zone`；展示由前端格式化（F-18）。fence/rule 的 effectiveAt/expiresAt 当前是 String，落库需转 timestamptz | F-18 |
| origin / edge_replay 元数据 | origin=CLOUD/EDGE_REPLAY；EdgeReplayMeta{edgeNodeId,offlineEventId,offlineOccurred,syncDelaySec,ruleVersionUsed,syncedAt} | edge_node_id 软引用（不加 FK 到可能未入库的边缘事件）、offline_event_id 保留原文；synced_at/offline_occurred 双时间戳语义保留（不回改边缘时间原则正确） | — |
| dedup_key | 形如 PERSON_INTRUSION:{person}:{fence}、COLLISION:{a}:{b}、EDGE-REPLAY:{node}:{eventId}，仅"存在未关闭告警"时生效 | 部分唯一索引（WHERE status<>closed）的方向正确；但 openGauss 部分索引/表达式索引语法需在真库 VERIFY；AI 建单当前不带 dedup（靠 linkedAlertId 防重），不要给 ai 来源强加同样约束 | DDL 已标 VERIFY |
| treatment / linkage / evidence 内嵌 | 同一聚合图一次 save | JDBC 实现需聚合内级联写 + 事务；Repository 契约要先改（F-05），不是表结构问题 | F-05 |
| 业务编号 | ALM-yyyyMMdd-NNN，单实例内存计数 nextAlertId() | DDL 的 UUID 主键 + 业务编号 UQ 方向正确；多实例编号生成（序列/号段/UUIDv7）必须在多实例部署前定，单实例 JDBC 阶段可先用序列 | F-16 |

### ai_event

- linked_alert_id：保留为**软引用**（可空、无强 FK 或仅普通索引），AI confirm 同事务建 Alert 后回写；Alert 侧 linked_ai_event_id 同样软引用溯源。不做双向状态同步（AI close 不联动 Alert close，代码现状正确）。
- risk 高/中/低：保留独立词表 code，不要与 Alert 四级共用 check；mapAlertRisk 的映射结果体现在生成的 Alert.risk 上。
- reviewer/assignee：同 F-02，未来改 userId + 姓名快照；API 本阶段不改，DDL 列先按可空 userId 设计。
- health（摄像头健康）、confidence、threshold、model、boxes(JSONB)：与 device_camera.status 有语义交叉但用途不同（事件时快照 vs 设备最新态），均保留在事件侧快照。

### safety_personnel

- **剔除/不落列**：active_alerts_ids（PersonnelService 已从 AlertRepository 实时派生 :86，intrude() 中为演示镜像 :186）、alerts_today（可聚合派生）、distance_today（演示/派生）。
- status / state / risk / bracelet_status 四字段重叠：落库时按"在线状态、风险标签、设备（手环）状态"拆列保留（前端统计分别使用），但不要加互相一致性约束；正式风险多值化是后续演进。
- x/y/coordinate/positioning_quality：当前最新位置可落主表（百分比 Demo 坐标，列注释标明非 GIS）；轨迹不进本表。
- base* 基线快照字段（模拟异常时还原用）：**Demo only，不落库**。

### collision_device

- **不存 latest_alert_id / latest_alert**（Q2，PairState 镜像，可派生）。
- speed/direction/x/y 等实时字段若落主表只代表"最新采样态"；distance/relSpeed/trend/radarQuality/steps/approachIndex 属 PairState 运行态不入库；高频序列归 openGemini。
- risk 中"待确认"是雷达断数健康态，建议设备健康状态列与风险等级列分开（DDL 设计时注意，不要把"待确认"放进风险 check）。

### safety_fence / version / edge_sync

- teams 当前自由文本（含未确认"检修班、外协单位"，InMemoryFenceRepository:42）：team_scope 落库设计为**关联表或 code 数组（JSONB）**，但 unresolved 的"检修班"在主数据确认前**不建外键、不强行映射到设备维保班**；保留原文快照 + 待核验标记。
- version 文本（v1.0 minor bump）与 rule version 编号空间独立，保持独立表/独立同步表（Q5）。
- polygon JSONB：合理；坐标为 0–100 百分比，注释标明 Demo 坐标系，未来 GIS 迁移不改表结构（改坐标内容与 SRID 标注）。

### safety_rule / version / rule_area

- Rule 当前**不参与运行时判定**（碰撞/AI 阈值在各自 Service 与 RuleService.simulate 双份，F-08）。这不影响表结构——rule/rule_version 如实存配置；但不要在字段注释里暗示"已生效规则实时驱动现场"，避免误读。
- owner/approver 未来按 user_id + 姓名快照；当前是中文名字符串。
- edgeNodes 同步态列表 → safety_rule_edge_sync（DDL 已规划），versionSnapshots Map → safety_rule_version 行。

### edge_node / edge_pending_event / ops_event_log

- edge_node：clock_offset_ms、agent_version、active/expected_rule_version、active/expected_fence_version 保留；cpu/memory/disk/temperature/latency 最新值可留列但标注"最新态、非历史"，历史归 openGemini。
- EdgeOpsService/EdgeReplayService 当前直接依赖 InMemory 实现类与 findMutable（F-05），**表结构不受影响，但 Repository 接口必须先修订**（显式状态翻转命令、补传进度更新方法），否则 JDBC 实现无法编译接入。
- edge_pending_event.payload_summary（嵌套 title/area/person/fence/device/risk/businessId/detail）→ JSONB，正确；local_linkage（6 个本地联动结果 + actions）→ JSONB。
- ops_event_log 与 alert_timeline **不合表**（审计确认职责不同），DDL 分离正确。

---

## 3. 已知数据库问题的复核结论（用户 §85 要求确认是否成立）

| 已知项 | 是否成立 | 复核结论 |
|---|---|---|
| upgradedFrom 真实语义 | **成立** | 是"上一风险等级文案"而非来源告警 ID，DDL/字典应按等级快照处理，勿建成告警外键 |
| risk level 需要 code 化 | **成立，且范围比状态更广** | 状态至少有 *Statuses 常量类收口；风险等级无常量类、中文字面量直接参与 ~20 处判断，是落库前第一项必修（F-01） |
| rule_id / rule_code 稳定性 | **成立但需细分** | Rule 自身有 RULE-* 稳定 ID；问题在 Alert 引用侧：人员越界把 fence.version 写进 rule_version、ruleId 固定占位。需要先定证据来源血缘，再决定列是否可空/加来源类型 |
| Fence team_scope code 化 | **成立，含 unresolved** | 应改 code 关联，但"检修班"未确认等于设备维保班，保留独立/待核验，不建强 FK |
| slaRemainingSec 等派生字段 | **成立** | sla_remaining_sec、active_alert_ids、alerts_today、latest_alert_id 均为派生/镜像，不落主表 |
| JDBC driver 描述 | **与代码一致，无需改设计** | pom 为 org.opengauss:opengauss-jdbc 6.0.0，驱动 org.postgresql.Driver、URL jdbc:postgresql://，application.yml 注释已写明；DDL/文档沿用该口径 |
| 时间类型混用 | **成立** | OffsetDateTime 为核心事件时间（正确），但 time/lastUpdated/effectiveAt/expiresAt/updatedAt/Version.date 仍是 String，落 timestamptz 前需收口（F-18） |
| 中文状态做业务判断 | **成立** | 状态值集中在常量类、风险值散在字面量；统一在 F-01 处理 |
| Alert assigneeId 技术债 | **成立且比文档记录更严重** | 不是"仅按姓名落库"，而是 Service 完全不读请求里的 assigneeId；DDL 的 assignee_user_id 要等 Service 改造后才有数据 |
| AI assign 只有姓名 | **成立** | AiEventService:167-196 派单按姓名，且 confirm/assign 是跨聚合多写，需要事务编排（F-02/F-03） |

---

## 4. 对迁移顺序与事务设计的补充输入

1. **Repository 契约修订先于 JDBC 实现**（F-05）：去 reset/clear/findMutable/deleteById(演示专用)、消除 Ops 两 Service 对 InMemory 具体类的依赖、聚合根暴露显式命令方法。这是 P0 表能开始迁移的前置条件，尤其 Alert 聚合簇与 Ops 三表。
2. **主数据先行仍成立**：sys_team/safety_area/sys_user 第一批；但 device_camera/collision_device 的主数据归口（DemoMasterData vs OpsInventory vs AiDemoSeeder）要先定，避免设备主数据第二次双源。
3. **事务边界**（F-03）：AI confirm/assign、Edge replay、Collision 升级为跨聚合事务；规则/围栏发布为"中心配置事务 + 边缘回执异步事务"；Alert 聚合内（主表+timeline+treatment+evidence+linkage）单事务。事件发布在事务提交后（AFTER_COMMIT），多实例 + RocketMQ 时启用 outbox_event。
4. **编号与幂等**：ALM 多实例号段/序列在多实例部署前必须解决（单实例可先用 SEQUENCE）；Edge 补传幂等由 edge_pending_event 唯一约束承载，取代 JVM HashSet；HTTP 幂等继续以 Kvrocks 为主、sys_idempotency_record 延后。
5. **Profile 隔离是灌种前提**：Seeder 无 @Profile、simulate 端点无隔离（F-11），在向真实 openGauss 连库之前必须先做 dev/demo vs server 的种子/模拟端点隔离，否则生产库首启即被灌入演示数据。该项不改变表结构，但属于"建库前必须完成"的配套工作。

---

## 5. 仍然 OPEN、需人工决定的事项（摘要，完整清单见 database-open-questions.md）

- 风险等级/各域状态 code 的最终命名集与中文映射字典归属；
- assignee_user_id 切换是否在同一阶段改 API 契约，还是先双写（姓名+ID）过渡；
- 人员越界告警的规则/围栏版本血缘表达（来源类型列 vs 两条引用）；
- "检修班"组织归属、USR-005~007 正式班组、箱区/通道等 unresolved 主数据（保持独立，不猜测合并）；
- outbox_event 是否随第一轮多实例启用；sys_dictionary 是否需要；
- openGauss 部分唯一索引、JSONB、IDENTITY/SEQUENCE 在 6.x 实机的兼容性 VERIFY（DDL 已标注）；
- Flyway vs Liquibase（倾向 Flyway，未引入依赖，待确认）；
- 高频时序进入 openGemini 的边界与保留周期、附件对象存储方案、审计数据保留/归档策略。

> 本文件仅为审计 findings，未改动 `database-design.md`、`database-data-dictionary.md`、`database-er.md`、`repository-table-mapping.md`、`api-data-source-mapping.md`、`database-open-questions.md` 与 `openGauss-schema-draft.sql` 中的任何内容。
