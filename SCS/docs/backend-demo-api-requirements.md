# Backend Demo 接口需求清单

> 版本：v1.0 ｜ 日期：2026-09-04
> 用途：指导下一阶段 **Backend Demo** 开发，让当前 Vue3 + TS 纯前端 Demo 逐步移除 Mock、改调真实 API。
> 配套机器可读清单：[`backend-demo-api-inventory.json`](./backend-demo-api-inventory.json)（116 条，含每条 request/response/priority/realtime/mockSource）。
> 边界：本文**不是**生产级 API/领域设计，不做 DDD、微服务拆分、最终状态机与 ER 图；以"前端页面能真正跑起来"为唯一目标。

---

## 1. 扫描范围与方法

已完整扫描 `frontend/src` 下全部目录，并**从页面实际行为反推**接口（不只看已有 api 文件）：

| 目录 | 扫描结论 |
|---|---|
| `views/`（11 个管理端页面 + 3 个移动页面 + 1 个大屏页面） | 页面编排层，绝大多数 Mock 数据与写操作逻辑集中在此 |
| `components/`（13 个子目录，约 90 个组件） | 复用组件（地图/证据/时间线/图表/Dialog/Drawer/Badge）与各模块交互 |
| `mock/`（7 个文件） | 全部 Mock 数据源（详见第 3 节） |
| `stores/`（safety / operations / incident） | safety 已预留 API 调用；operations、incident 当前纯前端状态 |
| `api/`（http.ts、live.ts） | **已存在 API 基座**：fetch 封装 + WebSocket 客户端（详见 4.2） |
| `types/`（11 个文件） | 全部数据结构与枚举，是 response 字段的直接依据 |
| `router/` | 14 条路由，含 2 个独立 standalone 页面（大屏/移动端） |
| `composables/useEChart.ts` | 图表基座，不产生接口需求 |

---

## 2. 前端模块清单

| 模块 | 路由 | 主要页面/组件 | 当前数据方式 |
|---|---|---|---|
| 用户/登录 | 无登录页 | AppHeader（李娜/安全员）、MobileHome（王建国） | **硬编码**，无登录 |
| 安全态势首页 | `/overview` | SafetyOverview、SafetyMap、RealtimeAlertFeed、2 图表、AlertDrawer | 组件内联 Mock |
| 人员定位 | `/people` | PersonnelList、PersonnelDetailDrawer、TrackPlayback、SafetyMapCanvas | 页面内联 Mock |
| 电子围栏 | `/fences` | FenceList、FenceDetailPanel、FencePublishDialog、地图绘点 | 页面内联 Mock |
| 设备防碰撞 | `/devices` | EquipmentRelationMap、RiskStatusPanel、DistanceTrendChart、SafetyLinkagePanel、ManualTakeover/RiskRelease Dialog | 页面内联 Mock + 定时器模拟 |
| AI 违规识别 | `/ai` | AIOverview、AIEventGrid/Card/Drawer、ReviewDialog、AssignmentDialog、MockAIEventButton | `mock/aiEvents.ts` |
| 告警中心 + 处置闭环 | `/alarms` | AlertOverview/Filters/Table/DetailDrawer、Assign/Treatment/Review Dialog、LinkageStatus、SLAIndicator、Timeline | `mock/alertEvents.ts` + 本地 SLA 走秒 |
| 统计分析 | `/analytics` | KPI、趋势/类型/区域/班组/设备/人员/等级 7 图、EventDetailTable、下钻 | `mock/analyticsData.ts`（默认/突增两套） |
| 规则配置 | `/rules` | RuleOverview/CategoryNav/Toolbar/Table/DetailDrawer/FormDrawer、Simulation/Conflict/Publish/Rollback Dialog、VersionHistory、EdgeSyncPanel | `mock/ruleData.ts` |
| 运维监控 | `/operations` | OpsOverview/Toolbar、SystemTopology、EdgeNodeCard/Drawer、DeviceHealthPanel、InterfaceMonitor、OpsEventFeed、CacheStatus、LocalEventQueue、RecoveryDialog、DeviceDiagnosticDialog | `mock/opsData.ts` |
| 云边断网自治 | `/operations`（同页） | OfflineBanner、断网/本地风险/恢复/对账流程 + operations store | 页面 + store 前端状态机 |
| 安全大屏 | `/safety-screen`（standalone） | BigScreenHeader/Metrics/Map/AlertFeed/CriticalAlertPopup/Trend/DeviceHealth | incident store + 组件内联 |
| 移动端告警处置 | `/mobile/*`（standalone） | MobileLayout/Home/AlertList/AlertCard/AlertDetail/TreatmentForm/EvidenceUpload/EscalateDialog | `mock/incidents.ts` + incident store（含 BroadcastChannel 跨页联动） |
| 全局实时 | App 挂载 | `api/live.ts` SafetyLiveClient、`stores/safety.ts` | 已尝试连 `/api/v1/state` 与 `WS /ws/live`，失败回退 Mock |

