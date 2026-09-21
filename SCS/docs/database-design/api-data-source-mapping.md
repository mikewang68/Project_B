# API → 数据库表来源映射（openGauss 6.x 最终修订版）

> 覆盖当前后端 **15 个 Controller、96 个 REST 端点**（不含 WebSocket `/ws/live`）。
> 类型标注：
> - **READ**：实体明细或列表过滤查询；
> - **COMMAND**：业务状态机流转与命令写入（写事务）；
> - **AGGREGATE**：单表或多表 SQL 统计分析（不建聚合实体表）；
> - **PROJECTION**：跨领域多源组装视图（严格不建物理表）。
> 
> 架构演进依据：全面对齐 Phase A 机器代码规范、Phase B 仓储契约以及 Phase B.5 的 5 项核心代码事实。

---

## 0. 基础与平台端点

| 方法 | 端点 | 类型 | 数据表来源（未来 JDBC 路径） | 说明与演进事实 |
| :--- | :--- | :---: | :--- | :--- |
| `GET` | `/api/v1/auth/me` | READ | `safety.sys_user` | 责任人信息读取（当前 DemoUserProperties 默认 USR-001） |
| `GET` | `/health/live` | PROJECTION | *进程存活探针* | JVM 运行态自检，不查库 |
| `GET` | `/health/ready` | PROJECTION | *就绪探针* | 聚合 openGauss 连接池与 Kvrocks 状态，不建业务表 |
| `GET` | `/api/v1/meta/dictionaries` | READ | `sys_user` + `sys_team` + `safety_area` | 基础主数据视图组装；业务枚举由代码内置 Mapper 支撑 |

---

## 1. AI 违规识别（AiEventController，/api/v1）

| 方法 | 端点 | 类型 | 数据表来源 | 说明与演进事实 |
| :--- | :--- | :---: | :--- | :--- |
| `GET` | `/ai-events` | READ+AGGREGATE | `ai_event` + `ai_event_timeline` | 分页列表；基于 `status_code`, `risk_code`, `area_code` 过滤 |
| `GET` | `/ai-events/{id}` | READ | `ai_event` → `ai_event_timeline` | 关联 `device_camera` 补充摄像头点位名称 |
| `POST` | `/ai-events/{id}/confirm` | COMMAND | `ai_event` + `safety_alert` + 双方 timeline | **跨聚合事务**：生成 `safety_alert`（决策源 `AI_MODEL`），回写 `linked_alert_id` |
| `POST` | `/ai-events/{id}/false-positive`| COMMAND | `ai_event` + `ai_event_timeline` | 标记误报，记录 `false_reason`，不生成 Alert |
| `POST` | `/ai-events/{id}/uncertain` | COMMAND | `ai_event` + `ai_event_timeline` | 标记存疑待进一步研判 |
| `POST` | `/ai-events/{id}/assign` | COMMAND | `ai_event` + `ai_event_timeline` | 写入 `assignee_user_code` 与姓名快照 |
| `POST` | `/ai-events/{id}/process` | COMMAND | `ai_event` + `ai_event_timeline` | 状态机推进入处理中 |
| `POST` | `/ai-events/{id}/close` | COMMAND | `ai_event` + `ai_event_timeline` | AI 事件闭环 |
| `POST` | `/ai-events/simulate` | COMMAND | `ai_event` + `ai_event_timeline` | 演示数据生成，与正式事件同表 |
| `GET` | `/cameras` | READ | `device_camera` | 摄像头台账及最新在线/健康状态 |

---

## 2. 告警处置（AlertController，/api/v1/alerts）

