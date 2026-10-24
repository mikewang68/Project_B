# Final DDL Patch: openGauss Compatibility Spike 前最后一致性修订记录

> 日期：2026-09-20
> 目标阶段：Database Design Final Revision → **READY FOR OPENGAUSS COMPATIBILITY SPIKE**
> 配套权威 DDL：[`../../backend/src/main/resources/db/design/openGauss-schema-final-draft.sql`](../../backend/src/main/resources/db/design/openGauss-schema-final-draft.sql)
> 约束状态：纯设计/文档 Patch，未修改 Java/前端代码，未 SSH，未连接 openGauss，未执行任何 SQL。

---

## 1. Scope（补丁范围与修订背景）

在完成 Phase A（机器代码化）、Phase B（仓储契约聚合根保存）与 Phase B.5（B5-01 ~ B5-05 关键缺陷修复）后，对最终 DDL 草案与架构设计文档进行了最后一次端到端的一致性审计。
本补丁集中解决以下 5 项物理一致性问题：
1. **活动表数量精准校正**：通过 SQL Parser 实际扫描核准真实 `CREATE TABLE` 数量，纠正旧报告中混淆关系表与领域聚合根概念的统计偏差；
2. **Sequence 显式绑定与消费收口**：将 21 个 `BIGINT` 物理主键序列提升至表前声明，并在 21 张表的主键列上显式绑定 `DEFAULT nextval(...)`，消除孤立序列；
3. **Rule `active_version` 语义与可空性**：支持从未发布的规则（草稿、审批中）`active_version` 为 `NULL`，明确 `platform_version` 为编辑态版本，确保 B5-04 在线编辑状态保护在 Schema 层成立；
4. **彻底删除 `collision_device.active_alert_id`**：消除单设备参与多个 Collision Pair 导致的 1:N 基数冲突，告警去重与查询完全收拢至 `safety_alert.dedup_key`；
5. **统一 Edge Pending 排序与 `NOT NULL` 语义**：确认合法业务事件的 `edge_occurred_at` 必填（`NOT NULL`），移除复合索引中语义冗余的 `NULLS LAST`，以 `(edge_occurred_at ASC, event_id ASC)` 保证确定性 tie-break 稳定重放。

---

## 2. Table Count Correction（活动表数量校正）

经文本扫描脚本对 `openGauss-schema-final-draft.sql` 中的实际 `CREATE TABLE` 语句进行统计，真实数量如下：

| 分类 | 数量 | 详细表名清单 |
| :--- | :---: | :--- |
| **Core Active Tables（核心业务活动表）** | **24** | `sys_team`, `safety_area`, `sys_user`<br>`safety_personnel`, `device_camera`, `collision_device`, `edge_node`<br>`safety_alert`, `safety_alert_timeline`, `safety_alert_evidence`, `safety_alert_treatment`, `safety_alert_linkage`, `safety_alert_linkage_step`<br>`ai_event`, `ai_event_timeline`<br>`safety_fence`, `safety_fence_version`, `safety_fence_edge_sync`<br>`safety_rule`, `safety_rule_area`, `safety_rule_version`, `safety_rule_edge_sync`<br>`edge_pending_event`, `ops_event_log` |
| **Platform Auxiliary Tables（平台发号辅助表）** | **1** | `sys_business_number` |
| **Total `CREATE TABLE` Statements** | **25** | 24 张核心业务表 + 1 张平台发号辅助表 |
| **Deferred Tables（延期实施表，DDL 注释保留）** | **5** | `sys_dictionary`, `sys_dictionary_item`, `sys_idempotency_record`, `safety_attachment`, `outbox_event` |
| **Prohibited / openGemini（严禁入 openGauss 时序表）**| **3** | `safety_personnel_track`, `collision_measurement`, `edge_node_metric` |
| **Projection / DTO（大盘、大屏、看板视图）** | **0** | 完全由 SQL 聚合视图与查询动态组装，坚决不建物理表 |

> **校正说明**：此前部分文档中记录的“22 张活动表”系将 `safety_rule_area`（多值关联）与 `safety_alert_linkage_step`（明细步骤）折叠到聚合根后的逻辑计数。物理 DDL 中实际包含完整的 24 张 Core 表 + 1 张 Auxiliary 表 = **25 `CREATE TABLE`**。本次已完成全局统一。