---

## 3. Mock 数据来源清单（后端替换的直接靶点）

| Mock 文件/位置 | 内容 | 被谁消费 | 替换接口方向 |
|---|---|---|---|
| `mock/snapshot.ts` `demoSnapshot` | 全局快照（作业计划/人员/围栏/设备/AI/告警/规则/场景/审计/系统） | safety store（API 失败时 fallback） | `GET /state`、`WS /ws/live` |
| `components/overview/SafetyOverview.vue` 内联 | 首页 6 人员、4 设备、3 告警、2 图表 | 首页 | `/overview/*` |
| `views/PersonnelLocationView.vue` 内联 | 7 名人员、2 围栏、9 点轨迹 | 人员定位 | `/personnel*` |
| `views/FenceManagementView.vue` 内联 | 4 个围栏（含 polygon/边缘节点状态） | 电子围栏 | `/fences*` |
| `views/CollisionOverviewView.vue` 内联 | 4 台设备、距离序列、联动步骤、3 个模拟器 | 设备防碰撞 | `/collision/*` |
| `mock/aiEvents.ts` | 10+ AI 事件、指标基数、序号生成器、场景图映射 | AI 违规 | `/ai-events*`、`/cameras` |
| `mock/alertEvents.ts` | 12 条告警（含 5 类证据/联动/时间线）、指标基数 | 告警中心 | `/alerts*` |
| `mock/analyticsData.ts` | 默认数据集 + 装卸区 A 突增数据集（KPI/趋势/7 聚合块/18 条明细） | 统计分析 | `/analytics/*` |
| `mock/ruleData.ts` | 23 条规则、冲突样例、规则仿真计算 | 规则配置 | `/rules*` |
| `mock/opsData.ts` | 4 边缘节点、5 类设备、6 接口、异常 feed | 运维监控 | `/ops/*` |
| `mock/incidents.ts` | 移动端 5 条事件 + 越界演示事件工厂 | 大屏 + 移动端 | `/mobile/*`、`/screen/*` |
| 各页面"模拟 xxx"按钮 | 14 类前端演示器（越界/低置信度/摄像头异常/断网/本地风险/时间偏差/缓存告警/设备离线/风险突增…） | 各模块 | **保留为 SIMULATED**，或由后端模拟器接口驱动（P2） |

---

## 4. 现状中与后端相关的关键事实

### 4.1 数据双轨现状（后端需要先决定走哪条）

- **轨道 A（已预留但几乎没页面用）**：`stores/safety.ts` 挂载时调 `GET /api/v1/state` 拉全量 `SafetySnapshot`，并连 `WS /ws/live` 接收快照；任何写操作走 `POST` 并由后端**返回最新全量快照**。失败时自动回退 `demoSnapshot`。
- **轨道 B（当前 99% 页面实际使用）**：各页面各自 import mock 或内联数据，在前端本地完成全部状态流转。
- **Backend Demo 建议**：以轨道 B 的页面级 REST 接口为主（见第 5 节），同时把 `/state` 与 `/ws/live` 实现为"聚合读取 + 增量推送"，两条轨在 Demo 阶段并存，不强制现在统一。

### 4.2 前端已经写死的 HTTP/WS 约定（后端直接对齐，成本最低）

- BasePath：`/api/v1`（可用 `VITE_API_BASE` 覆盖）；WS 路径 `/ws/live`（可用 `VITE_WS_PATH` 覆盖）。
- 请求头：`Accept: application/json`、`X-Trace-Id`（uuid）；非 GET 自动带 `Idempotency-Key` 与 `Content-Type: application/json`。
- 超时 8s；WS 20s 发一次 `{"type":"ping"}`，断线指数退避重连。
- 快照消息识别：JSON 中含 `schemaVersion` 字段即视为 `SafetySnapshot`。

---

## 5. 分模块接口需求

> 字段级 request/response 见 JSON 清单同名条目。下表"写后变化/错误提示"是前端当前行为，后端返回体需要支撑这些更新。
> 分页列：○=不分页（一次返回）、◐=建议分页、●=必须分页。实时列：◆=需要实时（轮询/WS）。

### 5.1 用户 / 登录

| # | 操作 | 方法 | 路径 | 优先级 | 分页 | 实时 | 说明 / 前端成功后变化 |
|---|---|---|---|---|---|---|---|
| 1 | 登录 | POST | `/auth/login` | P1 | ○ | | Demo 可固定账号；返回 token + 用户信息 |
| 2 | 当前用户/班次 | GET | `/auth/me` | **P0** | ○ | | 替换 Header「李娜/安全员」、移动端「王建国/夜班」硬编码 |
| 3 | 字典（区域/班组/责任人） | GET | `/meta/dictionaries` | P1 | ○ | | 替换各页面散落的内联下拉常量 |