| 方法 | 端点 | 类型 | 数据表来源 | 说明与演进事实 |
| :--- | :--- | :---: | :--- | :--- |
| `GET` | `/metrics` | AGGREGATE | `safety_alert` 动态聚合 | SQL `COUNT(*)` 按状态/风险分桶，**不建独立 metrics 表** |
| `GET` | `/` | READ | `safety_alert` | `AlertQuery` 分页，走 `idx_safety_alert_list_query` 复合索引 |
| `GET` | `/{id}` | READ | `safety_alert` 级联子表 | 单事务级联拉取 timeline、evidence、treatment、linkage |
| `POST` | `/{id}/confirm` | COMMAND | `safety_alert` + `safety_alert_timeline` | 人工确认，状态置为 `CONFIRMED` |
| `POST` | `/{id}/assign` | COMMAND | `safety_alert` + `safety_alert_timeline` | 派单：落库 `assignee_user_code` 及姓名快照 |
| `POST` | `/{id}/start` | COMMAND | `safety_alert` + `safety_alert_timeline` | 移动端开始处置，置 `status_code='PROCESSING'` |
| `POST` | `/{id}/treatment` | COMMAND | `safety_alert` + `safety_alert_treatment` + timeline | 提交现场整改措施与文字说明（1:N 级联插入） |
| `POST` | `/{id}/review` | COMMAND | `safety_alert` + `safety_alert_timeline` | 复核通过并闭环：置 `status_code='CLOSED'`，记录 `closed_at` |
| `POST` | `/{id}/review-reject` | COMMAND | `safety_alert` + `safety_alert_timeline` | 复核驳回：退回 `PENDING_PROCESS`，允许追加处置 |
| `POST` | `/{id}/transfer` | COMMAND | `safety_alert` + `safety_alert_timeline` | 转派：更新 `assignee_user_code`，追加流转审计 |
| `POST` | `/{id}/escalate` | COMMAND | `safety_alert` + `safety_alert_timeline` | **B5-01 核心事实**：升级 `risk_level_code`，记录 `previous_risk_level_code`，**主状态不变，无 ESCALATED** |
| `POST` | `/{id}/takeover` | COMMAND | `safety_alert` + `safety_alert_linkage_step` | 人工接管联动执行 |
| `POST` | `/{id}/linkage` | COMMAND | `safety_alert_linkage` + `safety_alert_linkage_step` | 现场联动执行会话控制（权威在 Alert 侧） |

---

## 3. 分析大盘（AnalyticsController，/api/v1/analytics）

| 方法 | 端点 | 类型 | 数据表来源 | 说明与演进事实 |
| :--- | :--- | :---: | :--- | :--- |
| `GET` | `/dataset` | AGGREGATE | `safety_alert` + `ai_event` 动态统计 | 综合看板指标聚合（KPI/多维分布），**严格不建表** |
| `GET` | `/events` | READ+AGGREGATE | `safety_alert` 分页列表 | 分析用事件明细分页拉取 |
| `GET` | `/devices/{id}` | AGGREGATE | `safety_alert`（按设备号反查）+ `collision_device` | 设备风险综合分析视图 |
| `GET` | `/persons/{id}` | AGGREGATE | `safety_alert`（按人员工号反查）+ `safety_personnel` | 人员卡控综合分析视图 |
| `POST` | `/simulate-surge` | COMMAND | `safety_alert` + `safety_alert_timeline` | 批量写入压测/演示告警 |

---

## 4. 防碰撞控制（CollisionController，/api/v1/collision）

| 方法 | 端点 | 类型 | 数据表来源 | 说明与演进事实 |
| :--- | :--- | :---: | :--- | :--- |
| `GET` | `/devices` | READ | `collision_device` | 设备列表与最新运行态势 |
| `GET` | `/devices/{id}` | READ | `collision_device` | 单设备详情（PairState 实时计算，不建表） |
| `GET` | `/pair` | READ | `collision_device` 两两计算 | 内存几何距离与交会趋势计算，不建物理表 |
| `GET` | `/devices/{id}/distance-trend` | READ | *openGemini（正式）* / 算法样本 | 历史测距时序归 openGemini，openGauss 不建表 |
| `POST` | `/simulate` | COMMAND | `collision_device` + `safety_alert` | 更新设备态，越界/碰撞达标在单事务内创建 `safety_alert` |
| `POST` | `/linkage` | COMMAND | `safety_alert_linkage` + `safety_alert_linkage_step` | 防碰撞现场联动会话推进 |
| `POST` | `/takeover` | COMMAND | `collision_device` + `safety_alert_linkage_step` | 司机人工接管确认 |
| `POST` | `/release-request` | COMMAND | `collision_device` + `safety_alert` | 解除防碰撞互锁申请 |