---

## 3. Sequence Binding（Sequence 物理绑定与映射）

为避免物理 DDL 中出现“声明了 `CREATE SEQUENCE` 但主键未显式消费”的孤立序列技术债，本补丁完成了如下改造：
1. **声明顺序前置**：将所有 21 个序列声明移动至 Schema 定义之后、表定义之前；
2. **主键列显式绑定**：在全部 21 张使用 `BIGINT` 物理主键的表上，将 `id` 列声明为：
   ```sql
   id BIGINT NOT NULL DEFAULT nextval('safety.seq_xxx'), -- VERIFY ON OPENGAUSS
   ```
3. **物理 PK 与业务编号发号解耦**：
   - 物理主键自增全部走 21 个独立序列；
   - 业务编号（`ALM-yyyyMMdd-NNN` 等）走 `sys_business_number` 排他行锁计数，二者概念严格隔离；
4. **UUID 实体保持独立**：`safety_alert`, `ai_event`, `edge_pending_event` 保持应用层生成 UUID，不强行改为 Sequence。

### Sequence Mapping 表
| 序号 | 序列名 (`Sequence`) | 对应物理表 (`Table`) | 绑定列 (`Column`) | 主键策略 (`Strategy`) | 实测标记 (`VERIFY`) |
| :-: | :--- | :--- | :--- | :--- | :--- |
| 1 | `seq_sys_team` | `safety.sys_team` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 2 | `seq_safety_area` | `safety.safety_area` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 3 | `seq_sys_user` | `safety.sys_user` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 4 | `seq_safety_personnel` | `safety.safety_personnel` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 5 | `seq_device_camera` | `safety.device_camera` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 6 | `seq_collision_device` | `safety.collision_device` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 7 | `seq_edge_node` | `safety.edge_node` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 8 | `seq_safety_alert_timeline` | `safety.safety_alert_timeline` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 9 | `seq_safety_alert_evidence` | `safety.safety_alert_evidence` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 10 | `seq_safety_alert_treatment` | `safety.safety_alert_treatment` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 11 | `seq_safety_alert_linkage` | `safety.safety_alert_linkage` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 12 | `seq_safety_alert_linkage_step`| `safety.safety_alert_linkage_step`| `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 13 | `seq_ai_event_timeline` | `safety.ai_event_timeline` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 14 | `seq_safety_fence` | `safety.safety_fence` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 15 | `seq_safety_fence_version` | `safety.safety_fence_version` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 16 | `seq_safety_fence_edge_sync` | `safety.safety_fence_edge_sync` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 17 | `seq_safety_rule` | `safety.safety_rule` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 18 | `seq_safety_rule_area` | `safety.safety_rule_area` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 19 | `seq_safety_rule_version` | `safety.safety_rule_version` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 20 | `seq_safety_rule_edge_sync` | `safety.safety_rule_edge_sync` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |
| 21 | `seq_ops_event_log` | `safety.ops_event_log` | `id` | `DEFAULT nextval` | VERIFY ON OPENGAUSS |

* **Sequence 统计**：创建总数 = 21；主键绑定消费数 = 21；孤立序列 = 0。

---

## 4. Rule `active_version` 语义与状态流转

根据 Phase B.5 的 B5-04 代码事实，卡控规则版本采用“双指针”设计，消歧定义如下：
* `active_version VARCHAR(24) NULL`：
  - 含义：当前在现场/边缘实际上线生效运行的规则版本号；
  - 约束：允许为 `NULL`。从未发布过的新建草稿（`DRAFT`、`REVIEW`、`APPROVED`）该字段为 `NULL`；
  - 触发：只有在执行 `publish` 动作时，该字段才被置为目标生效版本号。
* `platform_version VARCHAR(24)`：
  - 含义：管理平台当前最新编辑或配置的版本号；
  - 触发：新建草稿时为 `v1.0`；在 `ACTIVE` 状态下在线编辑时，递增为新草稿版本号（如 `v2.5`）。

### 状态转换用例验证
* **Case 1（初建草稿）**：
  - 主表 `safety_rule`：`status_code = 'DRAFT'`, `active_version = NULL`, `platform_version = 'v1.0'`
  - 子表 `safety_rule_version`：单行 `version_no = 'v1.0'`, `status_code = 'DRAFT'`
* **Case 2（首次审批并发布）**：
  - 主表 `safety_rule`：`status_code = 'ACTIVE'`, `active_version = 'v1.0'`, `platform_version = 'v1.0'`
  - 子表 `safety_rule_version`：`version_no = 'v1.0'`, `status_code = 'ACTIVE'`
  - 子表 `safety_rule_edge_sync`：针对 4 个边缘节点下发 `expected_version = 'v1.0'`
* **Case 3（在线编辑 ACTIVE 规则）**：
  - 主表 `safety_rule`：`status_code = 'ACTIVE'`, `active_version = 'v1.0'`（**旧版本继续生效，绝不降级**）, `platform_version = 'v1.1'`
  - 子表 `safety_rule_version`：新增一条 `version_no = 'v1.1'`, `status_code = 'DRAFT'`；原 `v1.0` 保持 `ACTIVE`
  - 子表 `safety_rule_edge_sync`：`expected_version` **保持为 v1.0**，普通编辑绝不下发新草稿
* **Case 4（新版本发布）**：
  - 主表 `safety_rule`：`active_version = 'v1.1'`, `platform_version = 'v1.1'`
  - 子表 `safety_rule_edge_sync`：`expected_version` 切换为 `v1.1` 并触发同步重试

---

## 5. Collision 运行态缓存解耦（彻底删除 `active_alert_id`）

### 5.1 删除原因与基数分析
1. **运行态缓存本质**：Phase B.5 已明确 `activeAlertId` 仅为内存 Pairing 计算中的镜像，不是权威外键；
2. **严重的 1:N 基数冲突（Cardinality Conflict）**：
   - 真实作业现场中，一台受控设备可同时与多个相邻设备发生安全交会；
   - 例如转运车辆 `VEH-07` 同时与翻箱机 `TIP-02` 发生间距预警、与龙门吊 `CRANE-01` 发生越界碰撞风险；
   - 若在 `collision_device` 表上设置单一标量列 `active_alert_id`，多告警并发时后一个告警必然覆盖前一个告警的 ID，导致数据失真；
3. **物理清理结论**：
   - 在 `collision_device` 表中**彻底删除 `active_alert_id` 列**（持久化 Schema 中该列数量清零）；
   - 保持 `PairState` 不建物理表；
   - 开放碰撞告警的查询完全收拢至 `safety_alert` 表：
     ```sql
     SELECT * FROM safety.safety_alert 
     WHERE dedup_key = 'COLLISION:VEH-07:TIP-02' 
       AND status_code NOT IN ('CLOSED', 'CANCELLED');
     ```

---

## 6. Edge Replay 排序与 `NOT NULL` 语义统一

### 6.1 业务事实确认
- 检查 `EdgePendingEvent.java` 与相关用例，合法的业务事件在边缘端产生时必须记录发生时钟；补传建单时 `occurred_at` 严格取自 `edgeOccurredAt`；
- 补传计算 `syncDelaySec = Duration.between(edgeOccurredAt, syncedAt)` 时，若发生时间为空会直接抛出空指针异常。

### 6.2 物理 Schema 统一
- 字段定义：保持 `edge_occurred_at TIMESTAMPTZ NOT NULL`；
- 索引清理：移除原索引定义中冗余且矛盾的 `NULLS LAST`，简化为：
  ```sql
  CREATE INDEX idx_edge_pending_replay_order ON safety.edge_pending_event (
      edge_node_code, status_code, edge_occurred_at ASC, event_id ASC
  );
  ```
- 语义效果：保证按照发生时间严格递增重放；在同一毫秒内的并发事件以 `event_id ASC` 作为确定性 tie-break 稳定保序。

---

## 7. Static Consistency Check（静态一致性审计）

在完成 Patch 后对 `openGauss-schema-final-draft.sql` 执行了全面的脚本化静态检查，结果如下：

| 审计检查项 | 目标预期 | 实际检测结果 | 结论 |
| :--- | :---: | :---: | :---: |
| `ESCALATED` in Alert status_code | 0 | **0**（仅存在于注释说明） | **PASS** |
| `upgraded_from_alert_no` 列声明 | 0 | **0**（仅存在于注释说明） | **PASS** |
| `active_alert_id` in `collision_device` | 0 | **0**（列已被彻底移除） | **PASS** |
| `latest_alert_id` in `collision_device` | 0 | **0**（列不存在） | **PASS** |
| 孤立 Sequence（未在表 PK 绑定的序列） | 0 | **0**（21 个序列全部 1:1 绑定） | **PASS** |
| 破坏性 SQL（DROP, TRUNCATE, DELETE） | 0 | **0**（仅存在于注释禁止说明） | **PASS** |
| Projection 物理实体表 | 0 | **0**（全部通过 SQL 聚合视图查询） | **PASS** |
| openGemini 时序表物理创建 | 0 | **0**（仅在 DDL 注释中保留规格） | **PASS** |
| Deferred 延期表物理创建 | 0 | **0**（仅在 DDL 注释中保留规格） | **PASS** |

---

## 8. Compatibility Spike Input（实机实测验证输入）

本次 Patch 后的权威 DDL 已就绪，后续在独立测试环境 openGauss 6.x 实例上执行 Compatibility Spike 时的验证清单如下：

1. **`CREATE SCHEMA` 权限与 search_path**；
2. **`CREATE SEQUENCE ... START WITH 1000 INCREMENT BY 1`** 独立序列语法及并发性能；
3. **`DEFAULT nextval('safety.seq_xxx')`** 语法支持与驱动主键自增回填；
4. **Sequence Rollback 行为**：验证事务回滚后序列是否跳号（正常跳号无需回滚）；
5. **Concurrent nextval**：高并发调用下序列号单调性；
6. **BIGINT 主键生成的 `RETURNING id` 语法支持**；
7. **`TIMESTAMPTZ` 时区转换**：数据库 UTC 与应用 Asia/Shanghai 的驱动自动映射；
8. **Java `OffsetDateTime` / `Instant` JDBC 类型映射**；
9. **`JSONB` 列存取**（`evidence.payload`, `fence_version.polygon`, `rule_version.params`）；
10. **`JSONB` 路径索引与 GIN 索引** 兼容性；
11. **部分索引（Partial Index）**：`CREATE INDEX ... WHERE status_code <> 'CLOSED' AND status_code <> 'CANCELLED'` 语法及优化器识别；
12. **多列复合索引执行计划**（`idx_safety_alert_list_query`, `idx_edge_pending_replay_order`）；
13. **`INSERT ... ON CONFLICT (...) DO UPDATE`** 幂等插入语法支持；
14. **`MERGE INTO` 语法对比验证**；
15. **`SELECT ... FOR UPDATE` 排他行锁** 在 `sys_business_number` 发号场景下的并发阻塞行为；
16. **CHECK 约束枚举合法性校验**；
17. **物理外键级联删除（`ON DELETE CASCADE`）** 在聚合根级删除时的物理行为；
18. **业务唯一键（`UNIQUE`）冲突异常转译**；
19. **本地事务 commit / rollback 一致性**；
20. **HikariCP 连接池与 `org.opengauss.Driver` 官方驱动适配**；
21. **通用驱动 `org.postgresql.Driver` 探针兼容性对比**；
22. **批量插入（Batch Insert）吞吐实测**。

---

## 9. Verification（回归验证）

- **后端测试**：执行 `mvn test "-Denforcer.skip=true"`，**205 个测试全绿（100% Green，0 失败，0 错误）**；
- **前端检查**：执行 `npm run typecheck`（`vue-tsc -b`），**0 错误（100% Green）**；
- **代码变动检查**：Java 代码修改 = 0，前端代码修改 = 0。

---

## 10. Conclusion（阶段结论）

当前系统已达到：
# READY FOR OPENGAUSS COMPATIBILITY SPIKE
等待人工确认后连接测试数据库执行实测脚本。