错误提示：401 跳登录（Demo 可省略）；用户信息加载失败时前端保留默认演示身份并提示"演示数据"。

### 5.2 安全态势首页（`/overview`）

| # | 操作 | 方法 | 路径 | 优先级 | 分页 | 实时 |
|---|---|---|---|---|---|---|
| 4 | 核心指标 | GET | `/overview/summary` | **P0** | ○ | |
| 5 | 态势地图（人/设备/围栏） | GET | `/overview/map` | **P0** | ○ | ◆ |
| 6 | 实时告警流 | GET | `/overview/alerts/feed` | P1 | ○ | ◆ |
| 7 | 风险趋势 | GET | `/overview/risk-trend` | P1 | ○ | |
| 8 | 风险类型分布 | GET | `/overview/risk-distribution` | P1 | ○ | |
| 9 | 首页告警详情 | GET | `/overview/alerts/{id}` | P1 | ○ | 可直接复用 `/alerts/{id}` |
| 10 | 模拟风险（演示器） | POST | `/overview/simulate-risk` | P2 | | SIMULATED |

### 5.3 人员定位（`/people`）

| # | 操作 | 方法 | 路径 | 优先级 | 分页 | 实时 | 说明 |
|---|---|---|---|---|---|---|---|
| 11 | 人员列表+统计+多筛选 | GET | `/personnel` | **P0** | ◐ | | keyword/team/area/state/bracelet/onlyAbnormal |
| 12 | 人员详情 | GET | `/personnel/{id}` | **P0** | ○ | | Drawer 全部字段 |
| 13 | 历史轨迹 | GET | `/personnel/{id}/track` | P1 | ○ | | from/to，轨迹回放按点播放 |
| 14 | 实时位置批量 | GET | `/personnel/live` | P1 | ○ | ◆ 约 3s | 也可并入 WS |
| 15 | 手环提醒（写） | POST | `/personnel/{id}/remind` | P1 | | | 成功 Toast"提醒已发送至手环" |
| 16 | 人员相关告警 | GET | `/personnel/{id}/alerts` | P2 | ◐ | |
| 17 | 模拟人员异常 | POST | `/personnel/simulate-abnormal` | P2 | | SIMULATED |

### 5.4 电子围栏（`/fences`）

| # | 操作 | 方法 | 路径 | 优先级 | 分页 | 实时 | 说明 |
|---|---|---|---|---|---|---|---|
| 18 | 围栏列表 | GET | `/fences` | **P0** | ○ | |
| 19 | 围栏详情（含边缘节点同步态） | GET | `/fences/{id}` | **P0** | ○ | |
| 20 | **新建围栏**（草稿/提交评审） | POST | `/fences` | **P0** | | | body 含 polygon 顶点；成功插入列表并选中 |
| 21 | **编辑围栏** | PUT | `/fences/{id}` | **P0** | | |
| 22 | 提交评审 | POST | `/fences/{id}/submit-review` | P1 | | |
| 23 | **发布（边缘下发）** | POST | `/fences/{id}/publish` | **P0** | | 返回 4/4 节点同步结果，状态→已生效 |
| 24 | 单节点重新下发 | POST | `/fences/{id}/redeliver` | P1 | | failed→syncing→success |
| 25 | 停用 | POST | `/fences/{id}/disable` | P2 | | |
| 26 | 模拟版本不一致 | POST | `/fences/{id}/simulate-mismatch` | P2 | | SIMULATED |

### 5.5 设备防碰撞（`/devices`）

| # | 操作 | 方法 | 路径 | 优先级 | 分页 | 实时 | 说明 |
|---|---|---|---|---|---|---|---|
| 27 | 设备列表 | GET | `/collision/devices` | **P0** | ○ | |
| 28 | 设备详情 | GET | `/collision/devices/{id}` | **P0** | ○ | |
| 29 | 设备对实时距离/速度 | GET | `/collision/pair` | P1 | ○ | ◆ | 真实毫米波雷达 SIMULATED，Demo 用模拟器推数 |
| 30 | 距离趋势 | GET | `/collision/devices/{id}/distance-trend` | P1 | ○ | ◆ |
| 31 | **人工接管确认（写）** | POST | `/collision/takeover` | **P0** | | PLC 失败兜底，必须落记录 |
| 32 | 申请解除运行限制 | POST | `/collision/release-request` | P1 | | |
| 33 | 发起联动（减速/停机） | POST | `/collision/linkage` | P1 | ◆ | 真实 PLC SIMULATED，步骤可 WS 回推 |
| 34 | 模拟接近/雷达断数/失败 | POST | `/collision/simulate` | P2 | | SIMULATED |

### 5.6 AI 违规识别（`/ai`）

