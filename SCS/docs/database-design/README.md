# 数据库设计蓝图（openGauss 6.x 最终设计修订版）文件索引

> 阶段：**Backend Demo → openGauss 6.x 最终一致性修订阶段产物（Post-Patch）**。
> 状态：**READY FOR OPENGAUSS COMPATIBILITY SPIKE**。纯设计与文档阶段，未连接 openGauss 实例、未执行任何 SQL、未改动现有 Java/前端业务代码、未引入 Flyway/Liquibase/MyBatis 依赖。
> 权威 DDL 产物：[`../../backend/src/main/resources/db/design/openGauss-schema-final-draft.sql`](../../backend/src/main/resources/db/design/openGauss-schema-final-draft.sql)。

---

## 1. 文档索引与全景导航

| 文档名称 | 核心内容定位 | 重点关注读者 |
| :--- | :--- | :--- |
| [**database-final-ddl-patch.md**](database-final-ddl-patch.md) | **Spike 前最后一致性补丁记录**：表数精准核正（24+1）、21 个 Sequence 显式绑定、Rule 版本可空、Collision 缓存列彻底删除、Edge 排序一致性 | 架构评审人、技术主管、DBA |
| [**database-design.md**](database-design.md) | **数据库统一设计总蓝图**：总体设计原则、存储边界分工、活动表域划分、主数据/版本/快照/JSONB 设计、主键与外键策略、索引体系、事务模型与迁移顺序 | 全体架构师、全栈开发、DBA |
| [**database-final-revision.md**](database-final-revision.md) | **最终设计修订报告**：详细阐述 Phase A（状态代码化）、Phase B（仓储契约）、Phase B.5（B5-01~B5-05 代码事实）对数据库设计的修正全记录 | 架构评审人、技术主管 |
| [**database-data-dictionary.md**](database-data-dictionary.md) | **逐表字段数据字典**：覆盖 24 张第一期活动核心表、发号辅助表及 5 张延期表，含类型、默认值、约束、业务说明及 Model 来源 | 后端开发、DBA |
| [**database-er.md**](database-er.md) | **Mermaid 领域实体关系图**：7 张分域 ER 图（主数据、告警主链、AI、防碰撞、围栏、规则、云边队列），仅包含持久化实体 | 全体团队成员 |
| [**repository-table-mapping.md**](repository-table-mapping.md) | **仓储接口与事务契约映射**：9 个 Repository 接口 → 物理表级联映射，聚合根保存语义、深拷贝防污染与迁移评估 | 后端核心开发 |
| [**api-data-source-mapping.md**](api-data-source-mapping.md) | **API 端点与数据来源映射**：覆盖 15 个 Controller、96 个 REST 端点，逐一标注 READ / COMMAND / AGGREGATE / PROJECTION | 后端开发、前端联调 |
| [**database-open-questions.md**](database-open-questions.md) | **决策清单与实测清单**：12 项已决架构决策、外部业务待决事项，以及 openGauss 6.x 兼容性实测（Compatibility Spike）24 项专项验证清单 | 架构评审人、DBA |
| [**openGauss-schema-final-draft.sql**](../../backend/src/main/resources/db/design/openGauss-schema-final-draft.sql) | **openGauss 6.x 最终 DDL 脚本草案**：包含 24 张核心活动表、21 个物理主键 Sequence、1 张发号辅助表、真实 Query 覆盖索引；0 破坏性语句 | DBA、后续实施团队 |
| [openGauss-schema-draft.sql](../../backend/src/main/resources/db/design/openGauss-schema-draft.sql) | *历史初稿（已标记 SUPERSEDED 废弃保留归档）* | 归档参考 |

---

## 2. 关键设计结论速览

1. **表结构规模与精简落地（SQL Parser 文本扫描核准）**：
   - **第一期活动核心表（Active Phase 1 Core Tables，共 24 张）**：
     - *主数据台账（3 张）*：`sys_team`, `safety_area`, `sys_user`
     - *人员与设备资产（4 张）*：`safety_personnel`, `device_camera`, `collision_device`, `edge_node`
     - *告警主聚合（6 张）*：`safety_alert`, `safety_alert_timeline`, `safety_alert_evidence`, `safety_alert_treatment`, `safety_alert_linkage`, `safety_alert_linkage_step`
     - *AI 违规聚合（2 张）*：`ai_event`, `ai_event_timeline`
     - *电子围栏聚合（3 张）*：`safety_fence`, `safety_fence_version`, `safety_fence_edge_sync`
     - *卡控规则聚合（4 张）*：`safety_rule`, `safety_rule_area`, `safety_rule_version`, `safety_rule_edge_sync`
     - *边缘队列与运维（2 张）*：`edge_pending_event`, `ops_event_log`
   - **业务发号辅助表（1 张）**：`sys_business_number`（多实例排他行锁）
   - **总 `CREATE TABLE` 语句数**：24 + 1 = **25 张**。
   - **物理主键 Sequence**：**21 个**，全部通过 `DEFAULT nextval(...)` 1:1 绑定到对应表，孤立序列 = 0。
   - **延期实施表（Deferred Tables，共 5 张，仅 DDL 注释保留）**：`sys_dictionary`, `sys_dictionary_item`, `sys_idempotency_record`, `safety_attachment`, `outbox_event`
   - **严禁建在 openGauss 的时序表（归 openGemini，共 3 类）**：`safety_personnel_track`, `collision_measurement`, `edge_node_metric`
2. **Phase B.5 核心代码事实 100% 贯彻**：
   - **B5-01**：Alert 升级仅提升 `risk_level_code`，主状态 `status_code` 不改变，正式主状态集彻底移除 `ESCALATED`；
   - **B5-02 & Patch 4**：`collision_device` 彻底删除 `active_alert_id` 列（单设备多 Pair 存在 1:N 冲突）；防碰撞去重唯一权威在 `safety_alert`；
   - **B5-03**：`LiveEventGate` 已支持嵌套可重入；JDBC 迁移时事件发布统一收口于事务提交后（`afterCommit`），不依赖临时表；
   - **B5-04 & Patch 3**：编辑 `ACTIVE` 规则不降级主表状态，`active_version` 允许未发布规则为空，新版本在 `safety_rule_version` 中以草稿形式持久化；
   - **B5-05 & Patch 5**：`edge_pending_event.idempotency_key` 施加唯一约束，新增 `payload_hash` 用于识别 409 载荷冲突；`edge_occurred_at NOT NULL`，排序索引去除冗余 `NULLS LAST`。
3. **架构红线遵守情况**：
   - **零代码修改**：未触碰任何 Java 后端与 Vue3 前端源码；
   - **零破坏性操作**：DDL 脚本禁止 `DROP TABLE`、`TRUNCATE`、`DELETE`；
   - **零数据库连接**：未执行任何 SQL，等待 openGauss Compatibility Spike 验证后方可推进实施。