---

## 5. 电子围栏（FenceController，/api/v1/fences）

| 方法 | 端点 | 类型 | 数据表来源 | 说明与演进事实 |
| :--- | :--- | :---: | :--- | :--- |
| `GET` | `/` | READ | `safety_fence` | 围栏台账与当前生效版本指针 |
| `GET` | `/{id}` | READ | `safety_fence` → `safety_fence_version` → `safety_fence_edge_sync` | 级联查询围栏顶点与下发同步状态 |
| `POST` | `/` | COMMAND | `safety_fence` + `safety_fence_version` | 创建新围栏草稿与 v1.0 版本 |
| `PUT` | `/{id}` | COMMAND | `safety_fence` + `safety_fence_version` | 编辑已有围栏，生成新版本记录 |
| `POST` | `/{id}/submit-review` | COMMAND | `safety_fence` | 状态置为 `TO_REVIEW` |
| `POST` | `/{id}/publish` | COMMAND | `safety_fence` + `safety_fence_version` + `safety_fence_edge_sync` | **发布强一致事务**：切换 `active_version` 并生成各节点下发记录 |
| `POST` | `/{id}/redeliver` | COMMAND | `safety_fence_edge_sync` | 针对特定边缘节点重新下发同步 |
| `POST` | `/{id}/disable` | COMMAND | `safety_fence` | 停用围栏 |
| `POST` | `/simulate-mismatch` | COMMAND | `safety_fence_edge_sync` | 模拟边缘节点版本不一致状态 |

---

## 6. 云边自治与补传（EdgeAutonomyController，/api/v1/edge）

| 方法 | 端点 | 类型 | 数据表来源 | 说明与演进事实 |
| :--- | :--- | :---: | :--- | :--- |
| `GET` | `/link` | READ | `edge_node` | 边缘节点链路、时钟与同步概览 |
| `GET` | `/local-events` | READ | `edge_pending_event` | 拉取待补传队列（按 `edge_occurred_at, event_id` 稳定排序） |
| `GET` | `/reconcile/rules` | READ | `rule_edge_sync` + `fence_edge_sync` | 对账比对云端与边缘版本偏差 |
| `POST` | `/simulate-link` | COMMAND | `edge_node` + `ops_event_log` | 模拟节点断网或恢复上线 |
| `POST` | `/local-events` | COMMAND | `edge_pending_event` | **离线事件入队**：校验 `idempotency_key` 唯一性与 `payload_hash` |
| `POST` | `/recover` | COMMAND | `edge_node` + `edge_pending_event` + `safety_alert` + `ops_event_log` | **逐条原子重放**：离线事件补传建单，标记 `origin='EDGE_REPLAY'` |
| `POST` | `/reconcile/rules` | COMMAND | `rule_edge_sync` + `fence_edge_sync` + `ops_event_log` | 执行版本下发对账修复 |
| `POST` | `/reconcile/time` | COMMAND | `edge_node` + `ops_event_log` | 边缘时钟对账校正 |

---

## 7. 运维中心（OperationsController，/api/v1/ops）