| # | 操作 | 方法 | 路径 | 优先级 | 分页 | 实时 | 说明 |
|---|---|---|---|---|---|---|---|
| 35 | AI 事件列表+指标+7 项筛选 | GET | `/ai-events` | **P0** | ● | | 含 today/pending/confirmed/falsePositive/cameraFault 指标 |
| 36 | 事件详情（检测框/时间线） | GET | `/ai-events/{id}` | **P0** | ○ | boxes 为相对截图百分比坐标 |
| 37 | **确认违规（写）** | POST | `/ai-events/{id}/confirm` | **P0** | | 待复核→已确认违规，记录 reviewer/reviewTime |
| 38 | **标记误报（写）** | POST | `/ai-events/{id}/false-positive` | **P0** | | reason 五枚举：遮挡误判/光照/识别错误/区域配置/其他 |
| 39 | 标记不确定 | POST | `/ai-events/{id}/uncertain` | P1 | | 进入人工复核队列 |
| 40 | **AI 派单（写）** | POST | `/ai-events/{id}/assign` | **P0** | | assignee/priority/note，状态→已派单 |
| 41 | 开始处理 | POST | `/ai-events/{id}/process` | P1 | | |
| 42 | 关闭 | POST | `/ai-events/{id}/close` | P1 | | |
| 43 | 摄像头健康列表 | GET | `/cameras` | P1 | ○ | 与运维设备健康存在重叠（见第 9 节） |
| 44 | 模拟新事件/低置信度/摄像头异常 | POST | `/ai-events/simulate` | P2 | ◆ | SIMULATED，真实 AI 推理不实现 |

### 5.7 告警中心 + 处置闭环（`/alarms`，写操作最密集）

| # | 操作 | 方法 | 路径 | 优先级 | 分页 | 实时 | 说明 |
|---|---|---|---|---|---|---|---|
| 45 | 顶部统计 | GET | `/alerts/metrics` | **P0** | ○ | |
| 46 | 告警列表（8 项筛选） | GET | `/alerts` | **P0** | ● | ◆ | **SLA 请返回 `slaDeadline`**，替代前端本地走秒 |
| 47 | 告警详情（5 类证据/联动/时间线） | GET | `/alerts/{id}` | **P0** | ○ | evidence 为联合结构，见 `types/alert.ts` |
| 48 | **确认事件（写）** | POST | `/alerts/{id}/confirm` | **P0** | | 待确认→待派单 |
| 49 | **派单（写）** | POST | `/alerts/{id}/assign` | **P0** | | 待派单→待处理，含优先级/时限/备注 |
| 50 | **转派（写）** | POST | `/alerts/{id}/transfer` | P1 | | |
| 51 | **接单/开始处理（写）** | POST | `/alerts/{id}/start` | **P0** | | 待处理→处理中，记录 acceptTime |
| 52 | **提交处置结果（写）** | POST | `/alerts/{id}/treatment` | **P0** | | measures/result/note，处理中→待复核 |
| 53 | **复核通过关闭（写）** | POST | `/alerts/{id}/review` | **P0** | | 严重/紧急必须复核，前端有二次确认 |
| 54 | 复核驳回 | POST | `/alerts/{id}/review-reject` | P1 | | 退回处理中 |
| 55 | 事件升级（写） | POST | `/alerts/{id}/escalate` | P1 | | 移动端共用 |
| 56 | 发起安全联动 | POST | `/alerts/{id}/linkage` | P1 | ◆ | 7 步：声光/手环/安全员/调度/减速/停机/PLC |
| 57 | **人工接管（写）** | POST | `/alerts/{id}/takeover` | **P0** | | PLC 超时兜底 |

统一错误提示：写操作失败时 Toast 显示后端 message，并保持原状态不变（前端不乐观更新或可回滚）；状态机非法流转返回 409，提示"当前状态不允许该操作"。

### 5.8 统计分析（`/analytics`）

| # | 操作 | 方法 | 路径 | 优先级 | 分页 | 实时 | 说明 |
|---|---|---|---|---|---|---|---|
| 58 | **聚合数据集（KPI+7 图表块）** | GET | `/analytics/dataset` | **P0** | ○ | period/area/team/type/level；Demo 单接口返回全部聚合，联调最快 |
| 59 | **事件明细（下钻表格）** | GET | `/analytics/events` | **P0** | ● | 图表点击下钻=带维度参数请求本接口 |
| 60 | 高频设备 Drawer | GET | `/analytics/devices/{id}` | P1 | ○ | |
| 61 | 重复风险人员 Drawer | GET | `/analytics/persons/{id}` | P2 | ○ | 安全管理视角，非黑名单 |
| 62 | 风险突增场景切换 | POST | `/analytics/simulate-surge` | P2 | | SIMULATED |

### 5.9 规则配置（`/rules`）

