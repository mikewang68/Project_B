# PSMS 生产调度管理模块 —— 接口设计文档

| 项目 | 内容 |
|------|------|
| 文档名称 | PSMS 生产调度管理模块接口设计文档 |
| 模块代号 | PSMS（Production Scheduling Management System） |
| 所属项目 | Project_B 数字孪生 / 综合监控一体化项目 |
| 契约基线版本 | 0.3.0（OpenAPI 文档版本 0.2.0） |
| 冻结规模 | 15 个领域对象 / 25 个接口 / 7 个演示场景 / 9 个公开错误码 |
| 文档状态 | 设计基线 |
| 编写日期 | 2026-09-21 |

---

## 目录

1. [文档说明](#1-文档说明)
2. [模块概述](#2-模块概述)
3. [接口总体设计](#3-接口总体设计)
4. [接口清单](#4-接口清单)
5. [接口详细设计](#5-接口详细设计)
6. [枚举字典](#6-枚举字典)
7. [状态机与命令约束](#7-状态机与命令约束)
8. [附录](#8-附录)

---

## 1 文档说明

### 1.1 编写目的

本文档定义 PSMS（生产调度管理）模块对外提供的全部接口契约，包括接口清单、请求/响应结构、错误码、权限与数据域约束、幂等与并发控制策略以及状态流转前置条件。

本文档同时承担两个职责：

- **对外**：作为前后端联调、接口对接、测试用例编写与验收的共同依据；
- **对内**：作为模块内部各业务功能（计划接收、任务拆解、派工执行、安全联锁等）之间"命令—状态—审计"闭环的规范说明。

### 1.2 适用范围与读者

| 读者角色 | 使用方式 |
|----------|----------|
| 后端开发工程师 | 依据第 5 章实现接口，依据第 7 章实现状态机校验 |
| 前端开发工程师 | 依据第 3、4 章完成请求封装与统一错误处理 |
| 测试工程师 | 依据第 5、8 章编写接口用例与场景用例 |
| 系统架构师 / 评审人 | 依据第 3、7 章评审契约完整性与一致性 |

### 1.3 术语与缩写

| 术语 | 说明 |
|------|------|
| PSMS | 生产调度管理系统，本模块 |
| DO（Domain Object） | 领域对象，模块内可被命令操作、带版本号且需审计的业务实体，共 15 个 |
| API | 本模块对外暴露的接口单元，共 25 个 |
| SCN | 演示场景（Demo Scenario），共 7 个 |
| 信封（Envelope） | 所有接口统一的响应外层结构，成功与失败共用 `ok/traceId/auditLogId` 骨架 |
| traceId | 链路追踪标识，与审计日志一对一关联 |
| auditLogId | 审计日志主键，由服务端生成 |
| expectedVersion | 乐观锁版本号，客户端提交其持有的对象版本 |
| 数据域（Data Scope） | 角色可访问的数据范围，取值域为作业区 / 全局 / 本人 |
| Mock | 本地网页 Demo 环境下由 MSW（Mock Service Worker）拦截并返回的模拟接口 |

### 1.4 参考资料

| 文档 | 说明 |
|------|------|
| `docs/baseline/openapi.yaml` | OpenAPI 3.1 契约基线，本文档的机器可读来源 |
| `docs/baseline/demo-fixtures.json` | 固定种子数据：15 类领域对象、13 个角色、7 个场景、9 个错误码 |
| `docs/baseline/traceability.csv` | 需求 → 页面 → 接口 → 测试 追溯矩阵 |
| `docs/baseline/page-task-matrix.csv` | 页面任务与接口分配矩阵 |
| `src/contracts/api.ts` | 接口目录（apiCatalog）运行时定义 |
| `src/contracts/schemas.ts` | 15 个领域对象与信封的 Zod 校验定义 |
| `src/contracts/enums.ts` | 全部枚举字典定义 |
| `src/contracts/requests.ts` | 14 个请求体 Schema 定义 |
| `src/commands/stateMachines.ts` | 8 个状态机的状态、命令与合法迁移 |

> **后端实现指引**：模块已提供真实后端服务（Express 5 + MongoDB 7），
> 接口路径前缀由 `/mock/*`（MSW 拦截）变为 `/api/*`，路径语义与 `operationId` 保持一致。
> 路由清单、启动方式与账号见 [`../backend/README.md`](../backend/README.md)；
> 数据模型见 [`PSMS-数据库设计文档.md`](./PSMS-数据库设计文档.md)。

### 1.5 契约冻结声明

本模块契约已冻结，规模固定为 **15 / 25 / 7 / 9**：

- 15 个领域对象（`DO-001` ~ `DO-015`）；
- 25 个接口（`API-001` ~ `API-025`）；
- 7 个演示场景（`SCN-01` ~ `SCN-07`）；
- 9 个公开错误码。

任何字段、枚举、接口路径或错误码的变更必须另立变更任务并同步更新契约基线文件，**不得在页面层或业务层另行定义**。字段权威顺序为：需求分析文档 → 主技术设计 → Mock 接口与数据契约 → 页面任务卡 → 实施基线。

---

## 2 模块概述

### 2.1 模块定位

PSMS 面向货场/站场的**生产调度全流程**，覆盖从外部到发计划接入、接车窗口推荐、任务拆解、工单派发、公路预约叫号、运行监控、异常处置、安全联锁、离线补传、统计报表到系统配置与审计追溯的完整闭环。

模块在设计上遵循三条主线：

1. **命令驱动**：所有状态变更均由"命令"触发，命令携带 `expectedVersion`，服务端统一执行"鉴权 → 校验 → 状态迁移 → 落库 → 写审计"五步流水线。
2. **版本受控**：每个领域对象持有整型 `version`，任何写操作都会导致版本自增，版本不匹配返回 `409 DEMO-VERSION-001`。
3. **全程留痕**：任何命令（含被拒绝的）都会生成审计记录，响应信封中必定回带 `auditLogId` 与 `traceId`。

### 2.2 业务能力域

| 编号 | 能力域 | 说明 | 对应页面 | 对应接口 |
|------|--------|------|----------|----------|
| D1 | 计划接收与校验 | 外部到发信息同步、字段完整性校验、多源冲突识别、人工补充与确认 | UI-001 / UI-002 | API-001 ~ API-004, API-025 |
| D2 | 接车计划推荐 | 基于股道占用、货类兼容性、释放时间的多因子打分与窗口推荐 | UI-003 | API-005, API-006 |
| D3 | 任务拆解 | 按货类与作业路由自动生成工单与作业节点树，支持人工编辑 | UI-004 | API-007 |
| D4 | 派工与执行 | 资源匹配、派工下发、接收回执模拟、改派 | UI-005 | API-008, API-009 |
| D5 | 公路预约与叫号 | 预约提报、资质审核、排队叫号、入场引导、离场回写 | UI-006 | API-010, API-011 |
| D6 | 运行监控 | 人机车态势快照、节点交账、超时卡顿识别、场景播放 | UI-007 | API-012, API-013 |
| D7 | 异常处置 | 异常告警、认领、指派、处置、复核、闭环与重开 | UI-008 | API-014, API-015 |
| D8 | 安全联锁 | 联锁触发、控制动作下发、回执、复位申请与审批、覆盖 | UI-009 | API-016, API-017 |
| D9 | 离线同步 | PDA 离线包缓存、补传、校验、合并、冲突处置与重试 | UI-010 | API-018, API-019 |
| D10 | 统计报表 | 班报/日报/月报生成、指标口径、多维分布、受控导出 | UI-011 | API-020, API-021 |
| D11 | 系统配置 | 配置版本草稿、提交、审批、发布与回滚 | UI-012 | API-022, API-023 |
| D12 | 审计追溯 | 全量操作审计查询、证据链、受控导出 | UI-013 | API-024 |

### 2.3 角色与权限模型

模块定义 **13 个角色**，角色编码为全局枚举 `RoleCode`。

| 角色编码 | 角色名称 | 核心职责边界 |
|----------|----------|--------------|
| `DISPATCHER` | 货场调度员 | 计划确认与调整、任务拆分、派工改派、一般异常处置；无高风险联锁复位/覆盖与权限配置审批权 |
| `SHIFT_LEADER` | 现场班组长 | 本班任务查看、接单组织、进度反馈、一般异常复核、班次交接；不得调整跨班组计划或审批本人申请 |
| `OPERATOR` | 现场作业人员 | 接收本人任务、节点交账、附件上传、异常上报；不得改派、关闭安全异常或复位联锁 |
| `MAINTAINER` | 设备维保人员 | 设备状态确认、故障处理、试运行、复位申请；不得审批本人申请 |
| `WAREHOUSE` | 仓储/物资管理员 | 货位/筒仓确认、入出库校验、库存差异处理；不得绕过联锁 |
| `GATE_GUARD` | 门岗人员 | 车辆证件核验、入离场登记、电子放行执行 |
| `DRIVER` | 外来货车司机 | 提交本人预约与资质、查询叫号与路径、确认本人运输任务 |
| `SAFETY` | 安环负责人 | 安全规则与阈值审批、联锁复位/覆盖审批、安全异常复核；不得申请并审批同一高风险动作 |
| `BUSINESS` | 经营管理人员 | 经营看板、统计报表、运营分析的全局只读访问 |
| `REGULATOR` | 监管人员 | 安全环保合规证据与监管报表只读查询、受控导出；敏感字段脱敏 |
| `SYS_ADMIN` | 系统管理员 | 账号、角色、权限与数据域配置；不得修改/删除审计日志或审批自身权限提升 |
| `INTERFACE_OPS` | 接口运维员 | 接口参数、同步重试、熔断恢复、连接状态维护 |
| `AUDITOR` | 独立审计员 | 审计日志、审批链、操作证据的全局只读查询与受控导出；不得参与被审计动作审批 |

权限模型由三层组成：

1. **页面权限**（`routeRequiredPermissions`）：控制 13 个页面（`UI-001` ~ `UI-013`）的访问。
2. **动作权限**（`actionPolicies`）：控制 42 个细粒度权限码（如 `plan:confirm`、`dispatch:assign`、`interlock:approve`）对应的角色集合。
3. **数据域**（`dataScope`）：控制行级可见范围，规则如下。

| 对象数据域类型 | 判定规则 |
|----------------|----------|
| `GLOBAL` | 仅当会话数据域包含 `GLOBAL` 或 `*` 时可见 |
| `AREA` | 会话数据域包含 `GLOBAL`/`*`，或包含该区域编码时可见 |
| `SELF` | 对象 `ownerId` 等于会话 `actorId` 时可见 |

### 2.4 页面—接口映射

| 页面编号 | 页面名称 | 路由 | 使用接口 |
|----------|----------|------|----------|
| UI-001 | 调度总览 | `/dispatch/overview` | API-001, API-013, API-025 |
| UI-002 | 计划接收台账 | `/dispatch/plans` | API-002, API-003, API-004, API-025 |
| UI-003 | 接车计划推荐 | `/dispatch/plans/:planId/recommendation` | API-005, API-006 |
| UI-004 | 任务拆解 | `/dispatch/plans/:planId/tasks` | API-007 |
| UI-005 | 派工看板 | `/dispatch/work-orders` | API-008, API-009 |
| UI-006 | 公路预约与叫号 | `/yard/appointments` | API-010, API-011 |
| UI-007 | 全流程监控 | `/monitor/operations` | API-012, API-013 |
| UI-008 | 异常处置 | `/monitor/exceptions` | API-014, API-015 |
| UI-009 | 安全联锁 | `/safety/interlocks` | API-016, API-017 |
| UI-010 | 离线同步 | `/operations/offline-sync` | API-018, API-019 |
| UI-011 | 统计报表 | `/reports/operations` | API-020, API-021 |
| UI-012 | 系统配置 | `/settings/system` | API-022, API-023 |
| UI-013 | 审计日志 | `/governance/audit` | API-024 |

---

## 3 接口总体设计

### 3.1 设计原则

| 原则 | 说明 |
|------|------|
| 资源化路径 | 路径以名词复数表达资源（`/plans`、`/work-orders`），以子路径表达动作（`/confirm`、`/decompose`） |
| 读写分离 | 查询使用 `GET`，状态变更使用 `POST`；`GET` 不得携带请求体 |
| 严格模式 | 所有 Schema 均声明 `additionalProperties: false`，出现未定义字段即判定请求非法 |
| 统一信封 | 所有响应外层结构一致，业务差异集中在 `data` 内 |
| 显式版本 | 所有写操作必须携带目标对象的 `expectedVersion` |
| 全量留痕 | 无论成功或失败，响应均回带 `auditLogId` 与 `traceId` |
| 可观测 | 每个接口标注权限要求（`x-permission`）、时延策略（`x-latency`）与幂等策略（`x-idempotency`） |

### 3.2 路径与命名规范

- 基础前缀：Mock 环境统一为 `/mock`；生产对接时替换为业务网关前缀（如 `/api/psms/v1`），**路径语义与 operationId 保持不变**。
- 路径参数使用大驼峰外的短名 `{id}`，MSW 内部以 `:id` 形式注册；两者必须可互相转换。
- `operationId` 命名规则：`<METHOD>_<lower_snake_case_path>`，例如 `POST /mock/plans/{id}/confirm` → `POST_mock_plans_id_confirm`。
- 每个接口在契约中标注 `x-api-id`（`API-0xx`）与 `x-msw-path`，作为前端与 Mock 层互认的锚点。

### 3.3 统一响应信封

#### 3.3.1 成功信封 `ApiSuccessEnvelope`

```json
{
  "ok": true,
  "data": { "...": "业务数据" },
  "auditLogId": "AUD-001",
  "traceId": "TRACE-001"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `ok` | boolean | 是 | 固定为 `true` |
| `data` | object | 是 | 业务数据体，结构由各接口定义 |
| `auditLogId` | string | 是 | 对应审计日志主键 `DO-013.id` |
| `traceId` | string | 是 | 链路追踪标识 |

#### 3.3.2 失败信封 `ApiErrorEnvelope`

```json
{
  "ok": false,
  "errorCode": "DEMO-VERSION-001",
  "message": "对象版本已变化，请刷新。",
  "details": { "expectedVersion": 1, "actualVersion": 2 },
  "auditLogId": "AUD-001-409",
  "traceId": "TRACE-001-409"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `ok` | boolean | 是 | 固定为 `false` |
| `errorCode` | string | 是 | 取自 9 个公开错误码枚举 |
| `message` | string | 是 | 面向用户的中文提示 |
| `details` | object | 否 | 结构化补充信息，如版本冲突详情 |
| `auditLogId` | string | 是 | 失败同样写入审计 |
| `traceId` | string | 是 | 链路追踪标识 |

#### 3.3.3 专用成功信封

`API-022` 与 `API-023` 使用精确化信封 `API022SuccessEnvelope` / `API023SuccessEnvelope`，其 `data` 被约束为固定结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| `apiId` | string | 字面量 `API-022` 或 `API-023` |
| `operationId` | string | 字面量 `GET_mock_config` 或 `POST_mock_config_id_command` |
| `now` | string | 服务端当前时间（ISO 8601 带时区） |
| `scenarioId` | string | 当前激活场景 |
| `items` | array | `API-022` 返回配置版本数组；`API-023` 强制长度为 1 |

`items` 元素结构为 `DO-015 ConfigVersion`。

### 3.4 错误码字典与状态码映射

所有接口均声明 `400 / 403 / 409` 三种失败响应，统一引用 `ApiErrorEnvelope`。

| 错误码 | 含义 | HTTP 状态 | UI 处理建议 | 是否审计 |
|--------|------|-----------|-------------|----------|
| `TOS-EXT-001` | 外部接口超时或不可用 | 400 | 提示重试并保留当前上下文 | 是 |
| `TOS-EXT-002` | 权威输入字段缺失 | 400 | 标记缺失字段并阻断确认 | 是 |
| `TOS-EXT-003` | 多源数据冲突 | 409 | 展示冲突来源并要求人工裁决 | 是 |
| `TOS-WO-001` | 作业单资源或设备不可用 | 409 | 阻断派工并提示替换资源 | 是 |
| `TOS-IL-001` | 安全联锁触发 | 409 | 强提示并禁止继续执行 | 是 |
| `TOS-OFF-001` | 离线包版本冲突 | 409 | 进入冲突处置流程 | 是 |
| `TOS-AUTH-001` | 权限不足或职责冲突 | 403 | 拒绝操作并提示联系负责人 | 是 |
| `DEMO-VERSION-001` | 对象版本已变化 | 409 | 刷新对象后重新操作 | 是 |
| `DEMO-SCENARIO-001` | 请求结构、枚举、状态或场景不允许 | 400 | 展示校验问题并保持当前页 | 是 |

**HTTP 状态码选用规则**：

| 状态码 | 触发条件 |
|--------|----------|
| `400` | 请求体结构不合法、枚举越界、必填字段缺失、当前状态不允许执行该命令 |
| `403` | 角色无对应动作权限，或对象不在会话数据域内 |
| `409` | 乐观锁版本冲突、外部多源数据冲突、资源不可用、联锁阻断、离线包冲突 |
| `200` | 命令被接受并成功提交（含幂等重放） |

> 注：`DEMO-VERSION-001` 的业务语义为 409 冲突，但契约中错误码字典统一声明其 `httpStatus` 为 409；`DEMO-SCENARIO-001` 与 `TOS-EXT-001`/`TOS-EXT-002` 归入 400 组。

### 3.5 鉴权、权限与数据域

每个接口在契约中均标注 `x-permission: "页面权限与数据域均通过后允许调用。"`，实际校验顺序如下：

```
① 会话有效性校验
     ↓
② 页面权限校验（routeRequiredPermissions[pageId]）
     ↓ 失败 → 403 TOS-AUTH-001
③ 动作权限校验（actionPolicies[permissionCode] 是否包含会话 roleCode）
     ↓ 失败 → 403 TOS-AUTH-001
④ 数据域校验（isWithinDataScope，按 GLOBAL / AREA / SELF 判定）
     ↓ 失败 → 403 TOS-AUTH-001
⑤ 请求结构校验（Zod strict schema）
     ↓ 失败 → 400 DEMO-SCENARIO-001
⑥ 状态机迁移校验（transitionState）
     ↓ 失败 → 400 DEMO-SCENARIO-001（UNKNOWN_STATE / UNKNOWN_COMMAND / FORBIDDEN_TRANSITION）
⑦ 乐观锁版本校验（expectedVersion vs 对象当前 version）
     ↓ 失败 → 409 DEMO-VERSION-001
⑧ 业务规则与外部依赖校验
     ↓ 失败 → 409 对应业务错误码
⑨ 提交变更 + 版本自增 + 写审计（同一事务）
```

**职责冲突硬约束**（必须由服务端强制，不得依赖前端）：

| 约束 | 说明 |
|------|------|
| 自审批禁止 | 任何申请人不得审批本人发起的联锁复位、联锁覆盖或权限提升 |
| 审计隔离 | `AUDITOR` 不得发起任何业务命令；`SYS_ADMIN` 不得修改/删除审计日志 |
| 联锁优先级 | 存在状态为 `LOCKED` / `WAITING_RECEIPT` 的联锁对象时，相关工单的派工与执行命令一律拒绝，返回 `TOS-IL-001` |
| 敏感字段脱敏 | `REGULATOR` 与 `BUSINESS` 访问明细数据时，车号、人员标识等敏感字段按掩码规则输出 |

### 3.6 幂等、乐观锁与版本控制

**幂等键构成**：`{对象 ID} + {动作} + {expectedVersion}`。

| 场景 | 服务端行为 |
|------|------------|
| 首次提交 | 正常执行，返回 `ok: true`，`idempotent: false` |
| 重复提交（相同幂等键，对象版本已推进） | 不重复执行业务，直接返回上次结果，`idempotent: true` |
| 版本已变化但幂等键不同 | 返回 `409 DEMO-VERSION-001`，`details` 中给出 `expectedVersion` 与 `actualVersion` |

**版本推进规则**：

- 任一字段变更即 `version = version + 1`；
- `DO-015` 的 `edit` 命令在草稿阶段为自迁移（`DRAFT → DRAFT`），仍推进版本；
- 审计日志（`DO-013`）与报表（`DO-012`）为追加型对象，`version` 仅用于展示，不参与并发控制。

**命令流水线**（所有写接口共用）：

```
executeCommand(command) {
  1. authorize(command)      → CommandPermissionDecision
  2. validate(command)       → Zod strict parse
  3. invokeMock(command)     → 调用底层 Mock 接口
  4. transition(command)     → transitionState 校验状态迁移
  5. commit(command, resp)   → 落库 + 版本自增
  6. appendAudit(command)    → 生成审计记录，返回 auditLogId
}
```

命令对象结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| `commandId` | string | 命令唯一标识 |
| `action` | string | 动作名，与状态机命令对齐 |
| `entityType` | string | 领域对象编号（`DO-xxx`）或 `SM-007` |
| `entityId` | string | 目标对象 ID |
| `expectedVersion` | integer | 期望版本 |
| `payload` | object | 命令载荷 |
| `actor` | object | `{ actorId, roleCode, dataScope, online }` |
| `traceId` | string | 链路追踪标识 |
| `clientTime` | string | 客户端时间 |

### 3.7 分页、排序与过滤

列表类接口（`API-002`、`API-010`、`API-014`、`API-016`、`API-018`、`API-024`）响应中 `data.type` 形如 `Page<Plan>`。

**分页约定**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `page` | integer | 1 | 页码，从 1 开始 |
| `pageSize` | integer | 20 | 每页条数，最大 100 |

**排序约定**：`sort=<field>:<asc|desc>`，默认 `updatedAt:desc`。

**过滤维度**（各接口按资源类型裁剪）：

| 资源 | 支持的过滤维度 |
|------|----------------|
| 计划 Plan | `date`、`workArea`、`planBatchNo`、`trainNo`、`status[]`、`exceptionType[]` |
| 预约 Appointment | `role`、`status`、`gateStatus`、`vehicleNo` |
| 异常 Exception | `workOrderId`、`planId`、`status`、`level`、`type`、`owner`、`from` |
| 联锁 Interlock | `exceptionId`、`status`、`actionLevel`、`riskType`、`receiptStatus`、`from` |
| 离线包 OfflinePacket | `packetId`、`terminalId`、`workOrderNo`、`mergeStatus`、`from` |
| 报表 Report | `type`、`period`、`dimensions[]` |

> 说明：契约基线中仅 `API-001`（`date`/`workArea`）、`API-005`（`inputVersion`）、`API-010`（`role`）、`API-012`（`time`）、`API-020`（`type`/`period`/`dimensions`）、`API-022`（`domain`）携带显式查询参数。其余过滤维度由前端在数据域过滤后于本地完成；生产化时建议以查询参数形式下沉至服务端，并同步更新契约基线。

### 3.8 时间与编码规范

| 项 | 规范 |
|----|------|
| 时间格式 | ISO 8601 带时区偏移，如 `2026-07-16T09:00:00+08:00` |
| 时区 | `Asia/Shanghai`（固定，不随客户端变化） |
| 时间区间 | 使用 `/` 连接起止，如 `2026-07-16T08:01:00+08:00/2026-07-16T10:01:00+08:00` |
| 空值表达 | 字符串字段空值统一为 `""`；可选字段缺省而非 `null` |
| 主键格式 | 领域对象使用语义化前缀 ID，如 `PLAN-001`、`WO-001`、`EX-001`、`IL-001`、`OFF-001` |
| 编号格式 | 业务编号使用带日期段的格式，如 `PB-20260716-01`、`RES-20260716-01`、`EX-20260716-01` |
| 字符集 | 请求与响应统一 UTF-8 |
| 内容类型 | `application/json` |

### 3.9 时延与异常注入

契约中每个接口标注：

- `x-latency`：正常 300ms；异常场景可配置 500 ~ 1500ms。
- `x-idempotency`：命令使用对象 ID、动作和 `expectedVersion` 组成幂等键。

**演示场景注入策略**：

| 场景 | 名称 | 故障类型 | 注入错误码 | 注入时延 | 种子对象 |
|------|------|----------|------------|----------|----------|
| SCN-01 | 正常整列 | `NONE` | — | 300ms | `PLAN-001` |
| SCN-02 | 字段缺失 | `VALIDATION_MISSING_FIELD` | `TOS-EXT-002` | 300ms | `PLAN-002` |
| SCN-03 | 接口超时 | `INTERFACE_TIMEOUT` | `TOS-EXT-001` | 1500ms | `PLAN-003` |
| SCN-04 | 设备离线 | `DEVICE_OFFLINE` | `TOS-WO-001` | 800ms | `WO-005` |
| SCN-05 | 人员侵入 | `INTERLOCK_FORCE_STOP` | `TOS-IL-001` | 500ms | `WO-006`、`IL-001` |
| SCN-06 | 离线冲突 | `OFFLINE_VERSION_CONFLICT` | `TOS-OFF-001` | 700ms | `OFF-001` |
| SCN-07 | 公路放行异常 | `SOURCE_DATA_CONFLICT` | `TOS-EXT-003` | 600ms | `APT-006` |

**故障注入实现**：场景通过 `fault.mutations` 对种子对象执行字段级变形——`omitFields` 删除指定字段、`replaceValues` 覆盖指定字段值。客户端通过 `API-025` 重置场景后，故障态与已解决字段记录一并清除。

**降级与熔断**：连续失败累计至阈值后，接口健康状态置为 `CIRCUIT_OPEN`，前端展示熔断提示并提供"重试"入口；恢复成功后状态回退为 `HEALTHY`。

---

## 4 接口清单

### 4.1 总览

| API 编号 | 方法 | 路径 | operationId | 归属页面 | 响应类型 |
|----------|------|------|-------------|----------|----------|
| API-001 | GET | `/mock/overview` | `GET_mock_overview` | UI-001 | `OverviewSnapshot` |
| API-002 | GET | `/mock/plans` | `GET_mock_plans` | UI-002 | `Page<Plan>` |
| API-003 | POST | `/mock/plans/sync` | `POST_mock_plans_sync` | UI-002 | `SyncBatch` |
| API-004 | POST | `/mock/plans/{id}/confirm` | `POST_mock_plans_id_confirm` | UI-002 | `Plan` |
| API-005 | GET | `/mock/plans/{id}/recommendation` | `GET_mock_plans_id_recommendation` | UI-003 | `Recommendation` |
| API-006 | POST | `/mock/plans/{id}/recommendation/confirm` | `POST_mock_plans_id_recommendation_confirm` | UI-003 | `ReceptionPlan` |
| API-007 | POST | `/mock/plans/{id}/decompose` | `POST_mock_plans_id_decompose` | UI-004 | `TaskTree` |
| API-008 | POST | `/mock/work-orders/{id}/assign` | `POST_mock_work_orders_id_assign` | UI-005 | `WorkOrder` |
| API-009 | POST | `/mock/work-orders/{id}/dispatch` | `POST_mock_work_orders_id_dispatch` | UI-005 | `DispatchReceipt` |
| API-010 | GET | `/mock/appointments` | `GET_mock_appointments` | UI-006 | `Page<Appointment>` |
| API-011 | POST | `/mock/appointments/{id}/transition` | `POST_mock_appointments_id_transition` | UI-006 | `Appointment` |
| API-012 | GET | `/mock/operations` | `GET_mock_operations` | UI-007 | `OperationSnapshot` |
| API-013 | POST | `/mock/scenarios/{id}/play` | `POST_mock_scenarios_id_play` | UI-001/007/009 | `ScenarioState` |
| API-014 | GET | `/mock/exceptions` | `GET_mock_exceptions` | UI-008 | `Page<Exception>` |
| API-015 | POST | `/mock/exceptions/{id}/command` | `POST_mock_exceptions_id_command` | UI-008 | `Exception` |
| API-016 | GET | `/mock/interlocks` | `GET_mock_interlocks` | UI-009 | `Page<Interlock>` |
| API-017 | POST | `/mock/interlocks/{id}/command` | `POST_mock_interlocks_id_command` | UI-009 | `Interlock` |
| API-018 | GET | `/mock/offline-packets` | `GET_mock_offline_packets` | UI-010 | `Page<OfflinePacket>` |
| API-019 | POST | `/mock/offline-packets/{id}/command` | `POST_mock_offline_packets_id_command` | UI-010 | `OfflinePacket` |
| API-020 | GET | `/mock/reports` | `GET_mock_reports` | UI-011 | `ReportSnapshot` |
| API-021 | POST | `/mock/reports/export` | `POST_mock_reports_export` | UI-011 | `ExportTask` |
| API-022 | GET | `/mock/config` | `GET_mock_config` | UI-012 | 精确信封（`items:[CFG-001]`） |
| API-023 | POST | `/mock/config/{id}/command` | `POST_mock_config_id_command` | UI-012 | 精确信封（`items` 长度 1） |
| API-024 | GET | `/mock/audit-logs` | `GET_mock_audit_logs` | UI-013 | `Page<AuditLog>` |
| API-025 | POST | `/mock/demo/reset` | `POST_mock_demo_reset` | 全部 | `DemoState` |

### 4.2 按能力域分组

| 分组 | 接口 | 读/写 |
|------|------|-------|
| 计划接收 | API-002, API-003, API-004, API-025 | 1 读 + 3 写 |
| 接车推荐 | API-005, API-006 | 1 读 + 1 写 |
| 任务拆解 | API-007 | 写 |
| 派工执行 | API-008, API-009 | 2 写 |
| 公路预约 | API-010, API-011 | 1 读 + 1 写 |
| 运行监控 | API-012, API-013 | 1 读 + 1 写 |
| 异常处置 | API-014, API-015 | 1 读 + 1 写 |
| 安全联锁 | API-016, API-017 | 1 读 + 1 写 |
| 离线同步 | API-018, API-019 | 1 读 + 1 写 |
| 统计报表 | API-020, API-021 | 1 读 + 1 写 |
| 系统配置 | API-022, API-023 | 1 读 + 1 写 |
| 审计追溯 | API-024 | 读 |
| 总览 | API-001 | 读 |

**统计**：读接口（`GET`）11 个，写接口（`POST`）14 个，合计 25 个。

---

## 5 接口详细设计

> 通用约定：所有接口的响应统一符合第 3.3 节信封结构；所有写接口默认携带 `expectedVersion` 语义；下文"错误"一栏仅列出该接口**特有**的业务错误，凡涉及权限、版本、结构的通用错误（`TOS-AUTH-001`、`DEMO-VERSION-001`、`DEMO-SCENARIO-001`）不再逐条重复。

---

### API-001 调度总览快照

| 项 | 内容 |
|----|------|
| API 编号 | API-001 |
| operationId | `GET_mock_overview` |
| 方法 / 路径 | `GET /mock/overview` |
| 归属页面 | UI-001 调度总览 |
| 权限 | `overview:view`（角色：`DISPATCHER` / `SHIFT_LEADER` / `BUSINESS`） |
| 幂等 | 读接口，天然幂等 |
| 时延 | 300ms（异常场景 500 ~ 1500ms） |
| 响应类型 | `OverviewSnapshot` |

**查询参数**

| 名称 | 位置 | 必填 | 类型 | 示例 | 说明 |
|------|------|------|------|------|------|
| `date` | query | 否 | string | `2026-07-16T09:30:00+08:00` | 快照基准时间 |
| `workArea` | query | 否 | string | `AREA-A` | 作业区过滤 |

**成功响应示例**

```json
{
  "ok": true,
  "data": { "type": "OverviewSnapshot", "id": "API-001-RESULT", "version": 1 },
  "auditLogId": "AUD-001",
  "traceId": "TRACE-001"
}
```

**业务说明**

返回总览 KPI（计划总数、待确认、已确认、受阻、已完成工单、待入场车辆、可用资源率、未闭环异常）与场区可视对象集合。数据仅包含会话数据域内的对象；`BUSINESS` 角色下敏感字段脱敏。

**请求示例**

```bash
curl -s "http://localhost:5173/mock/overview?date=2026-07-16T09:30:00%2B08:00&workArea=AREA-A"
```

---

### API-002 计划列表查询

| 项 | 内容 |
|----|------|
| API 编号 | API-002 |
| operationId | `GET_mock_plans` |
| 方法 / 路径 | `GET /mock/plans` |
| 归属页面 | UI-002 计划接收台账 |
| 权限 | `plan:view`（角色：`DISPATCHER` / `INTERFACE_OPS`） |
| 响应类型 | `Page<Plan>`（元素为 `DO-001`） |

**查询参数**：契约基线未声明显式参数；生产化建议支持 `date`、`workArea`、`planBatchNo`、`trainNo`、`status[]`、`page`、`pageSize`、`sort`。

**成功响应示例**

```json
{
  "ok": true,
  "data": { "type": "Page<Plan>", "id": "API-002-RESULT", "version": 1 },
  "auditLogId": "AUD-002",
  "traceId": "TRACE-002"
}
```

**业务说明**

返回 `DO-001 Plan` 分页列表。每条记录包含 `missingFields`（缺失字段清单）与 `conflicts`（多源冲突明细），供台账展示校验状态（`VALID` / `MISSING_FIELD` / `CONFLICT`）与同步状态（`SYNCED` / `DEGRADED` / `CIRCUIT_OPEN`）。

---

### API-003 计划批量同步

| 项 | 内容 |
|----|------|
| API 编号 | API-003 |
| operationId | `POST_mock_plans_sync` |
| 方法 / 路径 | `POST /mock/plans/sync` |
| 归属页面 | UI-002 |
| 权限 | `plan:view` + `interface:retry`（角色：`DISPATCHER` / `INTERFACE_OPS`） |
| 幂等键 | `scenarioId + sync + expectedVersion` |
| 响应类型 | `SyncBatch` |
| 状态迁移 | `DO-001`：`RECEIVED --sync--> VALIDATING` |

**请求体 `API003Request`**（`additionalProperties: false`）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `scenarioId` | string | 是 | 触发同步的场景标识，如 `SCN-01` |

```json
{ "scenarioId": "SCN-01" }
```

**成功响应示例**

```json
{
  "ok": true,
  "data": { "type": "SyncBatch", "id": "API-003-RESULT", "version": 1 },
  "auditLogId": "AUD-003",
  "traceId": "TRACE-003"
}
```

**特有错误**

| 错误码 | HTTP | 触发条件 |
|--------|------|----------|
| `TOS-EXT-001` | 400 | 外部铁路计划接口超时或不可用（SCN-03） |

**业务说明**

拉取外部权威计划数据并写入 `DO-001`。同步成功后计划状态由 `RECEIVED` 迁移至 `VALIDATING`；若外部接口不可用，记录同步失败并累计重试次数，连续失败达阈值后触发熔断。

---

### API-004 计划确认

| 项 | 内容 |
|----|------|
| API 编号 | API-004 |
| operationId | `POST_mock_plans_id_confirm` |
| 方法 / 路径 | `POST /mock/plans/{id}/confirm` |
| 归属页面 | UI-002 |
| 权限 | `plan:confirm`（角色：`DISPATCHER` / `INTERFACE_OPS`） |
| 响应类型 | `Plan` |
| 状态迁移 | `PENDING_CONFIRM --confirm--> CONFIRMED`；`ADJUSTED --confirm--> CONFIRMED` |

**路径参数**

| 名称 | 类型 | 必填 | 示例 |
|------|------|------|------|
| `id` | string | 是 | `PLAN-001` |

**查询参数**：无

**请求体 `API004Request`**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `reason` | string | 否 | 确认说明 |
| `supplements` | object | 否 | 人工补充字段键值对（用于修复 `missingFields`） |

```json
{ "reason": "字段已人工核对补充", "supplements": { "trackNo": "T1" } }
```

**特有错误**

| 错误码 | HTTP | 触发条件 |
|--------|------|----------|
| `TOS-EXT-002` | 400 | 权威输入字段缺失，阻断确认（SCN-02） |
| `TOS-EXT-003` | 409 | 多源数据冲突，需人工裁决 |

**业务说明**

确认操作前必须保证 `missingFields` 为空且 `conflicts` 已裁决。`supplements` 中提交的字段会被写入计划并清除对应的缺失标记。确认成功后计划版本自增，并记录变更前后快照用于留痕。

---

### API-005 接车推荐结果查询

| 项 | 内容 |
|----|------|
| API 编号 | API-005 |
| operationId | `GET_mock_plans_id_recommendation` |
| 方法 / 路径 | `GET /mock/plans/{id}/recommendation` |
| 归属页面 | UI-003 接车计划推荐 |
| 权限 | `plan:recommend`（角色：`DISPATCHER`） |
| 响应类型 | `Recommendation` |

**路径参数**

| 名称 | 类型 | 必填 | 示例 |
|------|------|------|------|
| `id` | string | 是 | `PLAN-001` |

**查询参数**

| 名称 | 位置 | 必填 | 类型 | 示例 | 说明 |
|------|------|------|------|------|------|
| `inputVersion` | query | 否 | integer | `1` | 计算所依据的计划版本，用于判定结果是否过期 |

**业务说明**

返回推荐草稿 `RecommendationDraft`，结构如下：

| 字段 | 类型 | 说明 |
|------|------|------|
| `planId` | string | 关联计划 ID |
| `draftVersion` | integer | 推荐草稿版本 |
| `status` | enum | `CALCULATED` / `CONFIRMED` |
| `inputPlanVersion` | integer | 计算输入的计划版本 |
| `ruleVersion` | string | 规则版本，固定 `C05-DEMO-RULE-1.0` |
| `generatedAt` | string | 生成时间 |
| `candidates` | array | 候选股道及评分 |
| `excluded` | array | 被排除股道及排除原因 |
| `selectedCandidateId` | string | 选定候选（确认后必填） |
| `adjustment` | object | 人工调整记录 |
| `confirmation` | object | 确认元数据 |

**候选评分（`RecommendationCandidate`）**

| 字段 | 类型 | 取值范围 | 说明 |
|------|------|----------|------|
| `candidateId` | string | — | 候选标识 |
| `trackId` / `trackNo` | string | — | 股道 ID / 股道号 |
| `trackVersion` | integer | ≥1 | 股道对象版本 |
| `occupyStatus` | enum | `FREE` / `OCCUPIED` / `RELEASING` | 占用状态 |
| `windowStart` / `windowEnd` | string | — | 推荐作业窗口 |
| `score` | integer | 0 ~ 100 | 总分，必须等于四项分项之和 |
| `rank` | integer | ≥1 | 排名 |
| `recommended` | boolean | — | 仅当 `rank = 1` 时为 `true` |
| `scoreBreakdown` | object | — | 分项：`availability`(0~45)、`timing`(0~30)、`continuity`(0~15)、`authority`(0~10) |
| `reasons` | string[] | 非空 | 推荐理由 |
| `sourceRefs` | string[] | 非空 | 数据来源引用 |

**排除原因 `exclusionCode`**：`TRACK_BLOCKED`（股道封锁）、`CARGO_INCOMPATIBLE`（货类不兼容）、`UNSUPPORTED_OCCUPANCY`（占用状态不支持）、`INVALID_RELEASE_TIME`（释放时间无效）。

**一致性约束**：`score` = `availability` + `timing` + `continuity` + `authority`；`recommended` 与 `rank === 1` 必须等价。

---

### API-006 接车推荐确认

| 项 | 内容 |
|----|------|
| API 编号 | API-006 |
| operationId | `POST_mock_plans_id_recommendation_confirm` |
| 方法 / 路径 | `POST /mock/plans/{id}/recommendation/confirm` |
| 归属页面 | UI-003 |
| 权限 | `plan:recommend` + `plan:confirm`（角色：`DISPATCHER`） |
| 响应类型 | `ReceptionPlan` |
| 状态迁移 | `DO-001`：`CONFIRMED --adjust--> ADJUSTED`（若发生调整） |

**请求体 `API006Request`**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `trackNo` | string | 是 | 选定股道号 |
| `window` | string | 是 | 选定作业窗口 |
| `reason` | string | 否 | 调整原因（人工改选时必填） |

```json
{ "trackNo": "T1", "window": "2026-07-16T08:30:00+08:00/2026-07-16T10:30:00+08:00", "reason": "现场条件调整" }
```

**特有错误**

| 错误码 | HTTP | 触发条件 |
|--------|------|----------|
| `DEMO-VERSION-001` | 409 | `inputVersion` 对应的计划版本已过期，推荐结果需重算 |

**业务说明**

将推荐草稿置为 `CONFIRMED`，写入 `selectedCandidateId` 与 `confirmation`（含 `actorId`、`roleCode`、`confirmedAt`、`commandId`、`traceId`）。若最终选定候选与排名第一的候选不一致，必须同时写入 `adjustment` 记录，且 `adjustment.finalCandidateId` 必须等于 `selectedCandidateId`。

---

### API-007 任务自动拆解

| 项 | 内容 |
|----|------|
| API 编号 | API-007 |
| operationId | `POST_mock_plans_id_decompose` |
| 方法 / 路径 | `POST /mock/plans/{id}/decompose` |
| 归属页面 | UI-004 任务拆解 |
| 权限 | `task:decompose`（角色：`DISPATCHER` / `SHIFT_LEADER`） |
| 响应类型 | `TaskTree` |
| 状态迁移 | `DO-001`：`CONFIRMED --decompose--> DECOMPOSED`；`ADJUSTED --decompose--> DECOMPOSED` |

**请求体 `API007Request`**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `ruleVersion` | string | 是 | 拆解规则版本，如 `RULE-1.0` |
| `mode` | string | 是 | 拆解模式标识 |

```json
{ "ruleVersion": "RULE-1.0", "mode": "AUTO" }
```

**业务说明**

依据货类识别结果与作业路由规则，将计划拆解为工单树：

- 生成 `DO-005 WorkOrder`（类型为 `UNLOAD` / `TRANSFER` / `LOAD` / `INSPECT`），初始状态 `DRAFT`；
- 生成 `DO-006 WorkNode` 作业节点，初始状态 `WAITING`；
- 建立工单依赖关系（`dependencyIds`、`upstreamWorkOrderId`、`downstreamWorkOrderIds`）与阶段归属（`RECOGNITION` / `UNLOAD` / `TRANSFER` / `STORAGE`）；
- 计划状态迁移至 `DECOMPOSED`，此后计划不可再修改。

**任务树结构（`TaskTree`）**

| 字段 | 类型 | 说明 |
|------|------|------|
| `planId` | string | 计划 ID |
| `ruleVersion` | string | 使用的规则版本 |
| `orders` | array | 工单视图集合 |
| `kpis` | object | 各进度态数量（`ready` / `assigned` / `dispatched` / `executing` / `completed` / `exceptionEntry`） |

---

### API-008 工单资源指派

| 项 | 内容 |
|----|------|
| API 编号 | API-008 |
| operationId | `POST_mock_work_orders_id_assign` |
| 方法 / 路径 | `POST /mock/work-orders/{id}/assign` |
| 归属页面 | UI-005 派工看板 |
| 权限 | `dispatch:assign`（角色：`DISPATCHER` / `SHIFT_LEADER`） |
| 响应类型 | `WorkOrder` |
| 状态迁移 | `DO-005`：`DRAFT --assign--> READY` |

**路径参数**：`id`（string，必填，示例 `WO-001`）

**请求体 `API008Request`**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `resourceId` | string | 是 | 指派资源 ID，须为 `DO-007 Resource` |
| `reason` | string | 否 | 指派说明 |

```json
{ "resourceId": "RESOURCE-001", "reason": "按班次计划分配" }
```

**响应数据 `DispatchBoardGatewayData`**

```json
{
  "apiId": "API-008",
  "operationId": "POST_mock_work_orders_id_assign",
  "now": "2026-07-16T09:00:00+08:00",
  "scenarioId": "SCN-01",
  "items": [{ "id": "WO-001" }]
}
```

其中 `items` 长度恒为 1，且 `items[0].id` 必须等于路径参数 `id`。

**特有错误**

| 错误码 | HTTP | 触发条件 |
|--------|------|----------|
| `TOS-WO-001` | 409 | 目标资源不可用（`OFFLINE` / `MAINTENANCE` / `LOCKED`）或设备离线（SCN-04） |
| `TOS-IL-001` | 409 | 关联对象处于安全联锁锁定态 |

**业务说明**

资源可指派性判定维度：`status === AVAILABLE`、`capabilityTags` 覆盖工单所需的 `requiredResourceType`、`workArea` 在会话数据域内。不可指派时须回带具体不可用原因（`unavailableReason`），供看板提示替换资源。

---

### API-009 工单派发下发

| 项 | 内容 |
|----|------|
| API 编号 | API-009 |
| operationId | `POST_mock_work_orders_id_dispatch` |
| 方法 / 路径 | `POST /mock/work-orders/{id}/dispatch` |
| 归属页面 | UI-005 |
| 权限 | `dispatch:send`（角色：`DISPATCHER` / `SHIFT_LEADER`） |
| 响应类型 | `DispatchReceipt` |
| 状态迁移 | `DO-005`：`READY --dispatch--> DISPATCHED`；`DISPATCHED --ack--> ACKNOWLEDGED` |

**请求体 `API009Request`**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `target` | string | 是 | 下发目标终端标识 |
| `simulateReceipt` | boolean | 是 | 是否模拟接收回执（为 `true` 时直接推进 `ackStatus`） |

```json
{ "target": "PDA-01", "simulateReceipt": true }
```

**业务说明**

下发成功后工单进入 `DISPATCHED`，`ackStatus` 置为 `PENDING`。当 `simulateReceipt = true` 时，服务端模拟终端回执，工单迁移至 `ACKNOWLEDGED` 且 `ackStatus = ACKNOWLEDGED`；回执超时则 `ackStatus = TIMEOUT`，拒绝回执则 `REJECTED`。

**接收状态 `ackStatus` 流转**：`PENDING → ACKNOWLEDGED | REJECTED | TIMEOUT`

---

### API-010 公路预约列表查询

| 项 | 内容 |
|----|------|
| API 编号 | API-010 |
| operationId | `GET_mock_appointments` |
| 方法 / 路径 | `GET /mock/appointments` |
| 归属页面 | UI-006 公路预约与叫号 |
| 权限 | `yard:submit`（角色：`DRIVER` / `GATE_GUARD` / `DISPATCHER`） |
| 响应类型 | `Page<Appointment>` |

**查询参数**

| 名称 | 位置 | 必填 | 类型 | 示例 | 说明 |
|------|------|------|------|------|------|
| `role` | query | 否 | string | `GATE_GUARD` | 按角色视角过滤可见预约 |

**业务说明**

`DRIVER` 角色仅可见 `driverId` 等于本人 `actorId` 的预约（`SELF` 数据域）；`GATE_GUARD` 仅可见所属门岗与当班车辆。

---

### API-011 预约状态流转

| 项 | 内容 |
|----|------|
| API 编号 | API-011 |
| operationId | `POST_mock_appointments_id_transition` |
| 方法 / 路径 | `POST /mock/appointments/{id}/transition` |
| 归属页面 | UI-006 |
| 权限 | 按动作映射：`yard:submit` / `yard:review` / `yard:call` / `yard:gate` / `yard:release` |
| 响应类型 | `Appointment` |
| 状态迁移 | 见下表 |

**请求体 `API011Request`**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `action` | string | 是 | 状态机命令，取值 `submit` / `approve` / `call` / `gateIn` / `release` / `gateOut` |
| `evidence` | object | 是 | 凭证信息（证件核验结果、放行依据等） |

```json
{ "action": "call", "evidence": { "queueNo": "Q01" } }
```

**合法迁移（`DO-008`）**

| 当前状态 | 命令 | 目标状态 | 权限码 |
|----------|------|----------|--------|
| `DRAFT` | `submit` | `SUBMITTED` | `yard:submit` |
| `NEED_FIX` | `submit` | `SUBMITTED` | `yard:submit` |
| `SUBMITTED` | `approve` | `APPROVED` | `yard:review` |
| `APPROVED` | `call` | `CALLED` | `yard:call` |
| `QUEUED` | `call` | `CALLED` | `yard:call` |
| `CALLED` | `gateIn` | `ENTERED` | `yard:gate` |
| `OPERATING` | `release` | `RELEASED` | `yard:release` |
| `RELEASED` | `gateOut` | `EXITED` | `yard:release` |

**特有错误**

| 错误码 | HTTP | 触发条件 |
|--------|------|----------|
| `TOS-EXT-003` | 409 | 车辆资质或放行源数据冲突（SCN-07） |

---

### API-012 运行态势快照

| 项 | 内容 |
|----|------|
| API 编号 | API-012 |
| operationId | `GET_mock_operations` |
| 方法 / 路径 | `GET /mock/operations` |
| 归属页面 | UI-007 全流程监控 |
| 权限 | `monitor:view`（角色：`DISPATCHER` / `SHIFT_LEADER` / `BUSINESS`） |
| 响应类型 | `OperationSnapshot` |

**查询参数**

| 名称 | 位置 | 必填 | 类型 | 示例 | 说明 |
|------|------|------|------|------|------|
| `time` | query | 否 | string | `2026-07-16T09:30:00+08:00` | 快照时点 |

**业务说明**

返回人机货车态势数据，包括：工单执行进度、作业节点状态流、资源实时状态、超时/卡顿标记、AGV 与智能设备连接状态。设备断联时以"降级"标记输出，不影响其他对象展示。

---

### API-013 演示场景播放

| 项 | 内容 |
|----|------|
| API 编号 | API-013 |
| operationId | `POST_mock_scenarios_id_play` |
| 方法 / 路径 | `POST /mock/scenarios/{id}/play` |
| 归属页面 | UI-001 / UI-007 / UI-009 |
| 权限 | `overview:view` / `monitor:view` / `interlock:view`（按调用页面） |
| 响应类型 | `ScenarioState` |

**路径参数**：`id`（string，必填，取值 `SCN-01` ~ `SCN-07`）

**请求体 `API013Request`**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `speed` | number | 是 | 播放倍速，示例 `1` |

```json
{ "speed": 1 }
```

**特有错误**

| 错误码 | HTTP | 触发条件 |
|--------|------|----------|
| `DEMO-SCENARIO-001` | 400 | 场景标识不存在，或当前状态不允许播放 |

**业务说明**

触发指定演示场景的运行。场景会按 `events` 序列推进对象状态，并按 `fault` 定义注入故障变形（字段缺失/替换）与响应时延。

---

### API-014 异常列表查询

| 项 | 内容 |
|----|------|
| API 编号 | API-014 |
| operationId | `GET_mock_exceptions` |
| 方法 / 路径 | `GET /mock/exceptions` |
| 归属页面 | UI-008 异常处置 |
| 权限 | `monitor:view`（角色：`DISPATCHER` / `SHIFT_LEADER` / `BUSINESS`） |
| 响应类型 | `Page<Exception>` |

**业务说明**

返回 `DO-009 DispatchException` 分页列表，同时给出 KPI 计数（`unacknowledged` / `handling` / `pendingReview` / `closed` / `overdue` / `interlock`）。

**异常等级 `level` 与处置时限**：

| 等级 | 说明 | 默认时限策略 |
|------|------|--------------|
| `INFO` | 提示级 | 当日闭环 |
| `MINOR` | 轻微 | 4 小时内闭环 |
| `MAJOR` | 严重 | 2 小时内闭环 |
| `CRITICAL` | 紧急 | 立即响应，需双人复核 |

**超期判定**：当前时间 > `dueAt` 且状态不在 `CLOSED` 时，`dueState` 置为 `OVERDUE`。

---

### API-015 异常处置命令

| 项 | 内容 |
|----|------|
| API 编号 | API-015 |
| operationId | `POST_mock_exceptions_id_command` |
| 方法 / 路径 | `POST /mock/exceptions/{id}/command` |
| 归属页面 | UI-008 |
| 权限 | 按命令映射：`exception:ack` / `exception:handle` / `exception:review` / `exception:close` / `exception:reopen` |
| 响应类型 | `Exception` |

**请求体 `API015Request`**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `command` | string | 是 | `ack` / `assign` / `handle` / `review` / `close` / `reopen` |
| `reason` | string | 否 | 处置说明 |
| `evidence` | object | 是 | 证据信息；`assign` 场景下须含 `owner` |

```json
{ "command": "handle", "reason": "已更换传感器", "evidence": { "attachments": ["EVIDENCE-001"] } }
```

**合法迁移（`DO-009`）**

| 当前状态 | 命令 | 目标状态 |
|----------|------|----------|
| `OPEN` | `ack` | `ACKNOWLEDGED` |
| `REOPENED` | `ack` | `ACKNOWLEDGED` |
| `ACKNOWLEDGED` | `assign` | `HANDLING` |
| `HANDLING` | `handle` | `PENDING_REVIEW` |
| `PENDING_REVIEW` | `review` | `HANDLING` |
| `PENDING_REVIEW` | `close` | `CLOSED` |
| `CLOSED` | `reopen` | `REOPENED` |

**证据要求**

| 状态 | 最少证据条目 |
|------|--------------|
| `PENDING_REVIEW` | ≥ 1 |
| `CLOSED` | ≥ 1，且 `MAJOR` / `CRITICAL` 等级需复核人签字 |

**职责冲突**：处置人与复核人不得为同一 `actorId`。

---

### API-016 联锁列表查询

| 项 | 内容 |
|----|------|
| API 编号 | API-016 |
| operationId | `GET_mock_interlocks` |
| 方法 / 路径 | `GET /mock/interlocks` |
| 归属页面 | UI-009 安全联锁 |
| 权限 | `interlock:view`（角色：`SAFETY` / `MAINTAINER` / `DISPATCHER`） |
| 响应类型 | `Page<Interlock>` |

**业务说明**

返回 `DO-010 Interlock` 分页列表与 KPI（`locked` / `pendingApproval` / `approved` / `restored` / `forceStop` / `receiptFailed`）。

**动作级别 `actionLevel`**：`WARN`（告警）、`PAUSE`（暂停）、`FORCE_STOP`（强制停机）。`FORCE_STOP` 时前端必须展示强提示并禁止继续执行相关作业。

**回执状态 `receiptStatus`**：`PENDING` / `RECEIVED` / `FAILED`。回执失败须在 KPI 中单独计数并进入待处理队列。

---

### API-017 联锁处置命令

| 项 | 内容 |
|----|------|
| API 编号 | API-017 |
| operationId | `POST_mock_interlocks_id_command` |
| 方法 / 路径 | `POST /mock/interlocks/{id}/command` |
| 归属页面 | UI-009 |
| 权限 | 按命令映射：`interlock:reset` / `interlock:approve` / `interlock:request-override` |
| 响应类型 | `Interlock` |

**请求体 `API017Request`**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `command` | string | 是 | `trigger` / `receipt` / `requestReset` / `approve` / `restore` / `requestOverride` |
| `receipt` | object | 是 | 控制回执信息 |
| `approval` | object | 是 | 审批信息，须含 `approverId` 与 `roleCode` |

```json
{
  "command": "approve",
  "receipt": {},
  "approval": { "approverId": "USER-008", "roleCode": "SAFETY" }
}
```

**合法迁移（`DO-010`）**

| 当前状态 | 命令 | 目标状态 |
|----------|------|----------|
| `TRIGGERED` | `trigger` | `ACTION_ISSUED` |
| `ACTION_ISSUED` | `receipt` | `WAITING_RECEIPT` |
| `WAITING_RECEIPT` | `receipt` | `LOCKED` |
| `LOCKED` | `requestReset` | `RESET_REQUESTED` |
| `RESET_REQUESTED` | `approve` | `APPROVED` |
| `APPROVED` | `restore` | `RESTORED` |
| `LOCKED` | `requestOverride` | `OVERRIDE_PENDING` |
| `OVERRIDE_PENDING` | `approve` | `OVERRIDDEN` |

**特有错误**

| 错误码 | HTTP | 触发条件 |
|--------|------|----------|
| `TOS-IL-001` | 409 | 联锁处于锁定态，禁止继续执行（SCN-05） |

**强制约束**

1. `approval.approverId` 不得等于该联锁复位/覆盖请求的申请人；
2. `approve` 命令要求 `approval.roleCode` 属于 `SAFETY` / `MAINTAINER` / `DISPATCHER` 之一；
3. `OVERRIDDEN` 为高风险终态，必须写入完整审批链 `approvalChain` 并生成 `CRITICAL` 级审计记录。

---

### API-018 离线包列表查询

| 项 | 内容 |
|----|------|
| API 编号 | API-018 |
| operationId | `GET_mock_offline_packets` |
| 方法 / 路径 | `GET /mock/offline-packets` |
| 归属页面 | UI-010 离线同步 |
| 权限 | `offline:view`（角色：`INTERFACE_OPS` / `SHIFT_LEADER` / `DISPATCHER`） |
| 响应类型 | `Page<OfflinePacket>` |

**业务说明**

返回 `DO-011 OfflinePacket` 分页列表与 KPI（`cached` / `pendingUpload` / `validating` / `merged` / `conflict` / `rejected` / `retry`）。

**版本差 `versionDelta`**：`packageVersion - serverVersion`。`versionDelta > 0` 表示终端版本更新；`< 0` 表示服务端已被他人推进，进入冲突流程。

**弱网限制**：当检测到网络质量低于阈值时，仅允许 `CACHED` / `RETRY` 状态的操作，并在 UI 中给出弱网提示。

---

### API-019 离线包处置命令

| 项 | 内容 |
|----|------|
| API 编号 | API-019 |
| operationId | `POST_mock_offline_packets_id_command` |
| 方法 / 路径 | `POST /mock/offline-packets/{id}/command` |
| 归属页面 | UI-010 |
| 权限 | 按命令映射：`offline:resolve` / `offline:retry` |
| 响应类型 | `OfflinePacket` |

**请求体 `API019Request`**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `upload` | string | 是 | 上传命令标识 |
| `merge` | string | 是 | 合并命令标识 |
| `reject` | string | 是 | 拒绝命令标识 |
| `retry` | string | 是 | 重试命令标识 |

```json
{ "upload": "upload", "merge": "merge", "reject": "reject", "retry": "retry" }
```

**合法迁移（`DO-011`）**

| 当前状态 | 命令 | 目标状态 |
|----------|------|----------|
| `CACHED` | `upload` | `PENDING_UPLOAD` |
| `RETRY` | `upload` | `PENDING_UPLOAD` |
| `PENDING_UPLOAD` | `validate` | `VALIDATING` |
| `VALIDATING` | `merge` | `MERGED` |
| `VALIDATING` | `reject` | `REJECTED` |
| `CONFLICT` | `reject` | `REJECTED` |
| `CONFLICT` | `retry` | `RETRY` |
| `REJECTED` | `retry` | `RETRY` |

**特有错误**

| 错误码 | HTTP | 触发条件 |
|--------|------|----------|
| `TOS-OFF-001` | 409 | 离线包版本冲突（SCN-06） |

**业务说明**

冲突处置路径：`CONFLICT` 状态下须由人工选择"重试"（`retry → RETRY → upload`）或"拒绝"（`reject → REJECTED`）。校验结果 `validation` 结构为 `{ valid: boolean, issues: string[] }`，常见 issue 包括 `VERSION_CONFLICT`。

---

### API-020 报表数据查询

| 项 | 内容 |
|----|------|
| API 编号 | API-020 |
| operationId | `GET_mock_reports` |
| 方法 / 路径 | `GET /mock/reports` |
| 归属页面 | UI-011 统计报表 |
| 权限 | `report:view`（角色：`BUSINESS` / `REGULATOR` / `DISPATCHER` / `AUDITOR`） |
| 响应类型 | `ReportSnapshot` |

**查询参数**

| 名称 | 位置 | 必填 | 类型 | 示例 | 说明 |
|------|------|------|------|------|------|
| `type` | query | 否 | string | `SHIFT` | 报表类型：`SHIFT` / `DAILY` / `MONTHLY` / `CUSTOM` |
| `period` | query | 否 | string | `2026-07-16` | 统计周期 |
| `dimensions` | query | 否 | array | `["planStatus"]` | 统计维度集合 |

**业务说明**

返回报表台账与指标快照两部分。指标快照包含：

- **KPI 计数**：`planTotal`、`confirmedPlanCount`、`appliedRecommendationCount`、`generatedWorkOrderCount`、`dispatchedWorkOrderCount`、`exceptionCount`、`interlockCount`、`mergedOfflinePacketCount`。
- **比率指标**：`planConfirmationRate`、`taskDecompositionRate`、`dispatchRate`、`exceptionClosureRate`、`offlineMergeRate`，每项须回带 `numerator`、`denominator`、`formula` 与 `source`。
- **分布指标**：计划状态分布、工单状态分布、异常等级分布、异常类型分布、联锁动作分布、离线包状态分布。

**统计口径声明**：演示环境使用确定性统计口径，返回值中必须携带 `disclosure` 字段说明。

---

### API-021 报表受控导出

| 项 | 内容 |
|----|------|
| API 编号 | API-021 |
| operationId | `POST_mock_reports_export` |
| 方法 / 路径 | `POST /mock/reports/export` |
| 归属页面 | UI-011 |
| 权限 | `report:export`（角色：`AUDITOR` / `BUSINESS` / `DISPATCHER` / `REGULATOR` / `SHIFT_LEADER`） |
| 响应类型 | `ExportTask` |

**请求体 `API021Request`**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `scope` | string | 是 | 导出范围 |
| `format` | string | 是 | 导出格式，如 `CSV` / `XLSX` |
| `purpose` | string | 是 | **导出用途（必填，用于合规留痕）** |

```json
{ "scope": "2026-07-16", "format": "XLSX", "purpose": "月度经营分析" }
```

**业务说明**

导出为受控动作，无论成功与否均必须写入审计（记录导出人、范围、格式、用途、时间与结果）。`REGULATOR` 导出时，敏感字段按脱敏规则输出，并在返回中标注脱敏字段清单。

---

### API-022 配置版本查询

| 项 | 内容 |
|----|------|
| API 编号 | API-022 |
| operationId | `GET_mock_config` |
| 方法 / 路径 | `GET /mock/config` |
| 归属页面 | UI-012 系统配置 |
| 权限 | `settings:view`（角色：`SYS_ADMIN` / `INTERFACE_OPS` / `SAFETY`） |
| 响应类型 | `API022SuccessEnvelope`（精确信封） |

**查询参数**

| 名称 | 位置 | 必填 | 类型 | 示例 | 说明 |
|------|------|------|------|------|------|
| `domain` | query | 否 | string | `dispatch` | 配置分组：`overview` / `dispatch` / `integration` / `governance` / `access` |

**成功响应示例**

```json
{
  "ok": true,
  "data": {
    "apiId": "API-022",
    "operationId": "GET_mock_config",
    "now": "2026-07-16T09:00:00+08:00",
    "scenarioId": "SCN-01",
    "items": [
      {
        "id": "CFG-001",
        "configVersion": "CFG-1.0",
        "displayName": "B项目生产调度 Demo",
        "defaultScenarioId": "SCN-01",
        "ruleVersion": "RULE-1.0",
        "dispatchStrategy": "BALANCED",
        "recommendationEnabled": true,
        "offlineSyncEnabled": true,
        "reportPeriod": "SHIFT",
        "auditRetentionDays": 180,
        "status": "DRAFT",
        "version": 1,
        "createdAt": "2026-07-16T08:15:00+08:00",
        "updatedAt": "2026-07-16T08:15:00+08:00",
        "updatedBy": "USER-011"
      }
    ]
  },
  "auditLogId": "AUD-022",
  "traceId": "TRACE-022"
}
```

**业务说明**

`DO-015 ConfigVersion` 是系统配置版本的**唯一权威身份**。返回的全部配置已按分组（`overview` / `dispatch` / `integration` / `governance` / `access`）组织，其中可编辑键固定为 8 个：

| 可编辑键 | 类型 | 约束 |
|----------|------|------|
| `displayName` | string | 1 ~ 64 字符 |
| `defaultScenarioId` | enum | `SCN-01` ~ `SCN-07` |
| `ruleVersion` | string | 1 ~ 32 字符 |
| `dispatchStrategy` | enum | `BALANCED` / `PRIORITY_FIRST` / `RESOURCE_FIRST` |
| `recommendationEnabled` | boolean | — |
| `offlineSyncEnabled` | boolean | — |
| `reportPeriod` | enum | `SHIFT` / `DAILY` / `MONTHLY` |
| `auditRetentionDays` | integer | 1 ~ 3650 |

其余字段（`id`、`configVersion`、`status`、`version`、`createdAt`、`updatedAt`、`updatedBy`）为只读。

---

### API-023 配置版本命令

| 项 | 内容 |
|----|------|
| API 编号 | API-023 |
| operationId | `POST_mock_config_id_command` |
| 方法 / 路径 | `POST /mock/config/{id}/command` |
| 归属页面 | UI-012 |
| 权限 | 按命令映射：`settings:edit` / `settings:publish` / `settings:rollback` / `settings:approve` |
| 响应类型 | `API023SuccessEnvelope`（精确信封，`items` 长度为 1） |

**路径参数**：`id`（string，必填，示例 `CFG-001`）

**请求体 `API023Request`**（判别联合，`oneOf` 两种形态）

*形态一 —— `edit`（可携带强类型白名单变更）*

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `command` | string | 是 | 字面量 `edit` |
| `expectedVersion` | integer | 是 | 正整数，乐观锁版本 |
| `changes` | object | 是 | 白名单字段变更集，至少 1 个键，且仅允许上述 8 个可编辑键 |
| `reason` | string | 否 | 变更原因，非空字符串 |

*形态二 —— 状态命令*

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `command` | string | 是 | 枚举：`submit` / `approve` / `publish` / `rollback` |
| `expectedVersion` | integer | 是 | 正整数 |
| `reason` | string | 否 | 非空字符串 |

**合法迁移（`DO-015`）**

| 当前状态 | 命令 | 目标状态 | 需权限 |
|----------|------|----------|--------|
| `DRAFT` | `edit` | `DRAFT` | `settings:edit` |
| `DRAFT` | `submit` | `SUBMITTED` | `settings:edit` |
| `SUBMITTED` | `approve` | `APPROVED` | `settings:approve` |
| `APPROVED` | `publish` | `PUBLISHED` | `settings:publish` |
| `PUBLISHED` | `rollback` | `ROLLED_BACK` | `settings:rollback` |

**请求示例（edit）**

```json
{
  "command": "edit",
  "expectedVersion": 1,
  "reason": "调整推荐开关",
  "changes": { "recommendationEnabled": false, "dispatchStrategy": "PRIORITY_FIRST" }
}
```

**业务说明**

- `changes` 为强类型白名单，任何未列入 8 个可编辑键的字段都会被拒绝（`DEMO-SCENARIO-001`）；
- `edit` 为草稿自迁移，状态保持 `DRAFT` 但 `version` 自增；
- `PUBLISHED` 后配置生效，旧的已发布版本进入 `ROLLED_BACK`；
- 提交人与审批人不得为同一 `actorId`（自审批禁止）。

---

### API-024 审计日志查询

| 项 | 内容 |
|----|------|
| API 编号 | API-024 |
| operationId | `GET_mock_audit_logs` |
| 方法 / 路径 | `GET /mock/audit-logs` |
| 归属页面 | UI-013 审计日志 |
| 权限 | `audit:view` / `audit:export` / `audit:verify`（角色：`AUDITOR` / `REGULATOR` / `SYS_ADMIN`） |
| 响应类型 | `Page<AuditLog>` |

**业务说明**

返回 `DO-013 AuditLog` 分页列表。单条审计记录结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 审计记录主键，如 `AUD-001` |
| `actorId` | string | 操作人 |
| `operatorTerminal` | string | 操作终端 |
| `action` | string | 动作名 |
| `objectType` | string | 对象类型（`DO-xxx`） |
| `objectId` | string | 对象 ID |
| `before` / `after` | object | 变更前后快照 |
| `reason` | string | 操作原因；失败时记录错误消息 |
| `traceId` | string | 链路追踪标识 |
| `occurredAt` | string | 发生时间 |

审计记录同时携带元数据：`roleCode`、`dataScope`、`result`（`SUCCESS` / `DENIED` / `FAILED`）、`errorCode`、`clientTime`、`serverTime`。

**不可变性**：审计记录一旦写入不可修改或删除，`SYS_ADMIN` 亦不具备该权限。审计保留期由 `DO-015.auditRetentionDays` 控制（1 ~ 3650 天）。

---

### API-025 演示场景重置

| 项 | 内容 |
|----|------|
| API 编号 | API-025 |
| operationId | `POST_mock_demo_reset` |
| 方法 / 路径 | `POST /mock/demo/reset` |
| 归属页面 | 全部（顶栏"重置场景"入口） |
| 权限 | `demo:reset`（角色：`BUSINESS` / `DISPATCHER` / `SHIFT_LEADER`） |
| 响应类型 | `DemoState` |

**请求体 `API025Request`**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `scenarioId` | string | 是 | 目标场景标识，如 `SCN-01` |

```json
{ "scenarioId": "SCN-01" }
```

**重置语义**

| 重置项 | 说明 |
|--------|------|
| `restoreBaseObjects` | 恢复固定种子对象（15 类对象共 90 条记录） |
| `restoreScenarioOverlay` | 恢复场景叠加态 |
| `resetTraceCounter` | 重置 `traceId` 计数器 |
| `resetAuditCounter` | 重置 `auditLogId` 计数器 |
| `clearFaultState` | 清除故障注入状态与已解决字段记录 |

**业务说明**

重置会将全部 15 类领域对象恢复到固定种子状态，并清空本次会话产生的增量变更与已解决字段记录。**重置不删除审计日志**（审计属于持久化证据）。重置后演示应从 `SCN-01` 重新开始。

---

## 6 枚举字典

以下 27 组枚举构成模块的公共字典，前端展示文案与后端存储值必须严格对应。

### 6.1 业务枚举

| 枚举组 | 取值 | 中文含义 |
|--------|------|----------|
| `CargoType` | `FLY_ASH` / `STEEL` / `CEMENT` / `GENERAL_CARGO` | 粉煤灰 / 钢材 / 水泥 / 普通货物 |
| `PlanStatus` | `RECEIVED` / `VALIDATING` / `PENDING_CONFIRM` / `CONFIRMED` / `DECOMPOSED` / `BLOCKED` / `CANCELLED` / `ADJUSTED` | 已接收 / 校验中 / 待确认 / 已确认 / 已拆解 / 受阻 / 已取消 / 已调整 |
| `TrackOccupyStatus` | `FREE` / `OCCUPIED` / `RELEASING` / `BLOCKED` | 空闲 / 占用 / 释放中 / 封锁 |
| `WorkOrderType` | `UNLOAD` / `TRANSFER` / `LOAD` / `INSPECT` | 卸车 / 倒运 / 装车 / 检验 |
| `WorkOrderPriority` | `LOW` / `MEDIUM` / `HIGH` / `URGENT` | 低 / 中 / 高 / 紧急 |
| `WorkOrderStatus` | `DRAFT` / `READY` / `DISPATCHED` / `ACKNOWLEDGED` / `IN_PROGRESS` / `COMPLETED` / `PAUSED` / `BLOCKED` / `FAILED` / `CANCELLED` | 草稿 / 待派 / 已下发 / 已接收 / 执行中 / 已完成 / 已暂停 / 受阻 / 失败 / 已取消 |
| `AckStatus` | `PENDING` / `ACKNOWLEDGED` / `REJECTED` / `TIMEOUT` | 待回执 / 已回执 / 已拒绝 / 回执超时 |
| `WorkNodeStatus` | `WAITING` / `READY` / `IN_PROGRESS` / `COMPLETED` / `SKIPPED` / `BLOCKED` / `FAILED` | 等待 / 就绪 / 执行中 / 已完成 / 已跳过 / 受阻 / 失败 |
| `ResourceType` | `TIPPER` / `CRANE` / `CONVEYOR` / `SILO` / `AGV` / `TEAM` | 翻车机 / 起重机 / 输送机 / 筒仓 / AGV / 班组 |
| `ResourceStatus` | `AVAILABLE` / `BUSY` / `OFFLINE` / `MAINTENANCE` / `LOCKED` | 可用 / 作业中 / 离线 / 检修 / 锁定 |
| `AppointmentStatus` | `DRAFT` / `SUBMITTED` / `APPROVED` / `QUEUED` / `CALLED` / `ENTERED` / `OPERATING` / `RELEASED` / `EXITED` / `REJECTED` / `NEED_FIX` / `EXCEPTION` | 草稿 / 已提交 / 已核准 / 排队中 / 已叫号 / 已入场 / 作业中 / 已放行 / 已离场 / 已驳回 / 待补充 / 异常 |
| `GateStatus` | `WAITING` / `OPEN` / `PASSED` / `CLOSED` / `REJECTED` | 待验 / 放行中 / 已通过 / 已关闭 / 已拒绝 |

### 6.2 安全与异常枚举

| 枚举组 | 取值 | 中文含义 |
|--------|------|----------|
| `ExceptionType` | `DEVICE_OFFLINE` / `DATA_CONFLICT` / `INTERLOCK` / `TIMEOUT` / `QUALITY` | 设备离线 / 数据冲突 / 联锁 / 超时 / 质量 |
| `ExceptionLevel` | `INFO` / `MINOR` / `MAJOR` / `CRITICAL` | 提示 / 轻微 / 严重 / 紧急 |
| `ExceptionStatus` | `OPEN` / `ACKNOWLEDGED` / `HANDLING` / `PENDING_REVIEW` / `CLOSED` / `ESCALATED` / `REOPENED` | 待处理 / 已认领 / 处置中 / 待复核 / 已闭环 / 已升级 / 已重开 |
| `ActionLevel` | `WARN` / `PAUSE` / `FORCE_STOP` | 告警 / 暂停 / 强制停机 |
| `InterlockStatus` | `TRIGGERED` / `ACTION_ISSUED` / `WAITING_RECEIPT` / `LOCKED` / `RESET_REQUESTED` / `APPROVED` / `RESTORED` / `FAILED` / `OVERRIDE_PENDING` / `OVERRIDDEN` | 已触发 / 已下发动作 / 待回执 / 已锁定 / 已申请复位 / 已批准 / 已恢复 / 失败 / 待覆盖审批 / 已覆盖 |
| `ReceiptStatus` | `PENDING` / `RECEIVED` / `FAILED` | 待回执 / 已回执 / 回执失败 |

### 6.3 同步、报表与治理枚举

| 枚举组 | 取值 | 中文含义 |
|--------|------|----------|
| `MergeStatus` | `CACHED` / `PENDING_UPLOAD` / `VALIDATING` / `MERGED` / `CONFLICT` / `REJECTED` / `RETRY` | 已缓存 / 待上传 / 校验中 / 已合并 / 冲突 / 已拒绝 / 待重试 |
| `ReportType` | `SHIFT` / `DAILY` / `MONTHLY` / `CUSTOM` | 班报 / 日报 / 月报 / 自定义报表 |
| `GenerateStatus` | `PENDING` / `RUNNING` / `SUCCESS` / `FAILED` | 待生成 / 生成中 / 生成成功 / 生成失败 |
| `RoleCode` | 见 2.3 节 13 个角色 | — |
| `UserStatus` | `ACTIVE` / `DISABLED` / `LOCKED` | 启用 / 停用 / 锁定 |
| `ConfigStatus` | `DRAFT` / `SUBMITTED` / `APPROVED` / `PUBLISHED` / `ROLLED_BACK` | 草稿 / 已提交 / 已审批 / 已发布 / 已回滚 |
| `DispatchStrategy` | `BALANCED` / `PRIORITY_FIRST` / `RESOURCE_FIRST` | 均衡调度 / 优先级优先 / 资源优先 |
| `ConfigReportPeriod` | `SHIFT` / `DAILY` / `MONTHLY` | 班 / 日 / 月 |
| `PublicErrorCode` | 见 3.4 节 9 个错误码 | — |

---

## 7 状态机与命令约束

模块共定义 **8 个状态机**，其中 7 个对应领域对象，1 个（`SM-007`）用于描述接口调用的执行生命周期。

### 7.1 状态机总览

| 状态机 | 对应对象 | 状态数 | 命令数 | 合法迁移数 |
|--------|----------|--------|--------|------------|
| `DO-001` | Plan 计划 | 8 | 5 | 8 |
| `DO-005` | WorkOrder 工单 | 10 | 6 | 7 |
| `DO-008` | Appointment 预约 | 12 | 6 | 8 |
| `DO-009` | DispatchException 异常 | 7 | 6 | 7 |
| `DO-010` | Interlock 联锁 | 10 | 6 | 8 |
| `DO-011` | OfflinePacket 离线包 | 7 | 5 | 8 |
| `DO-015` | ConfigVersion 配置版本 | 5 | 5 | 5 |
| `SM-007` | 接口调用生命周期 | 5 | 6 | 7 |

### 7.2 状态机迁移校验结果

调用 `transitionState({ machineId, current, command })` 返回三种失败原因：

| 失败原因 | 含义 | 返回错误码 |
|----------|------|------------|
| `UNKNOWN_STATE` | 当前状态不在该状态机的状态集合内 | `DEMO-SCENARIO-001` |
| `UNKNOWN_COMMAND` | 命令不在该状态机的命令集合内 | `DEMO-SCENARIO-001` |
| `FORBIDDEN_TRANSITION` | 状态与命令均在集合内，但迁移不是合法迁移 | `DEMO-SCENARIO-001` |

成功时返回 `{ previous, next, command }`，用于审计记录的 `before` / `after` 快照。

### 7.3 接口调用生命周期（`SM-007`）

用于统一描述所有外部依赖调用的执行过程，是熔断与重试策略的基础。

| 当前状态 | 命令 | 目标状态 |
|----------|------|----------|
| `ACCEPTED` | `accept` | `ACCEPTED` |
| `ACCEPTED` | `execute` | `EXECUTING` |
| `EXECUTING` | `succeed` | `SUCCESS` |
| `EXECUTING` | `fail` | `FAILED` |
| `EXECUTING` | `timeout` | `TIMEOUT` |
| `FAILED` | `retry` | `EXECUTING` |
| `TIMEOUT` | `retry` | `EXECUTING` |

### 7.4 状态与接口对应关系

| 状态机 | 驱动该状态机的接口 |
|--------|--------------------|
| `DO-001` | API-003（sync）、API-004（confirm / adjust）、API-007（decompose） |
| `DO-005` | API-008（assign）、API-009（dispatch / ack / start / pause / complete） |
| `DO-008` | API-011（submit / approve / call / gateIn / release / gateOut） |
| `DO-009` | API-015（ack / assign / handle / review / close / reopen） |
| `DO-010` | API-017（trigger / receipt / requestReset / approve / restore / requestOverride） |
| `DO-011` | API-019（upload / validate / merge / reject / retry） |
| `DO-015` | API-023（edit / submit / approve / publish / rollback） |
| `SM-007` | 全部涉及外部依赖的接口，由服务端内部驱动 |

---

## 8 附录

### 8.1 领域对象清单与种子数据规模

| 对象编号 | 对象名称 | 对应表（见数据库设计文档） | 种子记录数 | 关键唯一键 |
|----------|----------|---------------------------|-----------|------------|
| DO-001 | Plan 计划 | `psms_plan` | 3 | `plan_batch_no` |
| DO-002 | Waybill 运单 | `psms_waybill` | 8 | `waybill_no` |
| DO-003 | Track 股道 | `psms_track` | 4 | `track_no` |
| DO-004 | Material 物料 | `psms_material` | 8 | `material_id` |
| DO-005 | WorkOrder 工单 | `psms_work_order` | 12 | `work_order_no` |
| DO-006 | WorkNode 作业节点 | `psms_work_node` | 12 | `node_no` + `work_order_no` |
| DO-007 | Resource 资源 | `psms_resource` | 10 | `device_id` / `person_id` |
| DO-008 | Appointment 预约 | `psms_appointment` | 6 | `reservation_no` |
| DO-009 | DispatchException 异常 | `psms_exception` | 5 | `exception_no` |
| DO-010 | Interlock 联锁 | `psms_interlock` | 4 | `interlock_no` |
| DO-011 | OfflinePacket 离线包 | `psms_offline_packet` | 4 | `offline_package_no` |
| DO-012 | Report 报表 | `psms_report` | 3 | `report_type` + `period` |
| DO-013 | AuditLog 审计日志 | `psms_audit_log` | 9 | `trace_id` |
| DO-014 | UserRole 用户角色 | `psms_user_role` | 13 | `actor_id` |
| DO-015 | ConfigVersion 配置版本 | `psms_config_version` | 1 | `config_version` |

**种子数据总量**：90 条业务记录 + 13 条角色记录 + 7 条场景定义 + 9 条错误码定义 + 14 条验收场景定义。

### 8.2 请求 Schema 索引

| Schema 名称 | 适用接口 | 是否必填字段 |
|-------------|----------|--------------|
| `API003Request` | API-003 | `scenarioId` |
| `API004Request` | API-004 | 无（均可选） |
| `API006Request` | API-006 | `trackNo`、`window` |
| `API007Request` | API-007 | `ruleVersion`、`mode` |
| `API008Request` | API-008 | `resourceId` |
| `API009Request` | API-009 | `target`、`simulateReceipt` |
| `API011Request` | API-011 | `action`、`evidence` |
| `API013Request` | API-013 | `speed` |
| `API015Request` | API-015 | `command`、`evidence` |
| `API017Request` | API-017 | `command`、`receipt`、`approval` |
| `API019Request` | API-019 | `upload`、`merge`、`reject`、`retry` |
| `API021Request` | API-021 | `scope`、`format`、`purpose` |
| `API023Request` | API-023 | `command`、`expectedVersion`（`edit` 另需 `changes`） |
| `API025Request` | API-025 | `scenarioId` |

> 14 个请求 Schema 与 14 个写接口一一对应；其中 API-023 使用判别联合（`oneOf`）承载 `edit` 与状态命令两种形态。

### 8.3 需求追溯

完整的需求 → 页面 → 接口 → 测试追溯见 `docs/baseline/traceability.csv`，覆盖 7 大需求族（FR-001 计划、FR-002 任务与派工、FR-003 公路预约、FR-004 监控与异常、FR-005 报表、FR-006 安全联锁、FR-007 离线与弱网）共 47 条需求条目。

### 8.4 变更记录

| 版本 | 日期 | 变更说明 |
|------|------|----------|
| 0.3.0 | 2026-09-21 | 首版接口设计文档，基于契约基线 0.3.0 编写，冻结 15/25/7/9 规模 |

---

*本文件由 PSMS 模块代码与契约基线自动梳理生成，任何契约变更必须同步更新本文件与 `docs/baseline/openapi.yaml`。*
