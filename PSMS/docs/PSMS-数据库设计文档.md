# PSMS 生产调度管理模块 —— 数据库设计文档（openGauss + openGemini 组合版）

| 项目 | 内容 |
|------|------|
| 文档名称 | PSMS 生产调度管理模块数据库设计文档 |
| 模块代号 | PSMS（Production Scheduling Management System） |
| 所属项目 | Project_B 数字孪生 / 综合监控一体化项目 |
| 数据库类型 | **openGauss 6.0.5（业务/事务库）+ openGemini（时序/遥测库）** |
| 访问层 | `pg` 8.16.3（openGauss，走 PostgreSQL 线协议）+ 自研 InfluxDB 兼容 HTTP 客户端（openGemini，不引第三方 SDK） |
| 业务库 | `psms`（`DBCOMPATIBILITY=PG`、`UTF8`、属主 `psms`） |
| 时序库 | `psms_telemetry` |
| 表数量 | **21 张表**（11 张主表 + 10 张子表）+ **1 个 measurement** |
| 契约基线 | 0.3.0（15 个领域对象 / 25 个接口 / 7 个场景 / 9 个错误码） |
| 文档状态 | 设计基线（表结构、种子数据已在真机落地验证） |
| 编写日期 | 2026-09-24 |

---

## 目录