| # | 操作 | 方法 | 路径 | 优先级 | 分页 | 实时 | 说明 |
|---|---|---|---|---|---|---|---|
| 63 | KPI | GET | `/rules/metrics` | P1 | ○ | |
| 64 | 规则列表（6 分类/筛选） | GET | `/rules` | **P0** | ◐ | |
| 65 | 规则详情（参数/动作/版本/边缘） | GET | `/rules/{id}` | **P0** | ○ | params 随类型不同 |
| 66 | **新建规则** | POST | `/rules` | **P0** | | 草稿/直接提交评审；highRisk 前端有二次确认 |
| 67 | **编辑/新建版本** | PUT | `/rules/{id}` | **P0** | | asNewVersion 时版本号 +0.1 |
| 68 | **提交评审（写）** | POST | `/rules/{id}/submit` | **P0** | |
| 69 | 批准（写） | POST | `/rules/{id}/approve` | P1 | | 待评审→已批准 |
| 70 | 驳回（写） | POST | `/rules/{id}/reject` | P1 | | 退回草稿 |
| 71 | **发布+边缘同步（写）** | POST | `/rules/{id}/publish` | **P0** | ◆ | 返回逐节点同步结果 4/4 |
| 72 | 边缘重新下发 | POST | `/rules/{id}/redeliver` | P1 | |
| 73 | 回滚（生成新版本再走发布） | POST | `/rules/{id}/rollback` | P1 | | **不覆盖现版本**，生成 v(n+1) 且状态待评审 |
| 74 | 停用 | POST | `/rules/{id}/disable` | P1 | |
| 75 | 规则仿真（计算） | POST | `/rules/simulate` | P1 | | 输入距离/速度/方向/雷达质量/天气→等级+动作，后端做最简阈值判断即可 |
| 76 | 冲突检查 | POST | `/rules/conflict-check` | P1 | | 高危冲突不可忽略 |
| 77 | 版本历史 | GET | `/rules/{id}/versions` | P2 | ○ |
| 78 | 模拟版本异常 | POST | `/rules/{id}/simulate-mismatch` | P2 | | SIMULATED |

### 5.10 运维监控（`/operations`）

| # | 操作 | 方法 | 路径 | 优先级 | 分页 | 实时 | 说明 |
|---|---|---|---|---|---|---|---|
| 79 | 总览 KPI（7 项） | GET | `/ops/overview` | **P0** | ○ | ◆ |
| 80 | 云边端拓扑 | GET | `/ops/topology` | P1 | ○ | ◆ |
| 81 | 边缘节点列表 | GET | `/ops/edge-nodes` | **P0** | ○ | ◆ | CPU/内存/存储/缓存/版本/心跳/时间偏差 |
| 82 | 边缘节点详情 | GET | `/ops/edge-nodes/{id}` | **P0** | ○ | |
| 83 | 感知设备健康（5 类） | GET | `/ops/devices` | P1 | ○ | ◆ |
| 84 | 设备重连/诊断（写） | POST | `/ops/devices/{id}/reconnect` | P1 | |
| 85 | 关键接口监控 | GET | `/ops/interfaces` | P1 | ○ | ◆ | 延迟/成功率/错误次数 |
| 86 | 运维异常 Feed | GET | `/ops/events` | P1 | ◐ | ◆ |
| 87 | 节点维护（重连/校时/重下发） | POST | `/ops/edge-nodes/{id}/maintain` | P2 | | 日志下载继续 Mock |
| 88 | 模拟设备离线/缓存/时间偏差 | POST | `/ops/simulate` | P2 | | SIMULATED |

### 5.11 云边断网自治

| # | 操作 | 方法 | 路径 | 优先级 | 分页 | 实时 | 说明 |
|---|---|---|---|---|---|---|---|
| 89 | 云边链路全局状态 | GET | `/edge/link` | P1 | ○ | ◆ | Header/大屏/运维三处共享，需要权威状态 |
| 90 | 断网/恢复演练开关 | POST | `/edge/simulate-link` | P2 | | SIMULATED 演练 |
| 91 | 待补传本地事件队列 | GET | `/edge/local-events` | P1 | ◐ | ◆ | 含 dedupKey、缓存容量、最早时间 |
| 92 | 边缘事件补传上报 | POST | `/edge/local-events` | P2 | ◆ | 真实边缘上报后续做，先占位 |
| 93 | 恢复流程（补传/去重/对账编排） | POST | `/edge/recover` | P1 | ◆ | 返回分步状态 |
| 94 | 规则版本对账 | GET | `/edge/reconcile/rules` | P1 | ○ | 平台 vs EDGE-01~04 |
| 95 | 对账后重下发/保持 | POST | `/edge/reconcile/rules` | P1 | |
| 96 | 时间同步校时 | POST | `/edge/reconcile/time` | P2 | | +3.8s→32ms |

### 5.12 安全大屏（`/safety-screen`，只读展示端）

