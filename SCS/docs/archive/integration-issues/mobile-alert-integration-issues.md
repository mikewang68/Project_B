# 移动端 / 安全大屏 × Alert 后端集成说明与遗留问题

> 阶段：第四阶段（移动端告警处置 + 安全大屏真实 API + WebSocket 三端联动）
> 日期：2026-09-05
> 范围：记录旧 `Incident`（mock/incidents.ts）与后端 `Alert` 的字段映射、Backend Demo 过渡设计，以及后续正式领域设计需要统一的问题。本阶段不做最终领域建模。

## 1. 统一原则

- `Alert`（`ALM-20260904-xxx`）是唯一权威安全事件数据源；管理端 `/alarms`、移动端 `/mobile/*`、安全大屏 `/safety-screen` 三端共享同一 `alertId`。
- 后端不存在 `IncidentRepository` / `ScreenAlertRepository`；移动端首页与大屏概览均为只读 Projection（`MobileService` / `ScreenService` 聚合 `AlertRepository`）。
- WebSocket（`/ws/live`）只发“什么变了”的轻量通知；REST 仍是权威读取，前端收到事件后重新请求。

## 2. 旧 Incident 字段 → Alert 字段映射

| 移动端/大屏字段（旧 Incident） | Alert 字段 / 适配方式 | 说明 |
| --- | --- | --- |
| `id`（INC-20260904-001） | `id`（ALM-20260904-xxx） | 直接使用 Alert 主键，路由 `/mobile/alert/:id` 同步更换 |
| `title` | `title` | 同名 |
| `risk` | `risk`（一般/预警/严重/紧急） | 同名，经 `mapAlertLevel` 白名单兜底 |
| `source`（4 类） | `source`（人员安全/设备防碰撞/AI违规/设备异常/系统异常） | 类型补充“系统异常” |
| `area` / `target` / `time` | `area` / `target` / `time`（HH:mm 由 ISO/HMS 截取） | 时间统一由后端 ISO-8601 驱动，前端只做展示格式化 |
| `status`（待接单/已接单/已到场/处理中/待复核/已关闭） | `status` + `mobileStage` 联合投影，见 §3 | 主状态机不改动 |
| `evidence` | `evidence`（personnel/collision/ai/device-metric/system-metric 五类） | 复用管理端 `AlertEvidence` 渲染组件 |
| `timeline` | `timeline` | 每次写操作由后端追加，刷新后仍在 |
| `assignee` | `assignee` | 派单/转派后更新 |
| `acceptTime` | `acceptedAt`（accept）/ `acceptTime`（start） | HH:mm 展示 |
| `arriveTime` | `arrivedAt`（arrive） | 新增 |
| `handleTime` | start 写入的 `acceptTime` | 过渡复用 |
| `submitTime` / `measures` / `siteNote` / `riskCleared` / `note` | `treatment`（submitTime/measures/result/note） | `siteNote+note` 合并写入后端 note；`riskCleared → result=风险已解除` |
| `photos[]` | 无后端字段（SIMULATED） | 仅本地 Mock/dataURL；提交时以 `attachment="现场照片 N 张（Mock）"` 记录数量 |
| `linkage{soundLight,shutdown,plc}` | 7 步 `linkage[]`（sound-light/shutdown/plc…）投影 | done/running/failed/idle |
| `distanceM` | 无真实定位（SIMULATED） | 按 id 稳定哈希派生 42–229m，刷新不变 |
| `pos{x,y}` | 无真实坐标服务（SIMULATED） | 按 id 稳定哈希派生，地图底图/人员设备基础位置仍是 Demo |
| `upgradedFrom` | `upgradedFrom` | 同名 |

适配集中在 `frontend/src/adapters/mobileAlert.ts`（`mapAlertToMobileIncident` / `mapMobileStatus`），组件内不允许再写状态 if/switch。

## 3. 移动端状态过渡设计（mobileStage）

管理端主状态机保持不变：待确认 → 待派单 → 待处理 → 处理中 → 待复核 → 已关闭。
移动端现场需要“已接单 / 已到场”两个更细阶段，本阶段以 `DemoAlert.mobileStage` 承接（不改主状态）：

| 主状态 status | mobileStage | 移动端展示状态 | 触发接口 |
| --- | --- | --- | --- |
| 待处理 | null / PENDING | 待接单 | 派单后置 PENDING |
| 待处理 | ACCEPTED | 已接单 | `POST /api/v1/mobile/incidents/{id}/accept` |
| 待处理 | ARRIVED | 已到场 | `POST /api/v1/mobile/incidents/{id}/arrive` |
| 处理中 | PROCESSING | 处理中 | `POST /api/v1/alerts/{id}/start` |
| 待复核 | — | 待复核 | `POST /api/v1/alerts/{id}/treatment` |
| 已关闭 | — | 已关闭 | `POST /api/v1/alerts/{id}/review` |

非法阶段调用返回 409 `STATE_CONFLICT`（例如未接单直接到场）。

## 4. 仍为 Mock / SIMULATED 的能力

- 地图底图、人员/设备基础坐标、事件 `pos` 与 `distanceM`（无 UWB/GPS 实时服务）；
- 现场照片（dataURL 本地留存，无对象存储/文件服务）；
- PLC 真实控制、雷达/摄像头原始数据、真实 AI 推理、视频流；
- 大屏 24h 趋势曲线、在岗人数与设备台账（后端以 `demo=true` 标记的聚合常量返回，页面注明 Demo）；
- 推送通知（无 APNs/FCM/短信/企微）。

## 5. 后续正式领域设计待处理问题（本阶段不修改）

1. **状态模型统一**：`mobileStage` 是 Demo 过渡字段。正式设计需决定现场处置阶段（接单/到场）是并入主事件状态机，还是独立为“处置工单/任务”实体；当前“已升级”状态没有回到处理链的标准路径（escalate 后 status=已升级，需与处置状态共存规则统一）。
2. **Alert 与 AIEvent 的边界**：AI 违规事件目前直接作为 Alert 种子存在，正式模型需明确“识别事件 → 告警”的转换与 1:N 关系。
3. **证据多态结构**：五类 evidence 是 sealed union，正式建模时需确定单表 JSONB / 证据子表方案，以及证据与原始采集（图片、雷达序列）的引用方式。
4. **照片与附件**：正式需要对象存储与证据留存策略（时效、水印、哈希），当前仅记录数量文本。
5. **位置与距离**：正式接入定位引擎后，`pos/distanceM` 应由实时服务提供，并明确坐标系与更新频率。
6. **多实例 WebSocket 广播**：当前单实例 SessionRegistry；node4/node5 双实例部署时需经 RocketMQ 广播跨实例转发。
7. **操作人身份**：Demo 写操作的 operator/handler 为固定演示用户，正式需接入认证体系与审计字段。