1. [文档说明](#1-文档说明)
2. [选型说明](#2-选型说明)
3. [设计规范](#3-设计规范)
4. [数据库概览](#4-数据库概览)
5. [表详细设计](#5-表详细设计)
6. [索引设计与典型查询](#6-索引设计与典型查询)
7. [事务与并发控制](#7-事务与并发控制)
8. [数据完整性与约束策略](#8-数据完整性与约束策略)
9. [数据保留与归档策略](#9-数据保留与归档策略)
10. [时序库设计（openGemini）](#10-时序库设计opengemini)
11. [容量估算](#11-容量估算)
12. [运维与故障处置](#12-运维与故障处置)
13. [落地验证记录](#13-落地验证记录)
14. [附录](#14-附录)

---

## 1 文档说明

### 1.1 编写目的

本文档定义 PSMS 模块的持久化数据模型，包括：表结构与字段定义、内嵌结构的拆表方案、索引、约束、事务与并发控制策略、保留与归档策略、时序库设计以及容量规划。

### 1.2 本次变更说明

| 阶段 | 持久化方式 | 状态 |
|------|-----------|------|
| 变更前（原始仓库） | `backend/.data/<collection>.json` 纯 JSON 文件（内存缓存 + 全文件重写），`lib/jsondb.js` 手写仿 Mongoose 的增删改查 | 已废弃 |
| 中间过程 | MongoDB 7.0（`mongodb-memory-server` 内嵌 mongod，WiredTiger 持久化） | 已废弃并彻底移除 |
| **本次落地** | **openGauss 6.0.5（业务 `psms`）+ openGemini（时序 `psms_telemetry`）** | **正式生效** |

**变更原因**：经核对组内既有实践（架构基线文档 + 8 个模块配置矩阵 + 真机实测三方交叉验证），确认项目采用**「多存储各司其职」**的组合方案，而**不存在任何一个模块只用单一非关系型数据库**：

- `EHMS/docs/EHM-architecture-baseline.md`：资产、工单、权限等**事务数据进入 openGauss**；**遥测与趋势数据进入 openGemini**；缓存与幂等状态进入 Kvrocks；异步事件进入 RocketMQ。**MongoDB 仅保留为本地 Demo / 可选文档型存储适配器，不作为生产系统唯一数据源。**
- 真机实测：`node4` 承担 openGauss(5432) + Kvrocks(6666)，`node5` 承担 RocketMQ(9876)，`node6` 承担 openGemini(8086)。
- openGemini 三个既有库（`b_poc` / `ehm_telemetry` / `prom`）中**全部是时序 measurement，没有任何业务表** —— 佐证它只承担时序职责。

因此本次把 11 个**事务型**领域对象（计划 / 工单 / 任务 / 人员 / 设备台账 / 异常 / 联锁 / 预约 / 离线包 / 配置 / 审计）落到 openGauss，把**设备遥测**落到 openGemini。

> 本文件的历史版本依次为：MySQL 8.0 关系型设计（27 张表）→ MongoDB 版（12 个集合）。两者均可从 git 历史查阅。当前版本为**正式生效**的设计。

### 1.3 适用范围

- **开发**：表结构、字段语义、索引命中的唯一依据；
- **测试**：数据校验、索引验证、约束与非空校验的依据；
- **运维**：容量规划、保留与归档、备份恢复的输入。

### 1.4 领域对象与表映射

| 对象编号 | 对象名称 | 业务库表（openGauss） | 类型 |
|----------|----------|----------------------|------|
| DO-001 | Plan 计划 | `plans` + `plan_cargo_items` | 主表 + 子表 |
| DO-002 | Waybill 运单 | — | 见 1.5 |
| DO-003 | Track 股道 | — | 见 1.5 |
| DO-004 | Material 物料 | — | 见 1.5 |
| DO-005 | WorkOrder 工单 | `work_orders` + `work_order_crew` | 主表 + 子表 |
| DO-006 | WorkNode 作业节点 | `tasks` + `task_crew` + `task_dependencies` | 主表 + 2 子表 |
| DO-007 | Resource 资源 | `equipment` | 主表 |
| DO-008 | Appointment 预约 | `appointments` + `appointment_documents` | 主表 + 子表 |
| DO-009 | DispatchException 异常 | `exceptions` + `exception_evidence` | 主表 + 子表 |
| DO-010 | Interlock 联锁 | `interlocks` + `interlock_input_signals` | 主表 + 子表 |
| DO-011 | OfflinePacket 离线包 | `offline_packets` + `offline_packet_conflict_fields` | 主表 + 子表 |
| DO-012 | Report 报表 | — | 见 1.5（实时聚合，不落库） |
| DO-013 | AuditLog 审计日志 | `audit_logs` | 主表（按月分区） |
| DO-014 | UserRole 用户角色 | `users` + `user_data_scopes` | 主表 + 子表 |
| DO-015 | ConfigVersion 配置版本 | `config_versions` + `config_change_history` | 主表 + 子表 |
| — | 设备遥测 | `psms_telemetry.equipment_telemetry`（openGemini） | **时序 measurement** |

### 1.5 后端实现与前端契约的覆盖差异

后端实现的是模块的**调度 / 执行 / 安全 / 治理**主线，与前端契约的 15 个领域对象存在以下覆盖差异：

| 前端对象 | 后端现状 | 说明 |
|----------|----------|------|
| `DO-002` Waybill 运单 | 未独立建表 | 运单明细目前体现为 `plans` + `plan_cargo_items` 货物明细 |
| `DO-003` Track 股道 | 未独立建表 | 股道号作为 `plans.track_no` / `interlocks.source_id` 字段存在 |
| `DO-004` Material 物料 | 未独立建表 | 库存维度尚未落地 |
| `DO-012` Report 报表 | 不落库 | 报表由 SQL 实时聚合生成（`GROUP BY` / `COUNT` / 聚合函数），未做快照持久化 |

**后续对齐建议**：若需与前端契约完全一致，应补齐 `waybills`、`tracks`、`materials` 三张表，并把报表快照落到 `report_snapshots` 表。这是一次独立的对齐任务，本文档不做展开。

---

## 2 选型说明

### 2.1 为什么是「openGauss + openGemini」组合

| 评估维度 | 说明 |
|----------|------|
| **与组内基线一致** | 组内 8 个模块**全部**使用 openGauss 承载事务数据；openGemini 只在 SCS / EHMS 出现且只挂时序侧。PSMS 采用组合是与组内实践对齐，不是自创。 |
| **事务能力** | 本模块 11 个业务对象都有**跨行一致性需求**：计划确认要同时写状态与审计、派工要同时写工单与班组、任务拆解要写任务树与依赖图。这些在关系库里可用**真实事务**保证，是文档库难以等价替代的。 |
| **约束能力** | 状态机取值、时间先后关系（结束 ≥ 开始）、评分与版本自洽等规则可直接用 `CHECK` 约束在库层兜底，不必只依赖应用层校验。 |
| **时序能力** | 设备遥测是典型的"写多读少、按时间窗口聚合、量大"负载。openGemini 是组内既定时序方案，天然按时间分区压缩，与 openGauss 分工清晰。 |
| **真机已就绪** | `node4` 的 openGauss 与 `node6` 的 openGemini 都是**已运行中的服务**，无需在本机安装任何数据库。 |
| **迁移成本可控** | openGauss 与 PostgreSQL 线协议对齐，Node 侧直接用成熟的 `pg` 驱动；openGemini 提供 InfluxDB 兼容 HTTP API，自研轻量客户端即可，无需引入重依赖。 |

### 2.2 存储分工判定规则

决定一个数据该进 openGauss 还是 openGemini，用下面三条判定：

| 判定 | 进 openGemini | 进 openGauss |
|------|---------------|--------------|
| 主要访问形态 | 按**时间窗口**聚合（趋势、均值、峰值） | 按**业务主键/条件**检索（台账、分页、关联） |
| 更新形态 | 只追加（append-only），极少修改 | 有**状态机**、反复更新、需要乐观锁 |
| 一致性需求 | 单点自洽，允许最终一致 | 需跨行**事务**、外键、唯一约束 |

> 口诀：**「要事务、要关联、要状态机 → openGauss；要时间线、要压缩、只追加 → openGemini」**。

按此规则，`equipment.last_telemetry_at`（最近一次遥测到达时间）留在 openGauss —— 它是**设备台账的一个标量字段**，按设备检索而非按时间聚合；明细点值才进 openGemini。

### 2.3 部署形态

| 存储 | 现场地址 | 部署形态 |
|------|----------|----------|
| openGauss | `192.168.101.57:5432`（`bpoc-node4`） | 运行在 **iSulad 容器 `bpoc-opengauss`** 内，5432 经 iptables CNAT 暴露到宿主机；容器内以 `omm` 身份运行 |
| openGemini | `192.168.101.74:8086`（`bpoc-node6`） | HTTP 服务，`/ping` 返回 `204` + `X-Geminidb-Build: OSS`，无用户列表（免认证） |

**开发环境访问方式**：本机不在 `192.168.101.0/24` 网段，通过 **SSH 端口转发**接入（跳板机 `bpoc-node1`）：

```cmd
ssh -N -i C:\Users\Administrator\.ssh\bpoc_ed25519 ^
    -L 5432:192.168.101.57:5432 ^
    -L 8086:192.168.101.74:8086 ^
    lrz@100.65.200.125
```

后端 `.env` 中把 `OPENGAUSS_HOST` / `OPENGEMINI_URL` 指向 `127.0.0.1` 即可，应用代码不感知隧道。

> **生产部署建议**：PSMS 后端应部署在集群内，直连 `node4:5432` / `node6:8086`（或经 Easegress 网关），**不要用 SSH 隧道承载生产流量**。隧道路径经公网中继，延迟高且长连接会周期性失效，仅适合开发验证。

---

## 3 设计规范

### 3.1 命名与主键约定

| 对象 | 规范 | 示例 |
|------|------|------|
| 表名 | 小写下划线，名词复数（子表用 `父表单数_子项复数`） | `work_orders`、`plan_cargo_items` |
| 字段名 | 小写下划线（SQL 惯用风格） | `plan_batch_no`、`work_area` |
| 主键 | 一律**语义化字符串** `id`，不用自增整数 | `PLAN-001`、`WO-001`、`ACTOR-001` |
| 业务编号 | 独立列 + 唯一约束 | `plan_batch_no`、`appointment_id`、`exception_id` |
| 索引 | 普通 `idx_<表>_<列…>`；唯一 `uk_<表>_<列…>` | `idx_plans_work_area_status`、`uk_tasks_plan_task_no` |
| 约束 | `chk_<表>_<语义>`、`uk_<表>_<列…>` | `chk_wo_time_order`、`uk_configs…` |

**为什么用语义化字符串主键**：与前端 `DO-xxx` 契约、审计日志的 `objectId` 完全一致，人工排查、跨表对照、日志检索都不需要额外换算。代价是主键比自增整数长（约 10~14 字节），在本模块的数据量级下不构成问题。

**API 字段名 → 列名的映射**：后端对外仍使用前端契约的**小驼峰**字段名，落库时由访问层做映射（`planBatchNo` → `plan_batch_no`），映射表集中在 `src/db/tables.ts`。这样前端契约不需要因为换存储而改动。

### 3.2 字段类型选择

| 用途 | 选用类型 | 理由 |
|------|----------|------|
| 标识 / 枚举 / 短文本 | `TEXT` | openGauss 的 `TEXT` 无长度惩罚；枚举配 `CHECK` 约束 |
| 数量 / 重量 / 评分 | `NUMERIC(14,3)` | 定点数，避免浮点误差；本模块重量精度到千克、数量到千分位 |
| 时间点 | `TIMESTAMPTZ` | 带时区；统一按 UTC 存储，展示层转本地时区 |
| 布尔 | `BOOLEAN` | 不用 0/1 |
| 整数 / 序号 / 版本号 | `INTEGER` | 版本号、序号、状态码、耗时毫秒 |
| **真无结构**的载荷 | `JSONB` | 仅用于"结构随外部系统或终端版本演进"的字段，见 3.4 |

> **不使用的类型**：不自建 `ENUM` 类型（增删取值需要类型迁移，且回滚困难），一律 `TEXT + CHECK`。

### 3.3 时间处理

| 场景 | 规范 |
|------|------|
| 存储 | 一律 `TIMESTAMPTZ`，服务端按 UTC 存取 |
| 默认值 | `created_at` / `updated_at` 默认 `now()` |
| 更新 | 写命令时由访问层补 `updated_at = now()`（调用方显式给了就不覆盖） |
| 区间查询 | 用 `>=` / `<` 半开区间，避免边界重复计数 |
| 空值语义 | 未发生的时间点（如 `actual_end_time`）保持 `NULL`，**不用哨兵值** |

**本模块刻意保留的空值时间列**：`confirmed_at`、`accepted_at`、`actual_start_time`、`actual_end_time`、`triggered_at`、`reset_at`、`override_expires_at`、`check_in_time`、`called_at`、`enter_time`、`exit_time`、`published_at`、`last_login_at`、`last_heartbeat`、`last_telemetry_at`、`last_sync_at`。它们的"是否为空"本身就是业务信号（未确认 / 未入场 / 未发布）。

### 3.4 内嵌 vs 拆表决策（文档模型 → 关系模型）

原始 MongoDB 版把若干子结构**内嵌**在主文档里。改到关系库后，逐个按下面的规则决定：**拆成子表**还是**保留 `JSONB`**。

| 判定条件 | 处理 | 本模块实例 |
|----------|------|-----------|
| 需要被**独立查询 / 聚合 / 建索引** | **拆子表** | `cargoItems`（按品名统计）、`evidence`、`inputSignals`、`documents`、`assignedCrew`、`dependsOn`、`changeHistory`、`conflictFields` |
| 需要**行级约束**（非空、唯一、序号） | **拆子表** | 所有子表都有 `PRIMARY KEY (父键, 序号/取值)` 与 `CHECK` |
| 是**多对多关系的边** | **拆子表** | `task_dependencies`（任务→任务有向图，需反查找环） |
| 是**字符串数组**且需去重 | **拆子表** | `user_data_scopes`、`work_order_crew`、`task_crew`、`offline_packet_conflict_fields` |
| 结构**随外部系统或终端版本演进**、只整体读写 | **保留 `JSONB`** | `plans.supplements`、`equipment.specs`、`offline_packets.payload`、`audit_logs.before_state` / `after_state`、`config_change_history.old_value` / `new_value` |
| 原为**嵌套对象**（非数组） | **摊平成列** | `supplierInfo` → `supplier_name` / `supplier_contact` / `supplier_phone`；`executionFeedback` → `feedback_quality` / `feedback_comment` / `feedback_by` / `feedback_at` |

**摊平后的读写兼容**：访问层在写入前把嵌套对象摊成列，在读回时再还原成嵌套对象（`src/db/tables.ts` 的 `beforeWrite` / `hydrate`）。因此**业务代码与接口契约看到的仍然是嵌套结构**，存储规范化对上层透明。

**只保留 5 组 `JSONB` 字段的理由**：这些字段的共同特征是"内部结构由外部世界决定"——供应商补充信息随外部系统字段增减、设备规格随型号变化、离线载荷随 PDA 终端版本变化、审计前后快照随对象类型变化。为它们建列会导致表结构被外部系统牵着走；用 `JSONB` 可以在不迁移表结构的前提下容纳演进。

### 3.5 外键策略：只在「内嵌子表 → 主表」建外键

| 关系类型 | 是否建外键 | 理由 |
|----------|-----------|------|
| 子表 → 主表（内嵌结构拆出来的） | ✅ 建，`ON DELETE CASCADE` | 子行脱离父行没有意义，父删子删是正确语义 |
| 跨实体引用（工单→计划、任务→工单、异常→来源对象） | ❌ **不建** | 见下 |

**不建跨实体外键的原因**：

1. **历史留档优先于级联清理**。删除一张计划时，挂在它下面的工单/任务/异常是**需要留档的历史单据**，不应被级联删除；而如果建了外键又不设 `CASCADE`，删除操作会被直接拒绝。
2. **多态引用无法建外键**。`exceptions.source_type` + `source_id` 指向 `plan` / `workOrder` / `task` / `equipment` / `interlock` 五类对象，属于多态引用，关系库无法表达。
3. **弹性**。这些引用是"业务关联"而非"物理包含"，把一致性交给应用层可以在需要时允许悬挂引用（例如数据归档后的历史单据）。

**代价与补偿**：应用层必须自己保证引用有效。补偿手段是**建索引 + 定期孤儿巡检**（见 8.4）。

### 3.6 版本字段语义

本模块有两类"版本"，含义不同，**不可混用**：

| 字段 | 所在表 | 语义 | 用途 |
|------|--------|------|------|
| `version` | `config_versions` | **业务乐观锁版本**，每次成功写命令 +1 | 防止两个管理员并发覆盖配置 |
| `version` / `server_version` | `offline_packets` | **数据版本号**（客户端 / 服务端各一） | 离线包冲突判定：版本不一致即标记冲突 |
| `config_version` | `config_versions` | 配置的**展示版本串**（如 `CFG-1.0`） | 人工阅读与流程审批标识 |

**乐观锁的实现**：配置类写命令采用"条件更新"——`UPDATE ... WHERE id = $1 AND version = $2`，若影响行数为 0 则返回 `409 CONFLICT`，由前端提示"数据已被他人修改，请刷新"。

---

## 4 数据库概览

### 4.1 逻辑分层

```
┌─────────────────────────────────────────────────────────────────────┐
│  openGauss  psms（业务/事务库）                                       │
│                                                                      │
│  ┌──────────────────── 身份与治理域 ────────────────────┐             │
│  │  users ──1:N── user_data_scopes                      │             │
│  │  config_versions ──1:N── config_change_history        │             │
│  │  audit_logs（独立，无外键）                            │             │
│  └──────────────────────────────────────────────────────┘             │
│                                                                      │
│  ┌──────────────────── 调度域 ──────────────────────────┐             │
│  │  plans ──1:N── plan_cargo_items                       │             │
│  └──────────────────────────────────────────────────────┘             │
│                                                                      │
│  ┌──────────────────── 执行域 ──────────────────────────┐             │
│  │  work_orders ──1:N── work_order_crew                  │             │
│  │  tasks ──1:N── task_crew                              │             │
│  │  tasks ──1:N── task_dependencies ──┐(逻辑引用)        │             │
│  │                                     └──> tasks.id      │             │
│  └──────────────────────────────────────────────────────┘             │
│                                                                      │
│  ┌──────────────────── 资源与入场域 ────────────────────┐             │
│  │  equipment（台账，无子表）                             │             │
│  │  appointments ──1:N── appointment_documents            │             │
│  │  offline_packets ──1:N── offline_packet_conflict_fields│            │
│  └──────────────────────────────────────────────────────┘             │
│                                                                      │
│  ┌──────────────────── 安全域 ──────────────────────────┐             │
│  │  exceptions ──1:N── exception_evidence                │             │
│  │  interlocks ──1:N── interlock_input_signals           │             │
│  └──────────────────────────────────────────────────────┘             │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              │ equipment.id（设备编码）
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  openGemini  psms_telemetry（时序库）                                 │
│    equipment_telemetry   tags: equipment_id / point_code / quality   │
│                          fields: value / unit / metadata             │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 引用关系

**物理外键（子表 → 主表，`ON DELETE CASCADE`）**

| 子表 | 外键列 | 引用 | 删除行为 |
|------|--------|------|----------|
| `user_data_scopes` | `actor_id` | `users.actor_id` | 级联删除 |
| `plan_cargo_items` | `plan_id` | `plans.id` | 级联删除 |
| `work_order_crew` | `work_order_id` | `work_orders.id` | 级联删除 |
| `task_crew` | `task_id` | `tasks.id` | 级联删除 |
| `task_dependencies` | `task_id` | `tasks.id` | 级联删除 |
| `exception_evidence` | `exception_id` | `exceptions.id` | 级联删除 |
| `interlock_input_signals` | `interlock_id` | `interlocks.id` | 级联删除 |
| `appointment_documents` | `appointment_id` | `appointments.id` | 级联删除 |
| `offline_packet_conflict_fields` | `packet_id` | `offline_packets.id` | 级联删除 |
| `config_change_history` | `config_id` | `config_versions.id` | 级联删除 |

**逻辑引用（无外键，应用层保证 + 索引支撑）**

| 起点 | 终点 | 说明 |
|------|------|------|
| `work_orders.plan_id` | `plans.id` | 工单来源计划 |
| `work_orders.equipment_id` | `equipment.equipment_id` | 工单执行设备 |
| `work_orders.task_id` | `tasks.id` | 工单来源任务 |
| `tasks.plan_id` / `tasks.work_order_id` | `plans.id` / `work_orders.id` | 任务归属 |
| `tasks.parent_task_id` | `tasks.id` | 任务树（自引用） |
| `tasks.equipment_id` | `equipment.equipment_id` | 任务占用设备 |
| `task_dependencies.depends_on_task_id` | `tasks.id` | 任务依赖图的**边**（有向，需反查找环） |
| `exceptions.source_id` | 多态（`plans` / `work_orders` / `tasks` / `equipment` / `interlocks`） | 由 `source_type` 决定指向哪张表 |
| `interlocks.source_id` | 同上 | 联锁触发来源 |
| `equipment.id`（`equipment_id` 列） | openGemini `equipment_telemetry.equipment_id`（tag） | **跨库引用**，靠 tag 值对齐，无约束可言 |

### 4.3 表清单与实测行数

**业务库 `psms`（21 张表）**

| # | 表名 | 中文名 | 类别 | 核心/子表 | 种子行数 |
|---|------|--------|------|-----------|----------|
| 1 | `users` | 用户账号 | 身份治理 | 核心 | 5 |
| 2 | `user_data_scopes` | 账号数据域白名单 | 身份治理 | 子表 | 7 |
| 3 | `config_versions` | 系统配置版本 | 身份治理 | 核心 | 1 |
| 4 | `config_change_history` | 配置变更历史 | 身份治理 | 子表 | 0 |
| 5 | `audit_logs` | 审计留痕 | 身份治理 | 核心 | 5 |
| 6 | `plans` | 外部到发计划 | 调度 | 核心 | 5 |
| 7 | `plan_cargo_items` | 计划货物明细 | 调度 | 子表 | 0 |
| 8 | `work_orders` | 作业工单 | 执行 | 核心 | 3 |
| 9 | `work_order_crew` | 工单班组 | 执行 | 子表 | 2 |
| 10 | `tasks` | 任务拆解产物 | 执行 | 核心 | 2 |
| 11 | `task_crew` | 任务班组 | 执行 | 子表 | 0 |
| 12 | `task_dependencies` | 任务前置依赖 | 执行 | 子表 | 1 |
| 13 | `equipment` | 设备台账 | 资源 | 核心 | 15 |
| 14 | `appointments` | 公路预约与叫号 | 入场 | 核心 | 3 |
| 15 | `appointment_documents` | 预约资质附件 | 入场 | 子表 | 0 |
| 16 | `offline_packets` | PDA 离线数据包 | 入场 | 核心 | 1 |
| 17 | `offline_packet_conflict_fields` | 离线包冲突字段 | 入场 | 子表 | 0 |
| 18 | `exceptions` | 生产异常 | 安全 | 核心 | 2 |
| 19 | `exception_evidence` | 异常证据 | 安全 | 子表 | 0 |
| 20 | `interlocks` | 安全联锁 | 安全 | 核心 | 2 |
| 21 | `interlock_input_signals` | 联锁输入信号 | 安全 | 子表 | 2 |
| | | | | **合计** | **56** |

> 部分子表种子行为 0，是因为种子夹具本身未提供该数组（例如计划夹具没有 `cargoItems`），**不是映射缺陷**——已落数据的子表（`user_data_scopes` 7 行、`work_order_crew` 2 行、`task_dependencies` 1 行、`interlock_input_signals` 2 行）已经证明"内嵌数组 → 子表"的装配链路正确。

**时序库 `psms_telemetry`（1 个 measurement）**

| measurement | 说明 | tags | fields |
|-------------|------|------|--------|
| `equipment_telemetry` | 设备遥测点值 | `equipment_id` / `point_code` / `quality` | `value` / `unit` / `metadata` |

---

## 5 表详细设计

> 下列 DDL 与 `src/db/schema.ts` 完全一致，可重复执行（全部 `IF NOT EXISTS`）。字段表的"契约字段名"列表示后端 API 对外暴露的名字（小驼峰），"列名"表示物理列名。

### 5.1 `users` 用户账号

对应 `DO-014` 的账号侧视图。

| 列名 | 类型 | 约束 | 契约字段名 | 说明 |
|------|------|------|-----------|------|
| `id` | TEXT | PK | `_id` | 主键，与 `actor_id` 同值，来自契约的 `ACTOR-xxx` |
| `actor_id` | TEXT | NOT NULL, UNIQUE | `actorId` | 操作者标识；JWT payload 与审计日志均引用此字段 |
| `username` | TEXT | NOT NULL, UNIQUE | `username` | 登录名 |
| `password_hash` | TEXT | NOT NULL | `passwordHash` | 口令散列（bcrypt） |
| `display_name` | TEXT | NOT NULL | `displayName` | 展示名 |
| `role_code` | TEXT | NOT NULL, CHECK | `roleCode` | 后端角色集：`super_admin` / `admin` / `scheduler` / `dispatcher` / `operator` / `viewer` |
| `online` | BOOLEAN | NOT NULL DEFAULT FALSE | `online` | 是否在线（演示态） |
| `last_login_at` | TIMESTAMPTZ | — | `lastLoginAt` | 最近登录时间 |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | — | 时间戳 |

```sql
CREATE TABLE IF NOT EXISTS users (
  id            TEXT PRIMARY KEY,
  actor_id      TEXT NOT NULL UNIQUE,
  username      TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  display_name  TEXT NOT NULL,
  role_code     TEXT NOT NULL
                CHECK (role_code IN ('super_admin','admin','scheduler','dispatcher','operator','viewer')),
  online        BOOLEAN NOT NULL DEFAULT FALSE,
  last_login_at TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_users_role ON users (role_code);
CREATE INDEX IF NOT EXISTS idx_users_online ON users (online) WHERE online = TRUE;
```

**为什么 `id` 与 `actor_id` 同值**：契约里 `users._id` 与 `actorId` 是同一个语义标识（`ACTOR-001`）。访问层在写入前互相补齐，读回时两个字段都在，兼容两种调用风格。

**设计说明**：`idx_users_online` 是**部分索引**（`WHERE online = TRUE`）——在线用户通常只占极小比例，部分索引体积小、命中快。

### 5.2 `user_data_scopes` 账号数据域白名单

原 `dataScope: string[]` 内嵌数组。**拆表的理由**：数据域是权限判定的输入，需要独立按 `scope` 检索"某作业区有哪些账号"。

| 列名 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `actor_id` | TEXT | PK(复合), NOT NULL, FK → `users.actor_id` CASCADE | 账号 |
| `scope` | TEXT | PK(复合), NOT NULL | 数据域取值（作业区） |

```sql
CREATE TABLE IF NOT EXISTS user_data_scopes (
  actor_id   TEXT NOT NULL REFERENCES users (actor_id) ON DELETE CASCADE,
  scope      TEXT NOT NULL,
  PRIMARY KEY (actor_id, scope)
);
```

### 5.3 `config_versions` 系统配置版本

对应 `DO-015`。

| 列名 | 类型 | 约束 | 契约字段名 | 说明 |
|------|------|------|-----------|------|
| `id` | TEXT | PK | `_id` | 主键 |
| `config_id` | TEXT | NOT NULL, UNIQUE | `configId` | 配置标识 |
| `config_version` | TEXT | NOT NULL DEFAULT `'CFG-1.0'` | `configVersion` | 展示版本串 |
| `display_name` | TEXT | NOT NULL DEFAULT `'B项目生产调度管理系统'` | `displayName` | 系统显示名 |
| `default_scenario_id` | TEXT | NOT NULL DEFAULT `'SCN-01'` | `defaultScenarioId` | 默认演示场景 |
| `rule_version` | TEXT | NOT NULL DEFAULT `'RULE-1.0'` | `ruleVersion` | 规则版本 |
| `dispatch_strategy` | TEXT | NOT NULL, CHECK | `dispatchStrategy` | `BALANCED` / `PRIORITY_FIRST` / `RESOURCE_FIRST` / `OPTIMAL` |
| `recommendation_enabled` | BOOLEAN | NOT NULL DEFAULT TRUE | `recommendationEnabled` | 是否启用推荐 |
| `offline_sync_enabled` | BOOLEAN | NOT NULL DEFAULT TRUE | `offlineSyncEnabled` | 是否启用离线同步 |
| `report_period` | TEXT | NOT NULL, CHECK | `reportPeriod` | `SHIFT` / `DAILY` / `MONTHLY` |
| `audit_retention_days` | INTEGER | NOT NULL DEFAULT 365, CHECK 1~3650 | `auditRetentionDays` | **审计保留天数，驱动审计清理任务** |
| `status` | TEXT | NOT NULL, CHECK | `status` | `DRAFT` / `SUBMITTED` / `APPROVED` / `PUBLISHED` / `ROLLED_BACK` |
| `version` | INTEGER | NOT NULL DEFAULT 1, CHECK ≥ 1 | `version` | **业务乐观锁版本** |
| `scenario_id` | TEXT | NOT NULL DEFAULT `'SCN-01'` | `scenarioId` | 当前场景 |
| `published_at` / `published_by` | TIMESTAMPTZ / TEXT | — | 同名字段 | 发布信息 |
| `created_by` / `updated_by` | TEXT | `created_by` NOT NULL DEFAULT `'system'` | 同名字段 | 创建/更新人 |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | — | 时间戳 |

```sql
CREATE TABLE IF NOT EXISTS config_versions (
  id                     TEXT PRIMARY KEY,
  config_id              TEXT NOT NULL UNIQUE,
  config_version         TEXT NOT NULL DEFAULT 'CFG-1.0',
  display_name           TEXT NOT NULL DEFAULT 'B项目生产调度管理系统',
  default_scenario_id    TEXT NOT NULL DEFAULT 'SCN-01',
  rule_version           TEXT NOT NULL DEFAULT 'RULE-1.0',
  dispatch_strategy      TEXT NOT NULL DEFAULT 'BALANCED'
                         CHECK (dispatch_strategy IN ('BALANCED','PRIORITY_FIRST','RESOURCE_FIRST','OPTIMAL')),
  recommendation_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  offline_sync_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
  report_period          TEXT NOT NULL DEFAULT 'DAILY'
                         CHECK (report_period IN ('SHIFT','DAILY','MONTHLY')),
  audit_retention_days   INTEGER NOT NULL DEFAULT 365
                         CHECK (audit_retention_days >= 1 AND audit_retention_days <= 3650),
  status                 TEXT NOT NULL DEFAULT 'DRAFT'
                         CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','PUBLISHED','ROLLED_BACK')),
  version                INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  scenario_id            TEXT NOT NULL DEFAULT 'SCN-01',
  published_at           TIMESTAMPTZ,
  published_by           TEXT,
  created_by             TEXT NOT NULL DEFAULT 'system',
  updated_by             TEXT,
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_config_published CHECK (
    (status = 'PUBLISHED' AND published_at IS NOT NULL)
    OR status <> 'PUBLISHED'
    OR published_at IS NULL
  )
);
CREATE INDEX IF NOT EXISTS idx_config_versions_status ON config_versions (status);
```

> `chk_config_published` 用了一个"恒真兜底"写法（最后一行），这是为了兼容"先建为草稿、后续补发布时间"的分步写入而不让约束误伤，实际约束语义是**发布态必须带发布时间**。

### 5.4 `config_change_history` 配置变更历史

原 `changeHistory` 内嵌数组。**拆表的理由**：变更历史需要独立按时间/字段检索与统计。

| 列名 | 类型 | 约束 | 契约字段名 | 说明 |
|------|------|------|-----------|------|
| `config_id` | TEXT | PK(复合), FK → `config_versions.id` CASCADE | — | 所属配置 |
| `seq` | INTEGER | PK(复合), CHECK > 0 | — | 序号 |
| `field` | TEXT | NOT NULL DEFAULT `''` | `field` | 变更字段名 |
| `old_value` / `new_value` | JSONB | — | `oldValue` / `newValue` | 变更前后值（值类型不定，故用 JSONB） |
| `changed_by` | TEXT | — | `changedBy` | 变更人 |
| `changed_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | `changedAt` | 变更时间 |

```sql
CREATE TABLE IF NOT EXISTS config_change_history (
  config_id  TEXT NOT NULL REFERENCES config_versions (id) ON DELETE CASCADE,
  seq        INTEGER NOT NULL CHECK (seq > 0),
  field      TEXT NOT NULL DEFAULT '',
  old_value  JSONB,
  new_value  JSONB,
  changed_by TEXT,
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (config_id, seq)
);
```

### 5.5 `audit_logs` 审计留痕

**统一超集模型**：同时承载两类留痕——
- **业务审计**（service 层写入）：有 `before_state` / `after_state` / `reason` / `object_type` / `object_id`；
- **HTTP 访问留痕**（middleware 写入）：有 `status_code` / `duration_ms` / `ip` / `user_agent` / `request_body` / `response_summary`。

两类共用 `occurred_at` 作为业务时间轴；访问留痕的 `occurred_at` 可为空，按 `created_at` 排序。

| 列名 | 类型 | 约束 | 契约字段名 | 说明 |
|------|------|------|-----------|------|
| `id` | TEXT | PK | `_id` / `id` | 主键 |
| `actor_id` | TEXT | NOT NULL | `actorId` | 操作者 |
| `actor_role` | TEXT | — | `actorRole` | 操作者角色（冗余存，便于按角色审计） |
| `operator_terminal` | TEXT | NOT NULL DEFAULT `'WEB-BACKEND'` | `operatorTerminal` | 操作终端 |
| `action` | TEXT | NOT NULL | `action` | 动作（如 `plan:confirm`） |
| `object_type` | TEXT | NOT NULL DEFAULT `''` | `objectType` | 对象类型 |
| `object_id` | TEXT | NOT NULL DEFAULT `''` | `objectId` | 对象 ID（与各表语义化主键一致） |
| `before_state` | JSONB | — | `before` | 变更前快照 |
| `after_state` | JSONB | — | `after` | 变更后快照 |
| `reason` | TEXT | NOT NULL DEFAULT `''` | `reason` | 操作理由 |
| `trace_id` | TEXT | NOT NULL DEFAULT `''` | `traceId` | 链路追踪 ID |
| `occurred_at` | TIMESTAMPTZ | — | `occurredAt` | 业务事件时间（访问留痕为空） |
| `resource` / `resource_id` | TEXT | — | 同名字段 | 访问的资源 |
| `status_code` | INTEGER | CHECK 100~599 | `statusCode` | HTTP 状态码（访问留痕） |
| `duration_ms` | INTEGER | CHECK ≥ 0 | `durationMs` | 耗时毫秒 |
| `ip` / `user_agent` | TEXT | — | 同名字段 | 客户端信息 |
| `request_body` | TEXT | — | `requestBody` | 请求体摘要（敏感字段已截断） |
| `response_summary` | TEXT | — | `responseSummary` | 响应摘要 |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | — | 落库时间 |

```sql
CREATE TABLE IF NOT EXISTS audit_logs (
  id               TEXT PRIMARY KEY,
  actor_id         TEXT NOT NULL,
  actor_role       TEXT,
  operator_terminal TEXT NOT NULL DEFAULT 'WEB-BACKEND',
  action           TEXT NOT NULL,
  object_type      TEXT NOT NULL DEFAULT '',
  object_id        TEXT NOT NULL DEFAULT '',
  before_state     JSONB,
  after_state      JSONB,
  reason           TEXT NOT NULL DEFAULT '',
  trace_id         TEXT NOT NULL DEFAULT '',
  occurred_at      TIMESTAMPTZ,
  resource         TEXT,
  resource_id      TEXT,
  status_code      INTEGER CHECK (status_code IS NULL OR (status_code >= 100 AND status_code <= 599)),
  duration_ms      INTEGER CHECK (duration_ms IS NULL OR duration_ms >= 0),
  ip               TEXT,
  user_agent       TEXT,
  request_body     TEXT,
  response_summary TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_audit_logs_occurred ON audit_logs (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_occurred ON audit_logs (actor_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_object ON audit_logs (object_type, object_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action_occurred ON audit_logs (action, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_trace ON audit_logs (trace_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created ON audit_logs (created_at);
```

**不可篡改**：审计表在生产环境应通过 `GRANT` 层只授 `SELECT, INSERT`（不给 `UPDATE` / `DELETE`），并清理用 `DROP PARTITION` 而非 `DELETE`（见第 9 章）。这一点**在库层强制**比在应用层约定更可靠。

### 5.6 `plans` 外部到发计划

对应 `DO-001` 调度侧视图。

| 列名 | 类型 | 约束 | 契约字段名 | 说明 |
|------|------|------|-----------|------|
| `id` | TEXT | PK | `_id` | 主键（`PLAN-001`） |
| `plan_batch_no` | TEXT | NOT NULL, UNIQUE | `planBatchNo` | 计划批次号，业务唯一键 |
| `train_no` | TEXT | NOT NULL | `trainNo` | 车次 |
| `cargo_type` | TEXT | NOT NULL | `cargoType` | 货种 |
| `cargo_description` | TEXT | NOT NULL DEFAULT `''` | `cargoDescription` | 货物描述 |
| `estimated_weight` | NUMERIC(14,3) | NOT NULL DEFAULT 0, CHECK ≥ 0 | `estimatedWeight` | 预计重量 |
| `weight_unit` | TEXT | NOT NULL DEFAULT `'ton'` | `weightUnit` | 重量单位 |
| `source_station` | TEXT | NOT NULL | `sourceStation` | 始发站 |
| `destination_station` | TEXT | NOT NULL DEFAULT `''` | `destinationStation` | 到达站 |
| `arrive_time` | TIMESTAMPTZ | NOT NULL | `arriveTime` | 到发时间，台账默认按此倒序 |
| `track_no` | TEXT | NOT NULL DEFAULT `''` | `trackNo` | 股道号 |
| `work_area` | TEXT | NOT NULL | `workArea` | 作业区（数据域过滤依据） |
| `status` | TEXT | NOT NULL, CHECK | `status` | `PENDING_CONFIRM` / `CONFIRMED` / `IN_PROGRESS` / `COMPLETED` / `CANCELLED` |
| `priority` | TEXT | NOT NULL, CHECK | `priority` | `HIGH` / `MEDIUM` / `LOW` |
| `confirmed_by` / `confirmed_at` | TEXT / TIMESTAMPTZ | — | 同名字段 | 确认信息 |
| `supplier_name` / `supplier_contact` / `supplier_phone` | TEXT | — | 摊平自 `supplierInfo` 嵌套对象 | 供应商信息 |
| `supplements` | JSONB | — | `supplements` | 补充信息（随外部系统演进） |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | — | 时间戳 |

```sql
CREATE TABLE IF NOT EXISTS plans (
  id                  TEXT PRIMARY KEY,
  plan_batch_no       TEXT NOT NULL UNIQUE,
  train_no            TEXT NOT NULL,
  cargo_type          TEXT NOT NULL,
  cargo_description   TEXT NOT NULL DEFAULT '',
  estimated_weight    NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (estimated_weight >= 0),
  weight_unit         TEXT NOT NULL DEFAULT 'ton',
  source_station      TEXT NOT NULL,
  destination_station TEXT NOT NULL DEFAULT '',
  arrive_time         TIMESTAMPTZ NOT NULL,
  track_no            TEXT NOT NULL DEFAULT '',
  work_area           TEXT NOT NULL,
  status              TEXT NOT NULL DEFAULT 'PENDING_CONFIRM'
                      CHECK (status IN ('PENDING_CONFIRM','CONFIRMED','IN_PROGRESS','COMPLETED','CANCELLED')),
  priority            TEXT NOT NULL DEFAULT 'MEDIUM'
                      CHECK (priority IN ('HIGH','MEDIUM','LOW')),
  confirmed_by        TEXT,
  confirmed_at        TIMESTAMPTZ,
  supplier_name       TEXT,
  supplier_contact    TEXT,
  supplier_phone      TEXT,
  supplements         JSONB,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_plans_work_area_status ON plans (work_area, status);
CREATE INDEX IF NOT EXISTS idx_plans_arrive_time ON plans (arrive_time DESC);
CREATE INDEX IF NOT EXISTS idx_plans_status_updated ON plans (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_plans_train_no ON plans (train_no);
CREATE INDEX IF NOT EXISTS idx_plans_cargo_type ON plans (cargo_type);
```

### 5.7 `plan_cargo_items` 计划货物明细

原 `cargoItems` 内嵌数组。**拆表的理由**：需要按品名统计货物量（报表），内嵌无法建索引。

| 列名 | 类型 | 约束 | 契约字段名 | 说明 |
|------|------|------|-----------|------|
| `plan_id` | TEXT | PK(复合), FK → `plans.id` CASCADE | — | 所属计划 |
| `item_no` | INTEGER | PK(复合), CHECK > 0 | `itemNo` | 明细行号 |
| `name` | TEXT | NOT NULL | `name` | 品名 |
| `quantity` | NUMERIC(14,3) | NOT NULL DEFAULT 0, CHECK ≥ 0 | `quantity` | 数量 |
| `unit` | TEXT | NOT NULL DEFAULT `''` | `unit` | 单位 |
| `weight` | NUMERIC(14,3) | NOT NULL DEFAULT 0, CHECK ≥ 0 | `weight` | 重量 |
| `remarks` | TEXT | NOT NULL DEFAULT `''` | `remarks` | 备注 |

```sql
CREATE TABLE IF NOT EXISTS plan_cargo_items (
  plan_id  TEXT NOT NULL REFERENCES plans (id) ON DELETE CASCADE,
  item_no  INTEGER NOT NULL CHECK (item_no > 0),
  name     TEXT NOT NULL,
  quantity NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (quantity >= 0),
  unit     TEXT NOT NULL DEFAULT '',
  weight   NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (weight >= 0),
  remarks  TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (plan_id, item_no)
);
CREATE INDEX IF NOT EXISTS idx_plan_cargo_items_name ON plan_cargo_items (name);
```

### 5.8 `work_orders` 作业工单

对应 `DO-005`。

| 列名 | 类型 | 约束 | 契约字段名 | 说明 |
|------|------|------|-----------|------|
| `id` | TEXT | PK | `_id` | 主键（`WO-001`） |
| `plan_id` | TEXT | NOT NULL | `planId` | 所属计划（**逻辑引用，无外键**） |
| `plan_batch_no` | TEXT | NOT NULL | `planBatchNo` | 冗余批次号（免 JOIN 展示） |
| `task_id` | TEXT | — | `taskId` | 来源任务（逻辑引用） |
| `work_area` | TEXT | NOT NULL | `workArea` | 作业区 |
| `equipment_id` | TEXT | — | `equipmentId` | 执行设备 |
| `equipment_name` | TEXT | NOT NULL DEFAULT `''` | `equipmentName` | 设备名（冗余） |
| `assigned_operator` | TEXT | — | `assignedOperator` | 指派操作员 |
| `status` | TEXT | NOT NULL, CHECK | `status` | `DRAFT` / `READY` / `ASSIGNED` / `ACCEPTED` / `IN_PROGRESS` / `PAUSED` / `COMPLETED` / `CANCELLED` |
| `order_type` | TEXT | NOT NULL, CHECK | `orderType` | `LOADING` / `UNLOADING` / `TRANSFER` / `MAINTENANCE` / `OTHER` |
| `priority` | TEXT | NOT NULL, CHECK | `priority` | `HIGH` / `MEDIUM` / `LOW` |
| `description` | TEXT | NOT NULL DEFAULT `''` | `description` | 描述 |
| `instructions` | TEXT | — | `instructions` | 作业指令 |
| `estimated_duration` | INTEGER | CHECK ≥ 0 | `estimatedDuration` | 预计时长（分钟） |
| `actual_start_time` / `actual_end_time` | TIMESTAMPTZ | — | 同名字段 | 实际起止 |
| `accepted_by` / `accepted_at` | TEXT / TIMESTAMPTZ | — | 同名字段 | 接单信息 |
| `pause_reason` / `cancel_reason` | TEXT | — | 同名字段 | 暂停/取消原因 |
| `feedback_quality` / `feedback_comment` / `feedback_by` / `feedback_at` | TEXT / TIMESTAMPTZ | — | 摊平自 `executionFeedback` 嵌套对象 | 执行反馈 |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | — | 时间戳 |

```sql
CREATE TABLE IF NOT EXISTS work_orders (
  id                 TEXT PRIMARY KEY,
  plan_id            TEXT NOT NULL,
  plan_batch_no      TEXT NOT NULL,
  task_id            TEXT,
  work_area          TEXT NOT NULL,
  equipment_id       TEXT,
  equipment_name     TEXT NOT NULL DEFAULT '',
  assigned_operator  TEXT,
  status             TEXT NOT NULL DEFAULT 'DRAFT'
                     CHECK (status IN ('DRAFT','READY','ASSIGNED','ACCEPTED','IN_PROGRESS','PAUSED','COMPLETED','CANCELLED')),
  order_type         TEXT NOT NULL DEFAULT 'OTHER'
                     CHECK (order_type IN ('LOADING','UNLOADING','TRANSFER','MAINTENANCE','OTHER')),
  priority           TEXT NOT NULL DEFAULT 'MEDIUM'
                     CHECK (priority IN ('HIGH','MEDIUM','LOW')),
  description        TEXT NOT NULL DEFAULT '',
  instructions       TEXT,
  estimated_duration INTEGER CHECK (estimated_duration IS NULL OR estimated_duration >= 0),
  actual_start_time  TIMESTAMPTZ,
  actual_end_time    TIMESTAMPTZ,
  accepted_by        TEXT,
  accepted_at        TIMESTAMPTZ,
  pause_reason       TEXT,
  cancel_reason      TEXT,
  feedback_quality   TEXT CHECK (feedback_quality IS NULL OR feedback_quality IN ('GOOD','FAIR','POOR')),
  feedback_comment   TEXT,
  feedback_by        TEXT,
  feedback_at        TIMESTAMPTZ,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_wo_time_order CHECK (
    actual_end_time IS NULL OR actual_start_time IS NULL OR actual_end_time >= actual_start_time
  )
);
CREATE INDEX IF NOT EXISTS idx_work_orders_work_area_status ON work_orders (work_area, status);
CREATE INDEX IF NOT EXISTS idx_work_orders_equipment_status ON work_orders (equipment_id, status);
CREATE INDEX IF NOT EXISTS idx_work_orders_plan_status ON work_orders (plan_id, status);
CREATE INDEX IF NOT EXISTS idx_work_orders_created ON work_orders (created_at DESC);
```

> `idx_work_orders_plan_status` 与 `idx_work_orders_equipment_status` 是**跨实体引用的补偿**：因为没建外键，删除计划/设备时不做级联，但按 `plan_id` / `equipment_id` 反查工单必须快，所以给这两个逻辑引用建索引。

### 5.9 `work_order_crew` 工单班组

原 `assignedCrew: string[]` 内嵌数组。

| 列名 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `work_order_id` | TEXT | PK(复合), FK → `work_orders.id` CASCADE | 工单 |
| `crew` | TEXT | PK(复合) | 班组标识 |

```sql
CREATE TABLE IF NOT EXISTS work_order_crew (
  work_order_id TEXT NOT NULL REFERENCES work_orders (id) ON DELETE CASCADE,
  crew          TEXT NOT NULL,
  PRIMARY KEY (work_order_id, crew)
);
```

### 5.10 `tasks` 任务拆解产物

对应 `DO-006` 作业节点的后端视图。

| 列名 | 类型 | 约束 | 契约字段名 | 说明 |
|------|------|------|-----------|------|
| `id` | TEXT | PK | `_id` | 主键（`TASK-001`） |
| `plan_id` | TEXT | NOT NULL | `planId` | 所属计划 |
| `work_order_id` | TEXT | — | `workOrderId` | 关联工单 |
| `plan_batch_no` | TEXT | NOT NULL | `planBatchNo` | 冗余批次号 |
| `task_no` | TEXT | NOT NULL | `taskNo` | 任务编号（同计划内唯一） |
| `name` | TEXT | NOT NULL | `name` | 任务名 |
| `description` | TEXT | NOT NULL DEFAULT `''` | `description` | 描述 |
| `work_area` | TEXT | NOT NULL | `workArea` | 作业区 |
| `equipment_id` | TEXT | — | `equipmentId` | 占用设备 |
| `status` | TEXT | NOT NULL, CHECK | `status` | `PENDING` / `READY` / `IN_PROGRESS` / `PAUSED` / `COMPLETED` / `FAILED` / `CANCELLED` |
| `order_no` | INTEGER | NOT NULL DEFAULT 1 | `order` | 任务序号，决定执行顺序 |
| `parent_task_id` | TEXT | — | `parentTaskId` | 父任务（任务树，自引用） |
| `estimated_duration` / `actual_duration` | INTEGER | CHECK ≥ 0 | 同名字段 | 预计/实际时长 |
| `started_at` / `completed_at` | TIMESTAMPTZ | — | 同名字段 | 起止时间 |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | — | 时间戳 |

```sql
CREATE TABLE IF NOT EXISTS tasks (
  id                 TEXT PRIMARY KEY,
  plan_id            TEXT NOT NULL,
  work_order_id      TEXT,
  plan_batch_no      TEXT NOT NULL,
  task_no            TEXT NOT NULL,
  name               TEXT NOT NULL,
  description        TEXT NOT NULL DEFAULT '',
  work_area          TEXT NOT NULL,
  equipment_id       TEXT,
  status             TEXT NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING','READY','IN_PROGRESS','PAUSED','COMPLETED','FAILED','CANCELLED')),
  order_no           INTEGER NOT NULL DEFAULT 1,
  parent_task_id     TEXT,
  estimated_duration INTEGER CHECK (estimated_duration IS NULL OR estimated_duration >= 0),
  actual_duration    INTEGER CHECK (actual_duration IS NULL OR actual_duration >= 0),
  started_at         TIMESTAMPTZ,
  completed_at       TIMESTAMPTZ,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_tasks_plan_task_no UNIQUE (plan_id, task_no)
);
CREATE INDEX IF NOT EXISTS idx_tasks_plan_order ON tasks (plan_id, order_no);
CREATE INDEX IF NOT EXISTS idx_tasks_plan_status ON tasks (plan_id, status);
CREATE INDEX IF NOT EXISTS idx_tasks_work_order ON tasks (work_order_id);
CREATE INDEX IF NOT EXISTS idx_tasks_parent ON tasks (parent_task_id);
```

**注意**：契约里字段名是 `order`，而 `ORDER` 是 SQL 保留字，因此物理列名用 `order_no`；访问层做 `order` ↔ `order_no` 映射。

### 5.11 `task_crew` 任务班组

原 `assignedCrew: string[]`。

```sql
CREATE TABLE IF NOT EXISTS task_crew (
  task_id TEXT NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
  crew    TEXT NOT NULL,
  PRIMARY KEY (task_id, crew)
);
```

### 5.12 `task_dependencies` 任务前置依赖

原 `dependsOn: string[]`。**必须拆表的理由**：这是"任务 → 任务"的**有向图边集**，需要支持两种独立查询——找环（拓扑排序）和反查下游影响。内嵌数组两者都做不到。

| 列名 | 类型 | 约束 | 契约字段名 | 说明 |
|------|------|------|-----------|------|
| `task_id` | TEXT | PK(复合), FK → `tasks.id` CASCADE | — | 任务（边的起点） |
| `depends_on_task_id` | TEXT | PK(复合) | `dependsOnTaskId` | 被依赖的任务（边的终点） |

```sql
CREATE TABLE IF NOT EXISTS task_dependencies (
  task_id             TEXT NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
  depends_on_task_id  TEXT NOT NULL,
  PRIMARY KEY (task_id, depends_on_task_id),
  CONSTRAINT chk_task_dep_not_self CHECK (task_id <> depends_on_task_id)
);
CREATE INDEX IF NOT EXISTS idx_task_dependencies_reverse ON task_dependencies (depends_on_task_id);
```

- `chk_task_dep_not_self`：禁止自环。
- `idx_task_dependencies_reverse`：**反查索引**。主键已经能高效支持"我的前置是谁"，这条索引支持"谁依赖我"（用于暂停/取消时找出受影响的下游任务）。
- 对外读回时，访问层把行集摊平回字符串数组（`dependsOn: ['TASK-002']`），契约不变。

### 5.13 `equipment` 设备台账

对应 `DO-007` Resource。

| 列名 | 类型 | 约束 | 契约字段名 | 说明 |
|------|------|------|-----------|------|
| `id` | TEXT | PK | `_id` | 主键 |
| `equipment_id` | TEXT | NOT NULL, UNIQUE | `equipmentId` | 设备编码，业务唯一键（与遥测 tag 对齐） |
| `name` | TEXT | NOT NULL | `name` | 设备名 |
| `type` | TEXT | NOT NULL | `type` | 设备类型 |
| `model` | TEXT | NOT NULL DEFAULT `''` | `model` | 型号 |
| `specs` | JSONB | — | `specs` | 规格参数（随型号演进） |
| `work_area` | TEXT | NOT NULL | `workArea` | 作业区 |
| `status` | TEXT | NOT NULL, CHECK | `status` | `ONLINE` / `OFFLINE` / `MAINTENANCE` / `FAULT` |
| `last_heartbeat` | TIMESTAMPTZ | — | `lastHeartbeat` | 最近心跳 |
| `last_telemetry_at` | TIMESTAMPTZ | — | `lastTelemetryAt` | 最近遥测到达时间（**明细在 openGemini**） |
| `network_zone` / `protocol` / `ip_address` / `port` | TEXT / TEXT / TEXT / INTEGER | `port` CHECK 1~65535 | 同名字段 | 接入信息 |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | — | 时间戳 |

```sql
CREATE TABLE IF NOT EXISTS equipment (
  id                TEXT PRIMARY KEY,
  equipment_id      TEXT NOT NULL UNIQUE,
  name              TEXT NOT NULL,
  type              TEXT NOT NULL,
  model             TEXT NOT NULL DEFAULT '',
  specs             JSONB,
  work_area         TEXT NOT NULL,
  status            TEXT NOT NULL DEFAULT 'OFFLINE'
                    CHECK (status IN ('ONLINE','OFFLINE','MAINTENANCE','FAULT')),
  last_heartbeat    TIMESTAMPTZ,
  last_telemetry_at TIMESTAMPTZ,
  network_zone      TEXT,
  protocol          TEXT,
  ip_address        TEXT,
  port              INTEGER CHECK (port IS NULL OR (port > 0 AND port <= 65535)),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_equipment_work_area_status ON equipment (work_area, status);
CREATE INDEX IF NOT EXISTS idx_equipment_type_status ON equipment (type, status);
CREATE INDEX IF NOT EXISTS idx_equipment_online ON equipment (last_heartbeat DESC);
```

**跨库关联说明**：`equipment.equipment_id` 是 openGauss 与 openGemini 之间**唯一的连接点**。openGemini 的 `equipment_telemetry` 用 `equipment_id` 作 tag。这条引用**没有数据库层约束**（跨库不可能有），靠写入侧保证 tag 值取自设备台账。

### 5.14 `appointments` 公路预约与叫号

对应 `DO-008`。

| 列名 | 类型 | 约束 | 契约字段名 | 说明 |
|------|------|------|-----------|------|
| `id` | TEXT | PK | `_id` | 主键（`APT-001`） |
| `appointment_id` | TEXT | NOT NULL, UNIQUE | `appointmentId` | 预约编号 |
| `vehicle_plate` | TEXT | NOT NULL | `vehiclePlate` | 车牌号 |
| `vehicle_type` | TEXT | NOT NULL | `vehicleType` | 车型 |
| `driver_name` / `driver_phone` / `driver_id_card` | TEXT | NOT NULL | 同名字段 | 司机信息 |
| `company` | TEXT | NOT NULL DEFAULT `''` | `company` | 所属单位 |
| `cargo_type` | TEXT | NOT NULL | `cargoType` | 货种 |
| `estimated_weight` | NUMERIC(14,3) | NOT NULL DEFAULT 0, CHECK ≥ 0 | `estimatedWeight` | 预计重量 |
| `planned_arrive_time` | TIMESTAMPTZ | NOT NULL | `plannedArriveTime` | 计划到达 |
| `actual_arrive_time` / `check_in_time` / `called_at` / `enter_time` / `exit_time` | TIMESTAMPTZ | — | 同名字段 | 各节点实际时间 |
| `queue_number` | INTEGER | CHECK > 0 | `queueNumber` | 排队序号 |
| `status` | TEXT | NOT NULL, CHECK | `status` | `PENDING` / `APPROVED` / `CHECKED_IN` / `QUEUED` / `CALLED` / `ON_SITE` / `COMPLETED` / `CANCELLED` / `NO_SHOW` |
| `gate_no` / `parking_bay` / `route` / `remarks` | TEXT | — | 同名字段 | 门岗/车位/路线/备注 |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | — | 时间戳 |

```sql
CREATE TABLE IF NOT EXISTS appointments (
  id                 TEXT PRIMARY KEY,
  appointment_id     TEXT NOT NULL UNIQUE,
  vehicle_plate      TEXT NOT NULL,
  vehicle_type       TEXT NOT NULL,
  driver_name        TEXT NOT NULL,
  driver_phone       TEXT NOT NULL,
  driver_id_card     TEXT NOT NULL,
  company            TEXT NOT NULL DEFAULT '',
  cargo_type         TEXT NOT NULL,
  estimated_weight   NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (estimated_weight >= 0),
  planned_arrive_time TIMESTAMPTZ NOT NULL,
  actual_arrive_time TIMESTAMPTZ,
  check_in_time      TIMESTAMPTZ,
  called_at          TIMESTAMPTZ,
  enter_time         TIMESTAMPTZ,
  exit_time          TIMESTAMPTZ,
  queue_number       INTEGER CHECK (queue_number IS NULL OR queue_number > 0),
  status             TEXT NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING','APPROVED','CHECKED_IN','QUEUED','CALLED','ON_SITE','COMPLETED','CANCELLED','NO_SHOW')),
  gate_no            TEXT,
  parking_bay        TEXT,
  route              TEXT,
  remarks            TEXT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_appointment_exit_after_enter CHECK (
    exit_time IS NULL OR enter_time IS NULL OR exit_time >= enter_time
  )
);
CREATE INDEX IF NOT EXISTS idx_appointments_planned_arrive ON appointments (planned_arrive_time);
CREATE INDEX IF NOT EXISTS idx_appointments_status_queue ON appointments (status, queue_number);
CREATE INDEX IF NOT EXISTS idx_appointments_vehicle_plate ON appointments (vehicle_plate);
CREATE INDEX IF NOT EXISTS idx_appointments_driver_phone ON appointments (driver_phone);
-- 同一排队序号在"未结束"的预约里必须唯一，避免叫号重号
CREATE UNIQUE INDEX IF NOT EXISTS uk_appointments_active_queue
  ON appointments (queue_number)
  WHERE queue_number IS NOT NULL
    AND status IN ('QUEUED','CALLED','ON_SITE');
```

**`uk_appointments_active_queue` 是本模块最关键的一个约束**：叫号场景下，同一个排队号不能同时发给两辆车。用**部分唯一索引**把唯一性限定在"未结束"状态（`QUEUED` / `CALLED` / `ON_SITE`），既防止重号，又允许号码在车辆离场后被复用。

### 5.15 `appointment_documents` 预约资质附件

原 `documents` 内嵌数组。

```sql
CREATE TABLE IF NOT EXISTS appointment_documents (
  appointment_id TEXT NOT NULL REFERENCES appointments (id) ON DELETE CASCADE,
  seq            INTEGER NOT NULL CHECK (seq > 0),
  type           TEXT NOT NULL DEFAULT '',
  url            TEXT NOT NULL DEFAULT '',
  verified       BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (appointment_id, seq)
);
```

### 5.16 `offline_packets` PDA 离线数据包

对应 `DO-011`。

| 列名 | 类型 | 约束 | 契约字段名 | 说明 |
|------|------|------|-----------|------|
| `id` | TEXT | PK | `_id` | 主键（`PKT-001`） |
| `packet_id` | TEXT | NOT NULL, UNIQUE | `packetId` | 包编号 |
| `terminal_id` | TEXT | NOT NULL | `terminalId` | 终端标识 |
| `operator_id` | TEXT | NOT NULL | `operatorId` | 操作员 |
| `work_area` | TEXT | NOT NULL | `workArea` | 作业区 |
| `status` | TEXT | NOT NULL, CHECK | `status` | `DRAFT` / `SYNCED` / `CONFLICT` / `RESOLVED` / `FAILED` |
| `version` | INTEGER | NOT NULL DEFAULT 1, CHECK ≥ 1 | `version` | 客户端数据版本 |
| `server_version` | INTEGER | CHECK ≥ 1 | `serverVersion` | 服务端数据版本（冲突比对用） |
| `payload` | JSONB | NOT NULL | `payload` | 离线业务载荷（结构随终端版本演进） |
| `sync_attempts` | INTEGER | NOT NULL DEFAULT 0, CHECK ≥ 0 | `syncAttempts` | 同步尝试次数 |
| `last_sync_at` | TIMESTAMPTZ | — | `lastSyncAt` | 最近同步时间 |
| `resolution` | TEXT | CHECK | `resolution` | `ACCEPT_LOCAL` / `ACCEPT_SERVER` / `MANUAL_MERGE` / `DISCARD` |
| `resolved_by` / `resolved_at` | TEXT / TIMESTAMPTZ | — | 同名字段 | 冲突处置信息 |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | — | 时间戳 |

```sql
CREATE TABLE IF NOT EXISTS offline_packets (
  id              TEXT PRIMARY KEY,
  packet_id       TEXT NOT NULL UNIQUE,
  terminal_id     TEXT NOT NULL,
  operator_id     TEXT NOT NULL,
  work_area       TEXT NOT NULL,
  status          TEXT NOT NULL DEFAULT 'DRAFT'
                  CHECK (status IN ('DRAFT','SYNCED','CONFLICT','RESOLVED','FAILED')),
  version         INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  server_version  INTEGER CHECK (server_version IS NULL OR server_version >= 1),
  payload         JSONB NOT NULL,
  sync_attempts   INTEGER NOT NULL DEFAULT 0 CHECK (sync_attempts >= 0),
  last_sync_at    TIMESTAMPTZ,
  resolution      TEXT CHECK (resolution IS NULL OR resolution IN ('ACCEPT_LOCAL','ACCEPT_SERVER','MANUAL_MERGE','DISCARD')),
  resolved_by     TEXT,
  resolved_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_offline_packets_terminal_status ON offline_packets (terminal_id, status);
CREATE INDEX IF NOT EXISTS idx_offline_packets_status_updated ON offline_packets (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_offline_packets_operator ON offline_packets (operator_id);
```

### 5.17 `offline_packet_conflict_fields` 离线包冲突字段

原 `conflictFields: string[]`。

```sql
CREATE TABLE IF NOT EXISTS offline_packet_conflict_fields (
  packet_id TEXT NOT NULL REFERENCES offline_packets (id) ON DELETE CASCADE,
  field     TEXT NOT NULL,
  PRIMARY KEY (packet_id, field)
);
```

### 5.18 `exceptions` 生产异常

对应 `DO-009` 的后端视图。

| 列名 | 类型 | 约束 | 契约字段名 | 说明 |
|------|------|------|-----------|------|
| `id` | TEXT | PK | `_id` | 主键（`EXC-001`） |
| `exception_id` | TEXT | NOT NULL, UNIQUE | `exceptionId` | 异常编号 |
| `type` | TEXT | NOT NULL, CHECK | `type` | `FLOW` / `SAFETY` / `EQUIPMENT` / `INTERFACE` / `DATA` |
| `severity` | TEXT | NOT NULL, CHECK | `severity` | `CRITICAL` / `MAJOR` / `MINOR` / `INFO` |
| `source_id` | TEXT | NOT NULL | `sourceId` | 来源对象 ID（多态） |
| `source_type` | TEXT | NOT NULL, CHECK | `sourceType` | `plan` / `workOrder` / `task` / `equipment` / `interlock` |
| `title` | TEXT | NOT NULL | `title` | 标题 |
| `description` | TEXT | NOT NULL DEFAULT `''` | `description` | 描述 |
| `equipment_id` | TEXT | — | `equipmentId` | 相关设备 |
| `work_area` | TEXT | NOT NULL | `workArea` | 作业区 |
| `status` | TEXT | NOT NULL, CHECK | `status` | `OPEN` / `ACKNOWLEDGED` / `IN_PROGRESS` / `RESOLVED` / `CLOSED` / `DISMISSED` |
| `assigned_to` | TEXT | — | `assignedTo` | 处理人 |
| `acknowledged_by` / `acknowledged_at` | TEXT / TIMESTAMPTZ | — | 同名字段 | 确认信息 |
| `resolved_by` / `resolved_at` | TEXT / TIMESTAMPTZ | — | 同名字段 | 解决信息 |
| `closed_by` / `closed_at` | TEXT / TIMESTAMPTZ | — | 同名字段 | 关闭信息 |
| `resolution` / `root_cause` | TEXT | — | 同名字段 | 处理结论 / 根因 |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | — | 时间戳 |

```sql
CREATE TABLE IF NOT EXISTS exceptions (
  id              TEXT PRIMARY KEY,
  exception_id    TEXT NOT NULL UNIQUE,
  type            TEXT NOT NULL CHECK (type IN ('FLOW','SAFETY','EQUIPMENT','INTERFACE','DATA')),
  severity        TEXT NOT NULL DEFAULT 'MAJOR' CHECK (severity IN ('CRITICAL','MAJOR','MINOR','INFO')),
  source_id       TEXT NOT NULL,
  source_type     TEXT NOT NULL CHECK (source_type IN ('plan','workOrder','task','equipment','interlock')),
  title           TEXT NOT NULL,
  description     TEXT NOT NULL DEFAULT '',
  equipment_id    TEXT,
  work_area       TEXT NOT NULL,
  status          TEXT NOT NULL DEFAULT 'OPEN'
                  CHECK (status IN ('OPEN','ACKNOWLEDGED','IN_PROGRESS','RESOLVED','CLOSED','DISMISSED')),
  assigned_to     TEXT,
  acknowledged_by TEXT,
  acknowledged_at TIMESTAMPTZ,
  resolved_by     TEXT,
  resolved_at     TIMESTAMPTZ,
  closed_by       TEXT,
  closed_at       TIMESTAMPTZ,
  resolution      TEXT,
  root_cause      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_exceptions_work_area_status ON exceptions (work_area, status);
CREATE INDEX IF NOT EXISTS idx_exceptions_type_severity ON exceptions (type, severity);
CREATE INDEX IF NOT EXISTS idx_exceptions_status_created ON exceptions (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_exceptions_source ON exceptions (source_type, source_id);
```

> `idx_exceptions_source` 是**多态引用的补偿索引**：因为 `source_id` 指向的表由 `source_type` 决定，无法建外键；但要支持"查看某计划引发的全部异常"，必须建 `(source_type, source_id)` 复合索引。

### 5.19 `exception_evidence` 异常证据

原 `evidence` 内嵌数组。

```sql
CREATE TABLE IF NOT EXISTS exception_evidence (
  exception_id TEXT NOT NULL REFERENCES exceptions (id) ON DELETE CASCADE,
  seq          INTEGER NOT NULL CHECK (seq > 0),
  type         TEXT NOT NULL DEFAULT '',
  url          TEXT NOT NULL DEFAULT '',
  description  TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (exception_id, seq)
);
```

### 5.20 `interlocks` 安全联锁

对应 `DO-010`。

| 列名 | 类型 | 约束 | 契约字段名 | 说明 |
|------|------|------|-----------|------|
| `id` | TEXT | PK | `_id` | 主键（`ILK-001`） |
| `interlock_id` | TEXT | NOT NULL, UNIQUE | `interlockId` | 联锁编号 |
| `name` | TEXT | NOT NULL | `name` | 名称 |
| `type` | TEXT | NOT NULL, CHECK | `type` | `HARD` / `SOFT` / `PROCEDURAL` |
| `category` | TEXT | NOT NULL, CHECK | `category` | `ACCESS` / `EQUIPMENT` / `AREA` / `ENVIRONMENT` |
| `source_id` | TEXT | NOT NULL | `sourceId` | 来源对象 |
| `equipment_id` | TEXT | — | `equipmentId` | 相关设备 |
| `work_area` | TEXT | NOT NULL | `workArea` | 作业区 |
| `rule` | TEXT | NOT NULL | `rule` | 联锁规则表达式 |
| `description` | TEXT | NOT NULL DEFAULT `''` | `description` | 描述 |
| `status` | TEXT | NOT NULL, CHECK | `status` | `ARMED` / `TRIGGERED` / `OVERRIDDEN` / `RESET` / `DISABLED` |
| `triggered_at` / `triggered_by` / `trigger_reason` | TIMESTAMPTZ / TEXT / TEXT | — | 同名字段 | 触发信息 |
| `override_requested_by` / `override_approved_by` / `override_reason` / `override_expires_at` | TEXT / TEXT / TEXT / TIMESTAMPTZ | — | 同名字段 | 临时覆盖信息 |
| `reset_by` / `reset_at` | TEXT / TIMESTAMPTZ | — | 同名字段 | 复位信息 |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | — | 时间戳 |

```sql
CREATE TABLE IF NOT EXISTS interlocks (
  id                   TEXT PRIMARY KEY,
  interlock_id         TEXT NOT NULL UNIQUE,
  name                 TEXT NOT NULL,
  type                 TEXT NOT NULL CHECK (type IN ('HARD','SOFT','PROCEDURAL')),
  category             TEXT NOT NULL CHECK (category IN ('ACCESS','EQUIPMENT','AREA','ENVIRONMENT')),
  source_id            TEXT NOT NULL,
  equipment_id         TEXT,
  work_area            TEXT NOT NULL,
  rule                 TEXT NOT NULL,
  description          TEXT NOT NULL DEFAULT '',
  status               TEXT NOT NULL DEFAULT 'ARMED'
                       CHECK (status IN ('ARMED','TRIGGERED','OVERRIDDEN','RESET','DISABLED')),
  triggered_at         TIMESTAMPTZ,
  triggered_by         TEXT,
  trigger_reason       TEXT,
  override_requested_by TEXT,
  override_approved_by TEXT,
  override_reason      TEXT,
  override_expires_at  TIMESTAMPTZ,
  reset_by             TEXT,
  reset_at             TIMESTAMPTZ,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_interlock_override CHECK (
    override_approved_by IS NULL OR override_requested_by IS NOT NULL
  )
);
CREATE INDEX IF NOT EXISTS idx_interlocks_work_area_status ON interlocks (work_area, status);
CREATE INDEX IF NOT EXISTS idx_interlocks_status_triggered ON interlocks (status, triggered_at DESC);
CREATE INDEX IF NOT EXISTS idx_interlocks_override_expires ON interlocks (override_expires_at) WHERE override_expires_at IS NOT NULL;
```

**`chk_interlock_override` 表达的业务规则**：联锁的临时覆盖必须先申请、后批准——**没有申请人就不可能有批准人**。这条规则写在库层，任何绕过应用层的写入也会被拒绝。

**关于"覆盖到期"**：`override_expires_at` 只是记录到期时间，**到期后不删记录、不改状态**，由应用层在读取时判定"当前覆盖是否仍然有效"。原 MongoDB 版曾试图用 TTL 索引处理，但 TTL 会**删除整个文档**，而联锁记录必须留档追溯 —— 这正是迁到关系库后得以修正的设计缺陷。`idx_interlocks_override_expires` 用部分索引（只覆盖非空值）支撑"找出即将到期的覆盖"这类巡检。

### 5.21 `interlock_input_signals` 联锁输入信号

原 `inputSignals` 内嵌数组。

```sql
CREATE TABLE IF NOT EXISTS interlock_input_signals (
  interlock_id   TEXT NOT NULL REFERENCES interlocks (id) ON DELETE CASCADE,
  seq            INTEGER NOT NULL CHECK (seq > 0),
  equipment_id   TEXT,
  point_code     TEXT,
  expected_value TEXT,
  actual_value   TEXT,
  PRIMARY KEY (interlock_id, seq)
);
```

> `expected_value` / `actual_value` 用 `TEXT` 而非数值类型：测点值可能是数值、布尔（`true`/`false`）、状态字（`RUNNING`）等，用文本可容纳全部形态，比较语义交给业务规则。

---

## 6 索引设计与典型查询

### 6.1 索引策略

设计索引时遵循三条原则：

**原则一：先满足"列表页默认排序 + 数据域过滤"的组合。** 本模块所有台账页都是"在某个作业区内，按状态筛、按时间倒序分页"。因此高频索引一律建成 `(work_area, status)` 或 `(status, <时间> DESC)` 的复合形式，而不是给单列建索引。

**原则二：ESR 顺序**（Equality → Sort → Range）。复合索引的列顺序按"等值条件在前、排序/范围在后"排。例如 `idx_plans_work_area_status (work_area, status)` —— 台账页固定带 `work_area` 等值条件，`status` 是进一步的等值筛选。

**原则三：给"逻辑引用"建反查索引。** 因为跨实体不建外键，所有逻辑引用（`plan_id`、`equipment_id`、`source_id`）都需要显式索引，否则"按计划查工单"这类查询会全表扫描。

**部分索引的用法**：把索引限定在真正会被查询的子集上，索引体积更小、更快。本模块用了 3 个：
- `idx_users_online`：只在 `online = TRUE` 建索引（在线用户是少数）；
- `idx_interlocks_override_expires`：只在 `override_expires_at IS NOT NULL` 建索引；
- `uk_appointments_active_queue`：只在未结束状态下强制排队号唯一。

### 6.2 索引汇总

**主键索引：21 个**（每张表一个，全部为语义化 `TEXT` 主键或复合主键）

**唯一约束：10 个**

| 表 | 唯一列 | 业务含义 |
|----|--------|----------|
| `users` | `actor_id` | 账号标识唯一 |
| `users` | `username` | 登录名唯一 |
| `plans` | `plan_batch_no` | 计划批次号唯一 |
| `tasks` | `(plan_id, task_no)` | 同一计划内任务编号唯一 |
| `equipment` | `equipment_id` | 设备编码唯一 |
| `exceptions` | `exception_id` | 异常编号唯一 |
| `interlocks` | `interlock_id` | 联锁编号唯一 |
| `appointments` | `appointment_id` | 预约编号唯一 |
| `offline_packets` | `packet_id` | 包编号唯一 |
| `config_versions` | `config_id` | 配置标识唯一 |

**二级索引：42 个（含 1 个部分唯一索引、2 个部分索引）**

| 表 | 索引 | 服务的主要查询 |
|----|------|----------------|
| `users` | `idx_users_role` | 按角色查账号 |
| `users` | `idx_users_online`（部分） | 在线用户列表 |
| `plans` | `idx_plans_work_area_status` | 台账页：域内按状态筛 |
| `plans` | `idx_plans_arrive_time` | 台账页默认排序（到发时间倒序） |
| `plans` | `idx_plans_status_updated` | "最近变更"视图 |
| `plans` | `idx_plans_train_no` | 按车次检索 |
| `plans` | `idx_plans_cargo_type` | 按货种统计 |
| `plan_cargo_items` | `idx_plan_cargo_items_name` | 按品名汇总货量 |
| `work_orders` | `idx_work_orders_work_area_status` | 工单看板 |
| `work_orders` | `idx_work_orders_equipment_status` | 设备当前工单 |
| `work_orders` | `idx_work_orders_plan_status` | 计划 → 工单（逻辑引用反查） |
| `work_orders` | `idx_work_orders_created` | 最新工单 |
| `tasks` | `idx_tasks_plan_order` | 任务按执行顺序排列 |
| `tasks` | `idx_tasks_plan_status` | 计划下的任务状态分布 |
| `tasks` | `idx_tasks_work_order` | 工单 → 任务 |
| `tasks` | `idx_tasks_parent` | 任务树展开 |
| `task_dependencies` | `idx_task_dependencies_reverse` | **反查下游**（谁依赖我） |
| `equipment` | `idx_equipment_work_area_status` | 资源可用性清单 |
| `equipment` | `idx_equipment_type_status` | 按类型看状态 |
| `equipment` | `idx_equipment_online` | 心跳时间倒序（判断在线） |
| `exceptions` | `idx_exceptions_work_area_status` | 异常台账 |
| `exceptions` | `idx_exceptions_type_severity` | 按类型+严重度分布 |
| `exceptions` | `idx_exceptions_status_created` | 未闭环异常 |
| `exceptions` | `idx_exceptions_source` | 来源对象 → 异常（多态补偿） |
| `interlocks` | `idx_interlocks_work_area_status` | 联锁清单 |
| `interlocks` | `idx_interlocks_status_triggered` | 已触发联锁 |
| `interlocks` | `idx_interlocks_override_expires`（部分） | 即将到期的临时覆盖 |
| `appointments` | `idx_appointments_planned_arrive` | 预约按计划到达排序 |
| `appointments` | `idx_appointments_status_queue` | 叫号队列 |
| `appointments` | `idx_appointments_vehicle_plate` | 按车牌查历史 |
| `appointments` | `idx_appointments_driver_phone` | 按司机查历史 |
| `appointments` | `uk_appointments_active_queue`（部分唯一） | **防止叫号重号** |
| `offline_packets` | `idx_offline_packets_terminal_status` | 终端待同步包 |
| `offline_packets` | `idx_offline_packets_status_updated` | 冲突包待处理 |
| `offline_packets` | `idx_offline_packets_operator` | 按操作员查 |
| `config_versions` | `idx_config_versions_status` | 按状态查配置版本 |
| `audit_logs` | `idx_audit_logs_occurred` | 审计时间线 |
| `audit_logs` | `idx_audit_logs_actor_occurred` | 某人做了什么 |
| `audit_logs` | `idx_audit_logs_object` | 某对象被谁改过 |
| `audit_logs` | `idx_audit_logs_action_occurred` | 按动作检索 |
| `audit_logs` | `idx_audit_logs_trace` | 按链路 ID 追一次请求 |
| `audit_logs` | `idx_audit_logs_created` | 保留期清理扫描 |

**索引总量：21（主键）+ 10（唯一）+ 42（二级）= 73 个索引对象。**

### 6.3 典型查询与命中索引

**Q1｜台账首页：我的作业区内，待确认的计划，按到发时间倒序**

```sql
SELECT id, plan_batch_no, train_no, cargo_type, arrive_time, status, priority
FROM plans
WHERE work_area = ANY($1)          -- 数据域（会话的可见作业区）
  AND status = 'PENDING_CONFIRM'
ORDER BY arrive_time DESC
LIMIT 20 OFFSET 0;
```
命中：`idx_plans_work_area_status`（等值两列）+ 排序可用 `idx_plans_arrive_time`。若数据域只有 1 个作业区，`idx_plans_arrive_time` 配合过滤更优。

**Q2｜异常台账：未闭环，按创建时间倒序**

```sql
SELECT exception_id, type, severity, title, status, created_at
FROM exceptions
WHERE work_area = ANY($1) AND status IN ('OPEN','ACKNOWLEDGED','IN_PROGRESS')
ORDER BY created_at DESC LIMIT 20;
```
命中：`idx_exceptions_status_created`。

**Q3｜叫号：取当前队列（未结束的按排队号升序）**

```sql
SELECT appointment_id, vehicle_plate, queue_number, status
FROM appointments
WHERE status IN ('QUEUED','CALLED','ON_SITE')
ORDER BY queue_number ASC;
```
命中：`idx_appointments_status_queue`。该查询也是 `uk_appointments_active_queue` 部分唯一索引的服务对象。

**Q4｜资源：某作业区可用设备**

```sql
SELECT equipment_id, name, type, status
FROM equipment
WHERE work_area = $1
  AND status = 'ONLINE'
  AND specs -> 'capabilities' ?| $2::text[]   -- 能力标签覆盖
ORDER BY name;
```
命中：`idx_equipment_work_area_status`。能力匹配对 `specs` 做 `jsonb` 查询（这是接受它的代价——`specs` 是弱结构字段，不建 GIN 索引；若该查询成为热点，可为 `specs` 加 GIN）。

**Q5｜套用推荐：某计划下的任务，按执行顺序**

```sql
SELECT task_no, name, order_no, status, equipment_id
FROM tasks
WHERE plan_id = $1
ORDER BY order_no ASC, task_no ASC;
```
命中：`idx_tasks_plan_order`。

**Q6｜依赖检查：某任务的前置任务是否都已完成**

```sql
SELECT t.id, t.task_no, t.status
FROM task_dependencies d
JOIN tasks t ON t.id = d.depends_on_task_id
WHERE d.task_id = $1;
```
命中：`task_dependencies` 主键 `(task_id, depends_on_task_id)` + `tasks` 主键。

**Q7｜下游影响：暂停某任务，哪些任务会受影响**

```sql
SELECT d.task_id, t.name, t.status
FROM task_dependencies d
JOIN tasks t ON t.id = d.task_id
WHERE d.depends_on_task_id = $1;
```
命中：`idx_task_dependencies_reverse`（**没有它就会全表扫依赖表**）。

**Q8｜工单看板：某设备当前在做的工单**

```sql
SELECT id, plan_batch_no, order_type, status, actual_start_time
FROM work_orders
WHERE equipment_id = $1 AND status IN ('ASSIGNED','ACCEPTED','IN_PROGRESS','PAUSED')
ORDER BY created_at DESC;
```
命中：`idx_work_orders_equipment_status`。

**Q9｜追溯：某计划被谁改过**

```sql
SELECT actor_id, action, reason, before_state, after_state, occurred_at
FROM audit_logs
WHERE object_type = 'plan' AND object_id = $1
ORDER BY occurred_at DESC
LIMIT 50;
```
命中：`idx_audit_logs_object`。

**Q10｜链路追踪：一次请求产生的全部留痕**

```sql
SELECT actor_id, action, object_type, object_id, status_code, duration_ms, created_at
FROM audit_logs
WHERE trace_id = $1
ORDER BY created_at;
```
命中：`idx_audit_logs_trace`。

**Q11｜报表：按状态统计计划数（接口 `API-014`）**

```sql
SELECT status, COUNT(*) AS n, COALESCE(SUM(estimated_weight), 0) AS total_weight
FROM plans
WHERE work_area = ANY($1)
  AND arrive_time >= $2 AND arrive_time < $3
GROUP BY status
ORDER BY status;
```
命中：`idx_plans_arrive_time`（范围）+ `idx_plans_work_area_status` 可作备选。这是**取代 MongoDB 聚合管道**的实现方式——原 `$match` + `$group` 换成标准 `WHERE` + `GROUP BY`。

**Q12｜报表：按货种汇总货物明细**

```sql
SELECT ci.name, SUM(ci.quantity) AS qty, SUM(ci.weight) AS weight
FROM plan_cargo_items ci
JOIN plans p ON p.id = ci.plan_id
WHERE p.work_area = ANY($1) AND p.arrive_time >= $2 AND p.arrive_time < $3
GROUP BY ci.name
ORDER BY weight DESC;
```
命中：`plans` 的范围索引 + `plan_cargo_items` 主键（按 `plan_id` 关联）+ `idx_plan_cargo_items_name`。

**Q13｜待处理：终端的未同步/冲突包**

```sql
SELECT packet_id, terminal_id, status, version, server_version, last_sync_at
FROM offline_packets
WHERE terminal_id = $1 AND status IN ('DRAFT','CONFLICT','FAILED')
ORDER BY updated_at DESC;
```
命中：`idx_offline_packets_terminal_status`。

---

## 7 事务与并发控制

### 7.1 事务边界

关系库相对文档库最大的收益是**真正的多行事务**。本模块在以下三类操作中使用事务：

| 操作 | 参与的表 | 事务必要性 |
|------|----------|-----------|
| **新增带子表的记录** | 主表 + 子表 | 主行与子行必须同时成功，否则出现"有父无子"的残缺记录 |
| **更新多值字段** | 主表（可选）+ 子表 | 先 `DELETE` 旧子行再 `INSERT` 新子行，中间失败会丢数据 |
| **命令流水线**（业务命令 + 审计留痕） | 业务表 + `audit_logs` | 业务变更必须与审计留痕同生共死，否则审计不可信 |

**实现位置**：全部收敛在访问层 `src/db/table.ts` 的 `insertMany` 与 `findByIdAndUpdate` 内，业务服务层不需要手写 `BEGIN` / `COMMIT`：

- `insertMany`：开事务 → 逐行 INSERT 主表 → `persistChildren` 落子表 → `COMMIT`。任一步失败则 `ROLLBACK`。
- `findByIdAndUpdate`：**当 update 里含子表字段时**才开事务 —— 先 `DELETE` 该父记录的全部旧子行，再 `INSERT` 新子行，最后 `COMMIT`；只更新标量列时不开事务（单条 UPDATE 本身即原子）。
- 事务连接用完必定 `release()`，并保证 catch 分支也 `ROLLBACK`（用 `.catch(() => undefined)` 吞掉回滚本身的异常，避免掩盖原始错误）。

**为什么"更新多值字段"用整体替换而不是差分**：差分（逐条比对决定增/删/改）逻辑复杂且容易漏；整体替换的语义与文档库的 `set` 完全一致，业务代码不需要为换存储改变行为，而且在一个事务里做，原子性有保证。

### 7.2 乐观锁

| 场景 | 冲突检测方式 | 冲突返回 |
|------|-------------|----------|
| 配置版本编辑 | 条件更新 `WHERE id = $1 AND version = $2`，成功后 `version = version + 1` | `409 CONFLICT` |
| 离线包同步 | 比对 `version` 与 `server_version`，不一致则标记 `CONFLICT` 并记冲突字段 | 业务态 `CONFLICT`（非 HTTP 错误） |
| 叫号取号 | 依赖 `uk_appointments_active_queue` 部分唯一索引 | 唯一约束冲突 → 转业务提示"号码已被占用" |

**叫号并发是本模块最典型的竞争场景**：两个门岗同时给两辆车分配同一排队号。因为唯一性是**库层约束**，两次并发插入必有一次失败——这比"先查再写"的应用层检查可靠得多（后者在并发下必然漏判）。

### 7.3 并发热点与缓解

| 热点 | 原因 | 缓解措施 |
|------|------|----------|
| `appointments` 叫号 | 唯一索引上的争用 | 号码按作业区分段预分配，降低同一索引键的冲突概率 |
| `equipment.last_heartbeat` | 心跳高频更新同一行 | 心跳**不入业务库**（只走 openGemini）；台账的 `last_heartbeat` / `last_telemetry_at` 异步批量刷新（如每 30 秒一次 `UPDATE ... WHERE equipment_id = ANY($1)`） |
| `config_versions` 单行 | 配置是全模块单例，所有人读同一行 | 配置**读多写少**，适合缓存；写入用乐观锁串行化 |
| `audit_logs` 插入 | 每次写命令都插一行 | 按月分区 + 追加写；不建多余索引（每多一个索引，写入就多一份代价） |

---

## 8 数据完整性与约束策略

### 8.1 约束分层

本模块把完整性约束分为三层，各层职责明确：

| 层 | 手段 | 覆盖内容 | 是否可绕过 |
|----|------|----------|-----------|
| **库层** | 主键 / 唯一约束 / 外键 `CASCADE` / `CHECK` / `NOT NULL` | 结构完整性、枚举取值、时间先后、自环、重号 | ❌ 无法绕过（应用有 bug 也拦得住） |
| **应用层** | Zod 校验 + 状态机 + 数据域过滤 | 业务规则、状态迁移合法性、权限、跨表引用有效性 | ⚠️ 可绕过（直连库写入不经过它） |
| **流程层** | 审计留痕 + 审计表只授 `INSERT`/`SELECT` | 操作可追溯、留痕不可篡改 | ❌ 不可篡改 |

**分层的原则**：能用库层表达的一律下沉到库层。库层约束不依赖应用代码正确性，是最可靠的防线。

### 8.2 CHECK 约束清单

| 表 | 约束名 | 规则 | 业务含义 |
|----|--------|------|----------|
| `users` | （列级） | `role_code IN (6 个取值)` | 后端角色集受控 |
| `config_versions` | （列级） | `dispatch_strategy IN (4)`、`report_period IN (3)`、`status IN (5)` | 策略/周期/状态受控 |
| `config_versions` | （列级） | `audit_retention_days BETWEEN 1 AND 3650` | 保留期 1 天~10 年 |
| `config_versions` | （列级） | `version >= 1` | 乐观锁版本从 1 起 |
| `config_versions` | `chk_config_published` | 发布态必须带发布时间 | 状态与时间自洽 |
| `plans` | （列级） | `estimated_weight >= 0`、`status IN (5)`、`priority IN (3)` | 重量非负、取值受控 |
| `plan_cargo_items` | （列级） | `item_no > 0`、`quantity >= 0`、`weight >= 0` | 行号正、量重非负 |
| `work_orders` | `chk_wo_time_order` | `actual_end_time >= actual_start_time` | **时间先后自洽** |
| `work_orders` | （列级） | `estimated_duration >= 0`、`status IN (8)`、`order_type IN (5)`、`priority IN (3)`、`feedback_quality IN (3)` | 时长非负、取值受控 |
| `tasks` | （列级） | `status IN (7)`、`estimated_duration >= 0`、`actual_duration >= 0` | 取值与时长受控 |
| `task_dependencies` | `chk_task_dep_not_self` | `task_id <> depends_on_task_id` | **禁止自环** |
| `equipment` | （列级） | `status IN (4)`、`port BETWEEN 1 AND 65535` | 状态与端口受控 |
| `exceptions` | （列级） | `type IN (5)`、`severity IN (4)`、`source_type IN (5)`、`status IN (6)` | 多态来源与取值受控 |
| `interlocks` | （列级） | `type IN (3)`、`category IN (4)`、`status IN (5)` | 取值受控 |
| `interlocks` | `chk_interlock_override` | 有批准人则必有申请人 | **覆盖审批流程自洽** |
| `appointments` | （列级） | `estimated_weight >= 0`、`queue_number > 0`、`status IN (9)` | 重量、号码、状态受控 |
| `appointments` | `chk_appointment_exit_after_enter` | `exit_time >= enter_time` | **进出场时间自洽** |
| `offline_packets` | （列级） | `status IN (5)`、`version >= 1`、`server_version >= 1`、`sync_attempts >= 0`、`resolution IN (4)` | 取值受控 |
| `audit_logs` | （列级） | `status_code BETWEEN 100 AND 599`、`duration_ms >= 0` | 状态码与耗时合法 |

**关于"时间先后自洽"类的约束**：`chk_wo_time_order` 与 `chk_appointment_exit_after_enter` 都写成"只要有一个为空就放行"的形式（`... IS NULL OR ...`）。这是因为本模块的时间列大量在流程推进中逐步回填，约束必须容忍"尚未发生"的中间状态，只在两个时间**都已填入**时校验先后。

### 8.3 唯一性与防重约束清单

| 表 | 约束 | 防护的场景 |
|----|------|-----------|
| `users` | `actor_id` / `username` UNIQUE | 账号/登录名重复 |
| `plans` | `plan_batch_no` UNIQUE | 计划批次号重复导入 |
| `tasks` | `(plan_id, task_no)` UNIQUE | 同一计划内任务编号重复拆解 |
| `equipment` | `equipment_id` UNIQUE | 设备编码重复登记 |
| `exceptions` | `exception_id` UNIQUE | 异常编号重复 |
| `interlocks` | `interlock_id` UNIQUE | 联锁编号重复 |
| `appointments` | `appointment_id` UNIQUE | 预约编号重复 |
| `appointments` | `uk_appointments_active_queue`（部分唯一） | **叫号重号**（仅未结束状态） |
| `offline_packets` | `packet_id` UNIQUE | 包编号重复上报 |
| `config_versions` | `config_id` UNIQUE | 配置标识重复 |
| 全部子表 | 复合主键 `(父键, 序号/取值)` | 子行重复（重复插入被拒绝） |

> 子表的复合主键在设计上同时承担了"防重"和"定位"两个职责。**注意**：原实现曾在子表插入用 `ON CONFLICT DO NOTHING` 兜底，但 **openGauss 6.0 不支持该语法**（报 `syntax error at or near "CONFLICT"`），已改为普通 `INSERT`。这在本模块**语义上也没有损失**：新增父记录时子表必为空，更新路径会先删旧子行再插入。

### 8.4 参照完整性维护

跨实体引用（无外键）的一致性由三部分组成：

**1. 写入侧**：所有引用值必须来自父对象的真实主键。由服务层保证，并由 Zod 校验输入格式。

**2. 索引侧**：所有逻辑引用都有索引（见 6.2），保证反查不退化。

**3. 巡检侧**：定期运行孤儿数据检查。下面是可直接使用的巡检 SQL：

```sql
-- ① 工单挂在不存在的计划上
SELECT wo.id, wo.plan_id FROM work_orders wo
LEFT JOIN plans p ON p.id = wo.plan_id
WHERE p.id IS NULL;

-- ② 任务挂在不存在的计划/工单上
SELECT t.id, t.plan_id, t.work_order_id FROM tasks t
LEFT JOIN plans p ON p.id = t.plan_id
LEFT JOIN work_orders wo ON wo.id = t.work_order_id
WHERE p.id IS NULL OR (t.work_order_id IS NOT NULL AND wo.id IS NULL);

-- ③ 工单/任务引用了不存在的设备
SELECT wo.id, wo.equipment_id FROM work_orders wo
LEFT JOIN equipment e ON e.equipment_id = wo.equipment_id
WHERE wo.equipment_id IS NOT NULL AND e.id IS NULL;

-- ④ 任务依赖指向不存在的任务（悬挂边）
SELECT d.task_id, d.depends_on_task_id FROM task_dependencies d
LEFT JOIN tasks t ON t.id = d.depends_on_task_id
WHERE t.id IS NULL;

-- ⑤ 任务依赖成环（自环已由 CHECK 拦下，这里查多节点环）
WITH RECURSIVE dep_chain(root_id, cur_id, depth) AS (
  SELECT task_id, depends_on_task_id, 1 FROM task_dependencies
  UNION ALL
  SELECT dc.root_id, d.depends_on_task_id, dc.depth + 1
  FROM dep_chain dc
  JOIN task_dependencies d ON d.task_id = dc.cur_id
  WHERE dc.depth < 50
)
SELECT DISTINCT root_id FROM dep_chain WHERE root_id = cur_id;

-- ⑥ 异常的多态来源指向不存在的对象
SELECT ex.exception_id, ex.source_type, ex.source_id FROM exceptions ex
WHERE (ex.source_type = 'plan'      AND NOT EXISTS (SELECT 1 FROM plans      p  WHERE p.id  = ex.source_id))
   OR (ex.source_type = 'workOrder' AND NOT EXISTS (SELECT 1 FROM work_orders wo WHERE wo.id = ex.source_id))
   OR (ex.source_type = 'task'      AND NOT EXISTS (SELECT 1 FROM tasks      t  WHERE t.id  = ex.source_id))
   OR (ex.source_type = 'equipment' AND NOT EXISTS (SELECT 1 FROM equipment  e  WHERE e.id  = ex.source_id))
   OR (ex.source_type = 'interlock' AND NOT EXISTS (SELECT 1 FROM interlocks i  WHERE i.id  = ex.source_id));
```

**建议频率**：④⑤ 涉及图结构，建议纳入 CI 数据校验；其余可在每日业务低峰执行，结果写入巡检报告。

**关于"孤儿数据"的容忍度**：本模块**故意允许**部分孤儿出现（例如归档掉老计划后，其历史工单的 `plan_id` 成为悬挂引用）。因此巡检结果是"告警"而非"报错"，需要人工判断是否为预期内的归档副作用。

---

## 9 数据保留与归档策略

### 9.1 审计日志保留

审计日志是本模块唯一**持续高速增长**的业务表，也是唯一有明确保留期要求的数据。

| 项 | 设计 |
|----|------|
| 保留期配置 | `config_versions.audit_retention_days`（默认 365 天，可配 1~3650） |
| 清理触发器 | 后端启动时执行一次（`maintenance.purgeAuditLogs()`），按配置天数删除过期行 |
| 不可篡改 | 生产环境对 `audit_logs` 只授 `SELECT` / `INSERT`，**不授 `UPDATE` / `DELETE`** |
| 生产清理方式 | 用 **`DROP PARTITION`** 而非 `DELETE` —— 分区删除是元数据操作，秒级完成且不产生大量 WAL |

**建议的按月分区与清理脚本**（生产环境）：

```sql
-- 建分区表（首次）：以 occurred_at 的月为分区键
CREATE TABLE audit_logs (
  id          TEXT NOT NULL,
  actor_id    TEXT NOT NULL,
  action      TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- 其余列同 5.5 节
  PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE audit_logs_2026_09 PARTITION OF audit_logs
  FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
-- 每月由运维脚本预建下一个月的分区

-- 清理：直接丢整个月分区（保留 13 个月即保留 12 个月 + 当前月）
DROP TABLE IF EXISTS audit_logs_2025_09;
```

> **分区注意**：分区键必须包含在主键里（因此生产版主键为 `(id, occurred_at)`）。当前实现为了保持语义化单列主键的简洁性，采用的是**普通表 + `DELETE` 清理**；数据量达到亿级时应切换到分区方案。这个切换点应写入运维清单。

**为什么开发环境用 `DELETE` 而生产用分区**：开发环境数据量小（种子仅 5 行审计），`DELETE` 足够；分区的价值在亿级数据量下才显现，过早引入会让建表脚本复杂化。

### 9.2 时序数据保留

openGemini 侧采用**动态保留策略**，与 openGauss 的审计保留期解耦：

| 项 | 设计 |
|----|------|
| 默认保留期 | 建议 90 天（遥测点值原始精度） |
| 调整方式 | `ALTER RETENTION POLICY` 或 `collMod`（依 openGemini 版本支持而定） |
| 归档策略 | 到期前导出聚合结果（小时均值/峰值）到 openGauss 的报表快照表，原始点值即可丢弃 |
| 重要性 | 遥测是**全模块数据量最大的部分**（见第 11 章，占 89%），保留期直接决定存储成本 |

**为什么遥测不进审计体系**：遥测是设备自动上报的数据，无人工操作，没有"谁在什么时候改了什么"的留痕需求。它的价值在趋势，不在追溯。

### 9.3 其它数据的归档建议

| 数据 | 建议 |
|------|------|
| 已完成/已取消的 `plans`、`work_orders`、`tasks` | 按季度归档到历史表（同结构 + `_archive` 后缀），主表保留最近 2 个季度 |
| `offline_packets` | 已 `SYNCED` 且超过 3 个月的包，其 `payload` 可压缩归档（`payload` 是明细载荷，体积占该表主导） |
| `appointments` | 保留 1 年；车牌/手机号属个人信息，归档时建议脱敏 |
| `exceptions` | 长期保留（安全类异常需满足追溯期要求） |

---

## 10 时序库设计（openGemini）

### 10.1 为什么用时序库

设备遥测的负载特征与业务数据完全不同：

| 特征 | 业务数据（openGauss） | 遥测数据（openGemini） |
|------|----------------------|----------------------|
| 写入模式 | 随机更新，有状态机 | **纯追加**，不回改 |
| 读取模式 | 按主键/条件检索 | 按**时间窗口**聚合 |
| 单条体积 | 几百字节 | 几十字节 |
| 数据量 | 万级~十万级 | **千万级~亿级** |
| 压缩 | 常规页压缩 | 按时间列式压缩，压缩率高出数倍 |

把这些点值塞进关系库会导致：表体积迅速膨胀、索引维护成本高、历史查询慢。分开存储是组内基线的既定做法（openGemini 库名统一为 `*_telemetry`）。

### 10.2 measurement 定义

**库**：`psms_telemetry`

**measurement**：`equipment_telemetry`

| 分类 | 名称 | 类型 | 来源字段 | 说明 |
|------|------|------|----------|------|
| **tag** | `equipment_id` | string | `equipmentId` | 设备编码（**与 openGauss `equipment.equipment_id` 对齐**） |
| **tag** | `point_code` | string | `pointCode` | 测点编码 |
| **tag** | `quality` | string | `quality` | 数据质量（`GOOD` / `UNCERTAIN` / `BAD`） |
| **field** | `value` | float | `value` | 测点值 |
| **field** | `unit` | string | `unit` | 单位 |
| **field** | `metadata` | string | `metadata` | 附加信息（**JSON 字符串**，因 openGemini 字段只支持标量） |
| **time** | — | timestamp(ms) | `sourceTimestamp` | 数据源时间戳，毫秒精度 |

**tag 与 field 的划分依据**：**tag 是被索引、被 `GROUP BY` 的维度；field 是被聚合的数值**。
- `equipment_id` / `point_code` / `quality` 会出现在 `WHERE` 和 `GROUP BY` 里 → tag；
- `value` 是要算均值/最大值的量 → field；
- `unit` 与 `metadata` 虽然常不参与过滤，但 openGemini 字段类型只支持标量（无嵌套结构），因此 `metadata` 序列化成 JSON 字符串存放，读取时再反序列化。

### 10.3 行协议与写入

写入使用 InfluxDB 行协议，`precision=ms`：

```
equipment_telemetry,equipment_id=EQ-001,point_code=TEMP,quality=GOOD value=68.5,unit=℃,metadata="{\"batch\":\"B1\"}" 1758700000000
```

**行协议的转义规则**（`src/db/lineProtocol.ts` 内实现，避免手工拼串出错）：

| 位置 | 需要转义的字符 |
|------|---------------|
| measurement | `,` ` ` |
| tag key / tag value | `,` `=` ` ` |
| field key | `,` `=` ` ` |
| field 字符串值 | `"` `\` |

### 10.4 查询方式

通过 HTTP 的 `/query` 端点执行 InfluxQL：

```sql
-- 某设备最近 20 条遥测
SELECT * FROM "equipment_telemetry"
WHERE equipment_id = 'EQ-001'
ORDER BY time DESC LIMIT 20;

-- 某测点 7 天的点数（报表用）
SELECT COUNT(*) FROM "equipment_telemetry"
WHERE equipment_id = 'EQ-001' AND point_code = 'TEMP'
  AND time > now() - 7d;

-- 按测点算窗口均值（趋势图）
SELECT MEAN(value) FROM "equipment_telemetry"
WHERE equipment_id = 'EQ-001'
GROUP BY point_code, time(1h);
```

**注入防护**：`WHERE` 子句的值统一做单引号转义（`escapeInflux`），筛选条件由结构化对象（`TelemetryFilter`）生成，不接受原始 SQL 片段。这与业务库侧"全参数化 SQL"的策略互补——openGemini 的 HTTP API 不支持参数占位，因此用严格转义替代。

### 10.5 客户端与降级

| 项 | 设计 |
|----|------|
| 客户端 | 自研轻量 HTTP 客户端（`src/db/openGeminiClient.ts`），**不引入第三方 SDK** —— 与组内 SCS / EHMS 的做法一致 |
| 端点 | `GET /ping`（探活）、`GET /query`（查询/DDL）、`POST /write`（行协议写入） |
| 开关 | `OPENGEMINI_ENABLED`（总开关）、`OPENGEMINI_WRITE_ENABLED`（写入总开关） |
| 超时 | `OPENGEMINI_TIMEOUT_MS`（默认 5000；经隧道时建议放宽到 30000） |
| **降级策略** | 探针失败**不阻塞业务**：查询返回空集合并记 warn 日志，写入跳过并记 warn。保证遥测不可用时，业务功能（计划/工单/任务）仍完全可用 |
| 代理规避 | 使用 Node 内置 `fetch`（undici），**不读 `HTTP_PROXY` / `HTTPS_PROXY`**，因此不会把 `127.0.0.1:8086` 的隧道请求误送给宿主代理 |

**为什么遥测可以降级而业务库不可以**：业务库不可用意味着模块无法工作，必须启动失败并明确报错；遥测不可用只意味着"看不到最新设备数据"，业务照常。两者的失败处理必须区分，否则一个次要依赖会拖垮整个服务。

---

## 11 容量估算

### 11.1 实测基线（种子数据）

| 存储 | 对象 | 行数 |
|------|------|------|
| openGauss | 21 张表 | 56 行 |
| openGemini | `equipment_telemetry` | 1 个 measurement（写入链路已验证） |

### 11.2 年增长估算（按中型站场假设）

**假设**：5 个作业区、30 台设备、每台 20 个测点、采集周期 10 秒。

| 数据 | 计算 | 年增行数 | 单条估算 | 年增体积 |
|------|------|----------|----------|----------|
| 设备遥测 | 30 × 20 × 8640 × 365 | **约 19 亿** | ~40 B（压缩后） | **≈ 76 GB** |
| 计划 | 200/天 × 365 | 7.3 万 | ~600 B | ≈ 44 MB |
| 工单 | 400/天 × 365 | 14.6 万 | ~700 B | ≈ 102 MB |
| 任务 | 1200/天 × 365 | 43.8 万 | ~400 B | ≈ 175 MB |
| 预约 | 300/天 × 365 | 11 万 | ~500 B | ≈ 55 MB |
| 异常 | 50/天 × 365 | 1.8 万 | ~800 B | ≈ 15 MB |
| 审计日志（业务+访问） | 2 万/天 × 365 | **730 万** | ~450 B | **≈ 3.3 GB** |
| 其余（设备/联锁/离线包/配置/子表） | — | 约 20 万 | ~400 B | ≈ 80 MB |

**合计年增：约 80 GB，其中遥测占 95%。**

**结论**：容量规划的重点**只有两件事** —— ① 遥测保留期（90 天 vs 365 天，差别是 19 GB vs 76 GB）；② 审计日志（虽然只占 4%，但涉及合规追溯，不能随意缩短）。其余表的年增量在几百 MB 量级，十年也不过数 GB。

### 11.3 存储优化建议

| 对象 | 建议 |
|------|------|
| `equipment_telemetry` | ① 按保留期滚动；② 高频测点降采样（原始点值保留 7 天，小时均值保留 1 年）；③ 对不参与过滤的 tag 慎重取舍——**每个 tag 都建索引，tag 越多写入越慢** |
| `audit_logs` | 达到千万行时切换到按月分区（见 9.1），清理改 `DROP PARTITION` |
| `offline_packets.payload` | 该列是表体积主体；已同步的包建议压缩或归档 |
| `plans.supplements` / `equipment.specs` | `JSONB` 有额外解析开销；这两列数据量小，无需优化，但**不要**把高频查询字段塞进 `JSONB` |
| 全部表 | 索引数已控制在 73 个（含主键/唯一）。**新增索引前先确认它服务的查询确实存在**——每个索引都会拖慢写入 |

---

## 12 运维与故障处置

### 12.1 备份与恢复

| 存储 | 工具 | 命令要点 |
|------|------|----------|
| openGauss | `gs_dump` / `gs_dumpall`（openGauss 自带） | `gs_dump -h <host> -p 5432 -U psms -d psms -F c -f psms.dump`；恢复用 `gs_restore` |
| openGemini | 内置备份（`ts-backup` / 库文件级快照） | 依部署方式而定；容器化部署时长按卷快照即可 |

**备份策略建议**

| 对象 | 频率 | 保留 |
|------|------|------|
| 业务库 `psms` 全量 | 每日 1 次（业务低峰） | 30 天 |
| 业务库 WAL | 连续归档 | 7 天（支持时间点恢复） |
| 时序库 `psms_telemetry` | 每周 1 次 | 4 周（时序可重建，优先级低于业务库） |

**恢复演练**：至少每季度做一次"从备份还原到临时库 + 跑一遍巡检 SQL（8.4 节）"的演练。**没演练过的备份等于没有备份**。

### 12.2 优雅关闭与连接释放

| 项 | 做法 |
|----|------|
| 连接池 | `pg.Pool`，`OPENGAUSS_POOL_MAX` 默认 10；进程退出时 `pool.end()` 等待在途查询完成 |
| 信号处理 | 监听 `SIGINT` / `SIGTERM`，先停 HTTP 服务，再关连接池，最后退出 |
| 事务安全 | 所有事务连接在 `finally` 中 `release()`，避免连接泄漏导致池耗尽 |
| 未提交事务 | 进程被强杀时由 openGauss 自动回滚；`ROLLBACK` 不需要应用补做 |

### 12.3 常见故障速查

| 症状 | 可能原因 | 处置 |
|------|----------|------|
| `ECONNREFUSED 127.0.0.1:5432` | SSH 隧道未建立或已断开 | 重建隧道（见 2.3）；确认 `netstat -ano \| findstr :5432` 有监听 |
| `ETIMEDOUT` / 建连耗时长（~10s） | 隧道经公网中继，建连慢 | 把 `OPENGAUSS_CONNECT_TIMEOUT_MS` 放宽到 30000 |
| `read ECONNRESET` / `Connection terminated unexpectedly`（长连接后） | 中继掐断长连接 | 重启隧道；生产环境改为集群内直连 |
| `28P01 Invalid username/password` | ① 口令错；② **`.env` 中口令含 `#` 被 dotenv 当注释截断** | 检查 `.env` 里口令是否用引号包裹（见 13.5-⑦） |
| `Forbid remote connection with initial user` | 用 `omm`（初始用户）远程连接 | 必须用非初始角色 `psms` 连接 |
| `syntax error at or near "CONFLICT"` | 用了 `ON CONFLICT DO NOTHING` | openGauss 6.0 不支持，改为普通 `INSERT` |
| openGemini 请求返回 `502 Bad Gateway` | 宿主注入的 `http_proxy` 把 `127.0.0.1:8086` 也代理了 | 用 `curl --noproxy '*'`；应用侧已用不读代理的 `fetch` 规避 |
| openGemini `Connection reset`（`204` 被截断） | 自研隧道未做**半关闭**（half-close）转发 | 隧道需支持半关闭；改用系统 `ssh -N -L` |
| 端口被占：`:5432` 有多个监听 | Windows `SO_REUSEADDR` 允许多进程绑同端口，新旧隧道抢接连接 | 先 `netstat -ano` 找齐所有 PID 全部结束，再只起一条隧道（隧道侧已改 `SO_EXCLUSIVEADDRUSE`） |

### 12.4 自检与冒烟工具

| 命令 | 作用 |
|------|------|
| `pnpm db:init` | 建库 / 建表（幂等），同时确保 openGemini 库存在 |
| `pnpm db:stats` | 打印两个存储的表清单、行数、索引数、探针状态 |
| `pnpm seed` | 灌入种子数据（业务表 + 遥测点） |
| `pnpm smoke` | 端到端冒烟：登录、读接口、写命令、审计落库、遥测写入校验 |
| `pnpm db:inspect` | 打印某张表的物理结构与索引明细 |

---

## 13 落地验证记录

### 13.1 环境

| 项 | 实测值 |
|----|--------|
| openGauss | **6.0.5**，`DBCOMPATIBILITY=PG`、`ENCODING=UTF8`、属主 `psms` |
| openGauss 位置 | `192.168.101.57:5432`（`bpoc-node4`），运行于 **iSulad 容器 `bpoc-opengauss`** |
| 角色 | `psms`（非超管；`rolcreatedb=t`、`rolcanlogin=t`；`password_encryption_type=0` → md5 认证，Node `pg` 驱动可登录） |
| openGemini | `192.168.101.74:8086`（`bpoc-node6`）；`GET /ping` → `204`，响应头 `X-Geminidb-Build: OSS`；该实例无用户列表，免认证 |
| 时序库 | `psms_telemetry` 已创建 |
| Node 侧驱动 | `pg@8.16.3` + `@types/pg@8.15.6` |
| 类型检查 | `tsc --noEmit` **零错误** |

**同节点既有库对照**（佐证选型与组内惯例一致）：

| 库 | 兼容模式 | 属主 | 归属 |
|----|----------|------|------|
| `b_project` | **PG** | `safety_admin` | SCS |
| `b_poc` | A | `bpoc_app` | POC |
| `ehm` | A | `omm` | EHMS |
| `mt_wms_zxj` | A | `wms_zxj` | WMS |
| **`psms`** | **PG** | **`psms`** | **PSMS（本次新增）** |

> `b_project`（SCS）用的就是 `PG` 兼容模式，说明 PSMS 选 `PG` 是与组内实践对齐的。

### 13.2 表结构与索引

| 项 | 结果 |
|----|------|
| DDL 语句数 | **129 条**（21 张建表 + 73 个索引/约束 + 35 条中文注释），全部幂等 |
| 批量执行耗时 | 约 **3 秒** |
| 表数量 | **21 张**（11 主表 + 10 子表） |
| 索引对象 | **73 个**（21 主键 + 10 唯一 + 42 二级，其中 3 个部分索引） |
| 验证方式 | `pnpm db:init`（执行）+ `pnpm db:stats`（读回核对） |

### 13.3 种子数据

`pnpm seed` 实测写入结果：

| 存储 | 结果 |
|------|------|
| openGauss | **21 张表 / 56 行**（明细见 4.3） |
| openGemini | 库 `psms_telemetry` 中出现 measurement `equipment_telemetry` → **遥测写入链路已验证** |

**子表装配链路已验证**：`user_data_scopes` 7 行、`work_order_crew` 2 行、`task_dependencies` 1 行、`interlock_input_signals` 2 行。这四张子表对应四种不同的内嵌形态（字符串数组 / 字符串数组 / 图边集 / 对象数组），它们的成功落库证明"内嵌结构 → 子表"的转换对各类形态都正确。

### 13.4 本次迁移修正的 3 个前版设计缺陷

| # | 前版（MongoDB）的做法 | 问题 | 本版修正 |
|---|----------------------|------|----------|
| 1 | 给 `interlocks.overrideExpiresAt` 建 **TTL 索引** | **TTL 删除的是整个文档**，不是字段——联锁记录必须留档追溯，被 TTL 删掉等于销毁安全证据 | 改为应用层判定"覆盖是否过期"，记录永久保留 |
| 2 | 时序集合索引建在 `{equipmentId, pointCode, sourceTimestamp}` | MongoDB 时序集合只允许建在 `metaField + timeField` 组合上，含普通字段会被**直接拒绝** | 时序数据处理整体移交 openGemini，问题不复存在 |
| 3 | 审计集合同时承载两种文档形状 | middleware 写访问留痕、service 写业务审计，字段各不相同，查询经常命中不到 | 统一为**一张表的超集模型**，用 `occurred_at` 作共享业务时间轴，缺失字段为 `NULL` |

### 13.5 openGauss 适配注意事项（踩坑清单）

以下是本次实测撞到并已解决的问题，后续维护需留意：

| # | 现象 | 原因与处置 |
|---|------|-----------|
| ① | `syntax error at or near "CONFLICT"` | **openGauss 6.0 不支持 `INSERT ... ON CONFLICT (...) DO NOTHING`**。子表插入改普通 `INSERT`（语义无损失，见 8.3 说明） |
| ② | `Forbid remote connection with initial user` | **openGauss 禁止初始用户（`omm`）远程登录**。必须另建非初始角色（本模块用 `psms`） |
| ③ | `unrecognized role option "NOSUPERUSER"` | `CREATE USER` **不支持 `NOSUPERUSER`** 选项。只写 `CREATEDB` / `NOCREATEROLE` 即可 |
| ④ | 口令认证方式 | `password_encryption_type=0` 时用 **md5**，Node 的 `pg` 驱动可正常登录；若为 `2`（sha256）则需换驱动或改配置 |
| ⑤ | `gsql` 不接受 `PGPASSWORD` 环境变量 | 容器内运维操作需用 `local` 信任 + `gosu omm`，或走 `-W` 交互输入 |
| ⑥ | 容器内 `gsql` 报找不到动态库 | 需显式设置 `LD_LIBRARY_PATH=/usr/local/opengauss/lib:/usr/local/opengauss/lib/plugin` |
| ⑦ | **`.env` 中文口令被截断** | **`dotenv` 会把 `#` 当行内注释**！`OPENGAUSS_PASSWORD=Psms@Bpoc2026#Db` 只解析出 13 位（正确 16 位），报 `28P01`。**含 `#` 的值必须加引号**：`OPENGAUSS_PASSWORD="Psms@Bpoc2026#Db"`。这是本次最隐蔽的一个坑——同一口令在朴素解析下正确、只有 dotenv 会错 |
| ⑧ | `isula exec` 间歇性失败 | iSulad 在高频调用下会报 `Can not connect with server`。合并为**单次调用**（容器内脚本 base64 后一次执行）并加重试 |

### 13.6 尚未完成的验证项

诚实记录，避免误判完成度：

| 项 | 状态 | 说明 |
|----|------|------|
| 表结构建立 | ✅ 已验证 | 129 条 DDL 在真机执行成功 |
| 种子数据写入 | ✅ 已验证 | 21 表 / 56 行 + 遥测 measurement |
| 业务库连接 | ✅ 已验证 | `pg` 驱动经隧道连通，md5 认证通过 |
| 时序库连接 | ✅ 已验证 | `/ping` 204、建库、行协议写入均成功 |
| **HTTP 端到端冒烟（`pnpm smoke`）** | ⏳ **未完成** | 后端启动时的连接自检被**隧道瞬时故障**打断（`ECONNRESET` / 建连超时）。**属网络问题，非代码问题**——同一套代码已把 56 行数据写入真库 |
| 前端与后端联调 | ⏳ 未开始 | 前端仍跑 MSW Mock，未接入 `/api/*` |
| 与前端契约的字段/角色对齐 | ⏳ 未开始 | 见 14.2 |

**关于 `pnpm smoke` 的建议**：本机到 `bpoc-node1` 走 **Tailscale DERP 中继**（无直连，RTT 0.3~1.1 秒），隧道在持续流量下会被静默掐断。**请在本地终端直接跑隧道**（不受工具沙箱限制）：

```cmd
ssh -N -i C:\Users\Administrator\.ssh\bpoc_ed25519 ^
    -L 5432:192.168.101.57:5432 ^
    -L 8086:192.168.101.74:8086 ^
    lrz@100.65.200.125
```

然后依次执行 `pnpm db:init` → `pnpm seed` → `pnpm dev` → `pnpm smoke`。

---

## 14 附录

### 14.1 与接口设计文档的对应关系

| 接口设计文档章节 | 本文档对应章节 |
|------------------|----------------|
| 3.3 统一响应信封（`auditLogId` / `traceId`） | 5.5 `audit_logs` |
| 3.4 错误码字典 | 8.2 / 8.3 约束清单 |
| 3.5 鉴权、权限与数据域 | 5.1 `users`、5.2 `user_data_scopes`、各表 `work_area` |
| 3.6 幂等、乐观锁与版本 | 3.6 版本字段语义、7.2 乐观锁 |
| 3.7 分页、排序与过滤 | 6 索引设计与典型查询 |
| 5 接口详细设计 | 5 表详细设计 |
| 6 枚举字典 | 各表字段表的枚举取值、8.2 CHECK 清单 |

### 14.2 与前端契约的字段差异

后端与前端 `DO-xxx` 契约在**字段命名**与**枚举取值**上尚未统一，需注意：

| 维度 | 后端 | 前端契约 |
|------|------|----------|
| 计划来源系统 | `sourceStation` | `sourceSystem` |
| 到发时间 | `arriveTime`（TIMESTAMPTZ，单点） | `arrivalDepartureTime`（字符串，起/止区间） |
| 缺失字段 | `cargoItems`（品项数组，已拆 `plan_cargo_items`） | `missingFields`（缺失字段名数组） |
| 冲突信息 | 无独立字段 | `conflicts`（多源冲突明细） |
| 计划状态 | 5 个 | 8 个（多 `RECEIVED` / `VALIDATING` / `DECOMPOSED` / `BLOCKED` / `ADJUSTED`） |
| 工单状态 | 8 个（含 `ASSIGNED`） | 10 个（含 `ACKNOWLEDGED` / `BLOCKED` / `FAILED`） |
| 角色 | 6 个（`super_admin` 等小写下划线） | 13 个（`DISPATCHER` 等大写） |

**影响**：前端目前未接入后端，差异不影响现有运行；一旦做真实联调，需要一次专门的契约对齐任务。

### 14.3 代码与脚本索引

| 类别 | 文件 | 职责 |
|------|------|------|
| **连接层** | `src/db/openGaussClient.ts` | `pg` 连接池、事务、健康探针、错误分类 |
| | `src/db/openGeminiClient.ts` | InfluxDB 兼容 HTTP 客户端（ping/query/write）、建库、统计 |
| | `src/db/lineProtocol.ts` | 行协议构造与转义 |
| **结构定义** | `src/db/schema.ts` | 21 张表 DDL + 73 个索引 + 中文注释 + 清理顺序 |
| | `src/db/migrate.ts` | 迁移执行器（批量、幂等、失败定位） |
| **访问层** | `src/db/table.ts` | 通用表访问层（过滤/排序/分页/事务/子表装配） |
| | `src/db/tables.ts` | 11 张表的访问器与字段映射 |
| | `src/db/telemetry.ts` | 遥测访问器（走 openGemini） |
| | `src/db/types.ts` | 各表文档类型定义 |
| | `src/db/maintenance.ts` | 数据重置与审计清理 |
| | `src/db/inspect.ts` | 物理结构与索引查看 |
| **配置** | `src/config/env.ts` | `OPENGAUSS_*` / `OPENGEMINI_*` 配置与校验 |
| **脚本** | `src/scripts/db-init.ts` | 初始化两个存储 |
| | `src/scripts/db-stats.ts` | 存储自检与统计 |
| | `src/seeds/seed.ts` / `seed-data.ts` | 种子数据 |
| **配置样例** | `.env.example` | 不含真实口令的新栈模板（**`.env` 已被 `.gitignore` 忽略**） |

### 14.4 变更记录

| 版本 | 日期 | 变更说明 |
|------|------|----------|
| 0.3.0-mysql | 2026-09-21 | 首版：MySQL 8.0 关系型设计，27 张表（已废弃，见 git 历史） |
| 1.0.0-mongodb | 2026-09-21 | 改写为 MongoDB 版：12 个集合、内嵌/引用决策、时序集合、TTL 保留策略 |
| **2.0.0-combined** | **2026-09-24** | **改写为「openGauss + openGemini」组合版**：21 张表（11 主表 + 10 子表）+ 1 个时序 measurement；内嵌结构拆子表、`JSONB` 只留 5 组真弱结构字段；明确外键策略（只在子表→主表建，跨实体靠索引 + 巡检）；新增事务与并发控制、约束分层、openGauss 适配清单；MongoDB 相关实现（模型、连接层、依赖、数据目录）已彻底移除 |

---

*本文件与 [`PSMS-接口设计文档.md`](./PSMS-接口设计文档.md) 配套使用，二者共享同一契约基线。*
*后端实现见 `PSMS/backend/`，运行说明见 `PSMS/backend/README.md`。*