| # | 操作 | 方法 | 路径 | 优先级 | 分页 | 实时 | 说明 |
|---|---|---|---|---|---|---|---|
| 97 | 大屏首屏聚合 | GET | `/screen/overview` | **P0** | ○ | 6 指标+设备健康+风险类型+24h 趋势；尽量复用 overview/analytics |
| 98 | 大屏地图 | GET | `/screen/map` | **P0** | ○ | ◆ 同 `/overview/map` |
| 99 | 实时事件流 | GET | `/screen/alerts/feed` | P1 | ○ | ◆ 优先 WS |
| 100 | 当前紧急事件（弹窗） | GET | `/screen/critical` | P1 | ○ | ◆ |
| 101 | 紧急事件确认已读（写） | POST | `/screen/critical/{id}/ack` | P1 | | 大屏**唯一**写操作；无停机/关单/改规则 |
| 102 | 模拟人员越界 | POST | `/screen/simulate-breach` | P2 | ◆ | SIMULATED，需同步推送到移动端 |

### 5.13 移动端告警处置（`/mobile/*`）

| # | 操作 | 方法 | 路径 | 优先级 | 分页 | 实时 | 说明 |
|---|---|---|---|---|---|---|---|
| 103 | 首页（人设+4 统计+待办） | GET | `/mobile/home` | **P0** | ○ |
| 104 | 告警列表（全部/待处理/处置中） | GET | `/mobile/incidents` | **P0** | ○ | ◆ |
| 105 | 告警详情（简化地图+证据+时间线+联动） | GET | `/mobile/incidents/{id}` | **P0** | ○ |
| 106 | **接单（写）** | POST | `/mobile/incidents/{id}/accept` | **P0** | | 待接单→已接单 |
| 107 | **确认到场（写）** | POST | `/mobile/incidents/{id}/arrive` | **P0** | | 已接单→已到场 |
| 108 | **开始处理（写）** | POST | `/mobile/incidents/{id}/start` | **P0** | | 已到场→处理中 |
| 109 | **提交处置（写，含措施/照片/风险解除）** | POST | `/mobile/incidents/{id}/treatment` | **P0** | | 处理中→待复核；Demo 照片可收 dataURL |
| 110 | 复核关闭（演示） | POST | `/mobile/incidents/{id}/review-close` | P1 | |
| 111 | **事件升级（写）** | POST | `/mobile/incidents/{id}/escalate` | **P0** | | 升紧急时大屏弹窗 |
| 112 | 请求紧急联动 | POST | `/mobile/incidents/{id}/request-linkage` | P1 | ◆ 只读请求，不直接控设备 |
| 113 | 现场照片上传 | POST | `/mobile/incidents/{id}/photos` | P1 | | SIMULATED，可继续本地 dataURL |
| 114 | 离线操作批量同步 | POST | `/mobile/sync` | P2 | | SIMULATED，不做 PWA |

### 5.14 实时通道

| # | 操作 | 方法 | 路径 | 优先级 | 说明 |
|---|---|---|---|---|---|
| 115 | 实时推送 | **WS** | `/ws/live` | P1 | 前端客户端已就绪（心跳/重连）。Demo 最简实现：1~3s 定时广播快照或增量（`alert.new`/`alert.changed`/`person.moved`/`ai.new`/`ops.event`），是大屏-移动端联动、实时告警流、位置移动的关键 |
| 116 | 初始全量快照 | GET | `/state` | P1 | safety store 已在调用，失败回退 Mock；返回 `SafetySnapshot` |

---

## 6. 实时数据需求汇总

| 实时对象 | 消费页面 | 刷新诉求 | Demo 建议方式 |
|---|---|---|---|
| 人员位置 | 人员定位、首页地图、大屏地图 | 2~3 秒 | WS 增量 `person.moved`，或 `/personnel/live` 轮询 |
| 设备距离/速度/趋势 | 设备防碰撞 | 亚秒~秒级（演示 0.85s 步进） | 后端 Simulator 定时推 `/collision/pair` |
| 新告警/状态变化 | 首页、告警中心、大屏、移动端 | 即时 | **WS 增量推送（首选）**，写接口返回后广播给所有连接 |
| AI 新事件 | AI 页、大屏 | 事件驱动 | `ai.new`（Demo 由模拟器产生） |
| 边缘节点/接口/设备健康 | 运维、大屏 | 3~5 秒 | `/ops/*` 轮询或 WS |
| 云边链路状态 | Header、运维、大屏 | 状态变化即推 | `/edge/link` + WS |
| 紧急事件弹窗 | 大屏 | 即时且不丢 | WS + 进入页面时 `GET /screen/critical` 补拉 |

---

## 7. 优先级划分（P0/P1/P2）