| 方法 | 端点 | 类型 | 数据表来源 | 说明与演进事实 |
| :--- | :--- | :---: | :--- | :--- |
| `GET` | `/overview` | PROJECTION | `edge_node` + `collision_device` + `edge_pending_event` | 运维全景大盘视图组装，不建物理表 |
| `GET` | `/topology` | PROJECTION | `edge_node` + `device_camera` + `collision_device` | 节点设备网络拓扑图组装，不建物理表 |
| `GET` | `/edge-nodes` | READ | `edge_node` | 边缘节点列表与健康态 |
| `GET` | `/edge-nodes/{id}` | READ | `edge_node` | 节点运维详情与自愈时间线 |
| `GET` | `/devices` | READ | `collision_device` + `device_camera` | 现场受控硬件设备台账 |
| `GET` | `/interfaces` | READ | *配置台账* | 接口协议台账组装，非独立事实表 |
| `GET` | `/events` | READ | `ops_event_log` | 系统运维审计日志分页检索 |
| `POST` | `/edge-nodes/{id}/maintain` | COMMAND | `edge_node` + `ops_event_log` | 边缘节点置入维护状态 |
| `POST` | `/devices/{id}/reconnect` | COMMAND | `collision_device` + `ops_event_log` | 触发设备通信链路重连 |
| `POST` | `/simulate` | COMMAND | 各设备表 + `ops_event_log` | 运维故障注入模拟 |

---

## 8. 现场作业人员（PersonnelController，/api/v1/personnel）

| 方法 | 端点 | 类型 | 数据表来源 | 说明与演进事实 |
| :--- | :--- | :---: | :--- | :--- |
| `GET` | `/` | READ | `safety_personnel` | 人员台账与在岗态势分页列表 |
| `GET` | `/live` | READ | `safety_personnel` | 实时态势快照拉取（不建 live 实体表） |
| `GET` | `/{id}` | READ | `safety_personnel` | 人员详细信息 |
| `GET` | `/{id}/track` | READ | *openGemini（正式）* / 算法样本 | 连续轨迹严禁入 openGauss，归 openGemini |
| `GET` | `/{id}/alerts` | READ | `safety_alert` | 按人员工号 `target_object_id` 反查历史告警 |
| `POST` | `/{id}/remind` | COMMAND | `ops_event_log` | 现场安全提醒下发（记录审计日志） |
| `POST` | `/simulate-abnormal` | COMMAND | `safety_personnel` + `safety_alert` | 模拟人员越界/心率异常；越界自动在单事务中创建 `safety_alert` |

---

## 9. 移动端处置工作台（MobileController，/api/v1/mobile）

| 方法 | 端点 | 类型 | 数据表来源 | 说明与演进事实 |
| :--- | :--- | :---: | :--- | :--- |
| `GET` | `/home` | PROJECTION | `safety_alert` | 查询责任人为当前用户的未关闭告警，组装首页工作台，不建表 |
| `POST` | `/incidents/{id}/accept` | COMMAND | `safety_alert` + `safety_alert_timeline` | 移动端接单：更新 `accepted_at` 与 `mobile_stage_code='ACCEPTED'` |
| `POST` | `/incidents/{id}/arrive` | COMMAND | `safety_alert` + `safety_alert_timeline` | 移动端到场：更新 `arrived_at` 与 `mobile_stage_code='ARRIVED'` |

---

## 10. 大屏管控（OverviewController，/api/v1/overview）

| 方法 | 端点 | 类型 | 数据表来源 | 说明与演进事实 |
| :--- | :--- | :---: | :--- | :--- |
| `GET` | `/summary` | PROJECTION | `safety_alert` + `ai_event` + 人员设备态 | 大屏顶栏 KPI 综合聚合，不建物理表 |
| `GET` | `/map` | PROJECTION | `safety_personnel` + `collision_device` + `safety_fence` | 地图图层要素组装，不建物理表 |
| `GET` | `/alerts/feed` | PROJECTION | `safety_alert` + `safety_alert_timeline` | 实时告警滚动信息流组装，不建物理表 |
| `GET` | `/risk-trend` | AGGREGATE | `safety_alert` 按日分桶 | 风险趋势统计分析，不建表 |
| `GET` | `/risk-distribution` | AGGREGATE | `safety_alert` 按事件类型分桶 | 风险类型分布统计，不建表 |
| `GET` | `/alerts/{id}` | READ | `safety_alert` | 大屏告警详情拉取 |
| `POST` | `/simulate-risk` | COMMAND | `safety_alert` + `safety_alert_timeline` | 模拟大屏高危告警演示 |