- **P0（48 个，Demo 跑通必须）**：当前用户；首页指标+地图；人员列表/详情；围栏列表/详情/新建/编辑/发布；设备列表/详情/人工接管；AI 列表/详情/确认违规/误报/派单；告警 metrics/列表/详情 + 确认/派单/开始/处置/复核/接管共 8 个写操作；分析 dataset+明细；规则列表/详情/新建/编辑/提交/发布；运维 KPI/边缘节点列表详情；大屏 overview/map；移动端首页/列表/详情 + 接单/到场/开始/处置/升级。
- **P1（50 个，增强演示完整度）**：登录、字典、轨迹、各类趋势/聚合图表、审批/回滚/仿真/冲突、接口与设备健康、云边恢复对账、WS 实时通道、`/state` 快照、转派/驳回/升级/联动、移动端复核/照片/联动等。
- **P2（18 个，可继续 Mock）**：全部"模拟 xxx"演示器、版本历史、日志下载、离线批量同步、边缘真实补传占位等。

> 数量以 JSON 清单为准：P0 = 48，P1 = 50，P2 = 18，合计 116。

---

## 8. 建议继续 Mock / SIMULATED 的能力（不要阻塞 Backend Demo）

以下能力**硬件/算法依赖强**，Backend Demo 阶段一律标记 `SIMULATED`，由后端内置一个简单 Simulator（定时改数据/产生事件）或继续前端 Mock 即可：

1. **真实人员实时定位（UWB/基站解算）**——位置坐标由模拟器沿轨迹移动。
2. **真实毫米波雷达测距/相对速度**——距离曲线用脚本生成。
3. **真实 AI 推理（YOLO/模型推理/视频流/RTSP/摄像头接入）**——AI 事件、检测框、置信度、截图均用预置数据；摄像头异常用状态模拟。
4. **真实 PLC 控制与回执**——减速/停机/回执用状态机 + 定时器模拟（含超时失败分支）。
5. **真实边缘网关通信、边缘自治硬件**——断网/补传/对账是流程演示，用接口编排模拟。
6. **真实声光/手环/短信/电话/企业微信通知**——只返回"已发送"结果。
7. **真实文件/照片上传服务**——Demo 可收 base64/dataURL 或直接前端本地预览。
8. **大屏越界、AI 新事件、风险突增、设备离线、缓存告警等演示器**——保留。
9. **真实审批流引擎**——状态字段流转即可。

---

## 9. 前端当前字段 / 状态 / 命名问题（**只记录，不在本阶段修改**，留待领域设计）

1. **同名 `AiEvent` 两套结构**：`types/safety.ts`（status 为英文 `pending/confirmed/...`）与 `types/ai.ts`（status 为中文 7 态 `ReviewStatus`）字段完全不同，后端落地前必须统一。
2. **风险等级枚举至少 5 套**：`提示/一般/严重/紧急`（safety）、`一般/预警/严重/紧急`（alert/rule）、`紧急/严重/一般`（overview）、`高/中/低`（ai）、`安全/预警/严重/紧急/待确认`（collision）。
3. **事件状态机三套并行**：告警中心 `AlertStatus`（待确认→…→已关闭，8 态）、移动端 `IncidentStatus`（待接单→已接单→已到场→处理中→待复核→已关闭，6 态）、`safety.Alarm.status` 为自由 string；同一业务事件在管理端与移动端状态口径不同。
4. **ID 前缀不统一**：告警 `ALM-20260904-001` vs 首页 `alert-001` vs 移动端 `INC-20260904-001`；AI 事件 `AI-E-20260903-xxx` vs 快照 `AI-20260826-031`；设备 `VEH-07/TIP-02/CRANE-01` vs 快照 `DEV-TRUCK-07` vs 首页 `E-CRANE/7#转运车辆`；人员内部 id `P-ZHAO/P-002`、工号 `P-24018`、移动端展示 `P-1003` 三套并存。
5. **时间格式混用**：`HH:mm:ss`、`YYYY-MM-DD HH:mm`、相对时间（刚刚/3 秒前）并存；部分页面筛选靠字符串比较时间，跨天会错。后端应统一返回 ISO8601 并由前端格式化。
6. **坐标双轨**：地图使用百分比 `x/y(0~100)` 渲染，人员详情另有米制字符串 `coordinate: 'A-03 / 112.4, 86.7'`，无换算关系。
7. **联动步骤结构三套**：`collision.LinkageState(waiting/running/success/failed)`、`alert.LinkageStepState(wait/running/success/failed)`、移动端 `idle/running/done`。
8. **时间线节点两套**：首页 `{time,title,detail}` vs 告警/AI/移动端 `{time,text,state}`。
9. **SLA 口径**：前端每秒本地 `slaRemainingSec--`，刷新即重置；后端应返回 `slaDeadline` 权威时间。
10. **同一实体跨模块口径漂移**：CAM-03 在 AI 模块分属装卸区 A/B；赵磊在不同页面 id/工号不一致；EDGE-01~04 的规则版本在规则/围栏/运维三处各自维护。
11. **快照模型与页面模型双轨**：`SafetySnapshot` 一套实体，各页面又各有一套实体（Person vs PersonnelPoint/PersonnelRecord；Fence vs FenceRecord；Device vs CollisionEquipment…）。
12. **AI 事件与告警的领域关系缺失**：AI 确认违规后是否自动生成 Alert、移动端 Incident 与 Alert 是否同一聚合，当前无链路（前端各自独立列表）。
13. **证据模型仅告警侧存在**：`AlertEvidence` 五联合类型只在告警/移动端使用，首页快照内 Alarm 无证据字段。
14. **分页约定缺失**：当前所有列表一次性返回，后端落地需要统一分页/总数契约（见第 10 节）。

---

## 10. 轻量基础 API 规范建议（够用即可，不做企业标准）

1. **BasePath**：统一 `/api/v1`；REST 风格路径，资源用复数名词（`/alerts`、`/ai-events`），动作用子路径（`/{id}/confirm`）。
2. **ID**：一律字符串；建议后端统一发号（如 `ALM-yyyyMMdd-序号`），Demo 可沿用现有前缀；写操作请求体不要求前端生成 id（新建除外）。
3. **时间**：请求/响应统一 ISO8601 字符串（`2026-09-04T22:14:08+08:00`）；时长统一秒（number），前端负责 `2m18s` 等展示格式化。
4. **统一成功响应**（前端 `apiRequest` 当前直接取 body，建议包一层且前端做一次适配）：
   ```json
   { "code": 0, "message": "ok", "data": { }, "traceId": "uuid" }
   ```
5. **统一分页响应**：`{ "list": [], "total": 0, "page": 1, "pageSize": 20 }`；请求参数 `page/pageSize`，筛选参数平铺在 query。
6. **统一错误响应**：`{ "code": 40901, "message": "当前状态不允许该操作", "traceId": "uuid", "details": {} }`；HTTP 状态码语义化（400 参数/401 未认证/404 不存在/409 状态冲突/500 服务异常）。
7. **字段命名**：camelCase（与现有 TS 类型一致）；布尔用 `is/has/enabled/online` 等肯定式；枚举值先用前端现有中文字符串（如状态"待确认"），**不在 Demo 阶段改枚举**，降低前端改造量。
8. **写操作**：统一 POST；返回更新后的对象（或最新列表/快照），便于前端直接替换本地状态；支持前端已在发的 `Idempotency-Key` 做幂等。
9. **WS**：文本 JSON；快照消息必须带 `schemaVersion`（前端靠它识别）；增量消息统一 `{ "type": "xxx", "data": {} }`；忽略无法识别的 type。
10. **空值与缺省**：字段缺失时前端按"无数据"渲染，后端可只返回必要字段，但字段名必须与清单一致。

---

## 11. Backend Demo 推荐开发顺序

按"每一步都能在现有前端看到页面活起来"的原则：

1. **第 1 步 · 骨架（0.5 步）**：工程骨架 + 统一响应/错误/分页 + `/auth/me`（固定返回李娜/王建国两套身份可切换）+ `/meta/dictionaries` + CORS。
2. **第 2 步 · 告警处置闭环（最高价值）**：`/alerts`（metrics/list/detail）+ 8 个写操作 + 状态机；它同时是移动端和大屏的数据源。
3. **第 3 步 · 移动端 + 大屏联动**：`/mobile/*` 与 `/screen/*`（可直接复用告警数据做视图），**实现 `/ws/live` 最简广播**，打通"大屏出事件→移动端接单处置→大屏状态同步"。
4. **第 4 步 · AI 违规**：`/ai-events` 列表/详情 + 确认/误报/派单写操作 + 一个定时产生 AI 事件的 Simulator。
5. **第 5 步 · 首页态势**：`/overview/*` + `/state` 聚合快照，替换首页内联 Mock 与 safety store fallback。
6. **第 6 步 · 人员 / 围栏 / 设备**：只读列表详情先行，再补围栏发布、人工接管等写操作；位置/距离由 Simulator 推。
7. **第 7 步 · 规则配置**：规则 CRUD + 状态流转（提交/批准/发布/回滚）+ 最简仿真计算与冲突检查。
8. **第 8 步 · 统计分析**：`/analytics/dataset` 聚合接口（可直接对告警表 group by）+ 明细分页。
9. **第 9 步 · 运维 + 云边自治**：`/ops/*` 只读监控 + 断网/恢复/补传/对账流程接口编排。
10. **第 10 步 · 收尾**：P2 演示器按需由后端 Simulator 接管；前端逐模块删 Mock；第 9 节的字段/状态问题统一留到 Demo 跑通后做领域设计，不在过程中重构。

---

## 12. 本阶段未做事项（按要求明确）

未创建后端工程、数据库、SQL、Controller、Swagger；未重构前端；未统一领域模型与正式状态机；未做最终 ER/DDD/微服务设计。全部产出仅为本文档与配套 JSON 接口清单。