---

## 11. 指挥中心第二屏（ScreenController，/api/v1/screen）

| 方法 | 端点 | 类型 | 数据表来源 | 说明与演进事实 |
| :--- | :--- | :---: | :--- | :--- |
| `GET` | `/overview` | PROJECTION | `safety_alert` + `ai_event` + 设备态 | 委托 `SafetyProjectionService` 组装，不建独立表 |
| `GET` | `/alerts/feed` | PROJECTION | `safety_alert` | 关键告警轮播流，不建独立表 |
| `GET` | `/critical` | PROJECTION | `safety_alert` | 高危且未处置告警筛选列表，不建独立表 |

---

## 12. 安全卡控规则（RuleController，/api/v1/rules）

| 方法 | 端点 | 类型 | 数据表来源 | 说明与演进事实 |
| :--- | :--- | :---: | :--- | :--- |
| `GET` | `/metrics` | AGGREGATE | `safety_rule` 统计聚合 | 规则分类与启用率统计，不建表 |
| `GET` | `/` | READ | `safety_rule` + `safety_rule_area` | 规则列表与适用区域拉取 |
| `GET` | `/{id}` | READ | `safety_rule` 级联全量子表 | 级联查询当前版本参数与边缘同步状态 |
| `GET` | `/{id}/versions` | READ | `safety_rule_version` | 规则历史版本列表（包含差异 diffs） |
| `POST` | `/` | COMMAND | `safety_rule` + `safety_rule_area` + `safety_rule_version` | 创建新规则初版草稿 |
| `PUT` | `/{id}` | COMMAND | `safety_rule` + `safety_rule_version` | **B5-04 核心事实**：编辑 ACTIVE 规则不改变主表状态，在版本子表新增草稿 |
| `POST` | `/{id}/submit` | COMMAND | `safety_rule` | 提交审批，置 `status_code='REVIEW'` |
| `POST` | `/{id}/approve` | COMMAND | `safety_rule` | 审批通过，置 `status_code='APPROVED'` |
| `POST` | `/{id}/reject` | COMMAND | `safety_rule` | 审批驳回，退回草稿 |
| `POST` | `/{id}/publish` | COMMAND | `safety_rule` + `safety_rule_version` + `safety_rule_edge_sync` | **发布事务**：切换主表 `active_version`，下发同步记录 |
| `POST` | `/{id}/redeliver` | COMMAND | `safety_rule_edge_sync` | 重新向指定节点下发同步规则 |
| `POST` | `/{id}/rollback` | COMMAND | `safety_rule` + `safety_rule_version` | 回滚：复制目标版本为新版本并切换主版本指针 |
| `POST` | `/{id}/disable` | COMMAND | `safety_rule` | 停用规则 |
| `POST` | `/simulate-mismatch` | COMMAND | `safety_rule_edge_sync` | 模拟边缘版本不匹配 |
| `POST` | `/simulate` | COMMAND | `safety_rule` | 演示规则数据初始化 |
| `POST` | `/conflict-check` | READ | `safety_rule` + `safety_rule_area` | 规则区域与阈值重叠冲突计算，不建物理表 |

---

## 13. 统计汇总

- **COMMAND / 写事务端点**：约 40 个，全部收敛到 22 张活动实体表，且严格保证聚合内与跨聚合的本地事务一致性；
- **READ 明细/过滤端点**：约 26 个，由实体表及其子表支撑；
- **AGGREGATE / PROJECTION 端点**：约 30 个，**严格不建独立实体表**，全部通过 SQL 聚合函数或多表联合视图在查询时动态组装，杜绝表膨胀与数据冗余。
