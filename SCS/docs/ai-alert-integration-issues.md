# AI 事件 / Alert / Overview 前后端联调问题记录（Stage 5）

> 阶段：第五阶段「AI 违规识别后端化 + 安全态势首页后端化」
> 原则：本阶段不做最终领域模型重构，只记录 AIEvent、Alert、Overview 三者在 Demo 阶段的字段/状态差异、当前处理方式与正式领域设计建议。
> 日期：2026-09-05

## 1. 问题清单

| # | 问题 | 前端当前定义 | 后端当前定义 | 影响 | 当前 Demo 处理 | 正式领域设计建议 |
|---|---|---|---|---|---|---|
| 1 | AIEvent 与 Alert 是否同一对象 | 前端 `types/ai.ts` 的 `AiEvent` 与 `types/alert.ts` 的 `AlertEvent` 完全独立 | 后端 `module/ai/DemoAiEvent` 与 `module/alert/DemoAlert` 独立存储，仅以 `linkedAlertId` 关联 | AI 确认违规后会出现两条记录，需要明确主从关系 | AI 负责识别/证据/置信度/复核；仅「确认违规 / 派单」时经 `AlertService.createFromAi` 创建 Alert，AI 保存 `linkedAlertId`，处置主链全部走 Alert | 正式建模为「检测事件（Detection Event）→ 安全事件（Safety Incident/Alert）」的一对多/多对一关系，AI、雷达、围栏均为检测来源，Alert 为唯一处置根 |
| 2 | AI 模型版本 vs 规则版本 | AiEvent.model 为模型名（如 `PPE-Detection-v2.4.1`）；Alert.ruleId/ruleVersion 为规则编号/版本 | `createFromAi` 入参含 ruleId/ruleVersion，但 AI 侧没有规则版本概念，当前传 `null` | 由 AI 生成的 Alert 在「规则信息」处为空 | AI 生成的 Alert `ruleId/ruleVersion = null`，前端规则区域显示「—」；模型版本保留在 AIEvent | 建立「检测模型版本」与「判定规则版本」两条独立血缘，规则中心为 AI 识别策略分配规则号后再回填 |
| 3 | 风险等级口径不一致 | AiEvent.risk 为「高/中/低」；Alert.risk 为「一般/预警/严重/紧急」 | 后端 `mapAlertRisk`：未佩戴安全帽/翻越护栏/闯入→严重，人员滞留→预警，摄像头异常→一般 | AI 卡片与生成的 Alert 风险标签字面不同（高风险 AI 事件 → 严重 Alert） | 后端集中映射；AI 事件本身保留高/中/低，生成 Alert 时统一为四级风险 | 正式统一风险枚举与「检测置信度→风险等级」的可配置映射策略 |
| 4 | 已关联 Alert 后再标记误报 | 前端复核动作可在任意状态触发 | 后端 false-positive 允许 [待复核, 不确定]，已确认/已派单链路不会再出现误报入口 | 若已生成 Alert 又误报，Alert 不会自动撤销 | Demo 阶段避免构造该路径（状态机不暴露入口）；后端不自动删除已建 Alert | 正式设计「事件撤回 / Alert 作废」流程，带审计与三端同步广播 |
| 5 | AI close 不联动关闭 Alert | AI 状态含「已关闭」；Alert 有独立关闭主链 | 后端 AI `close` 仅关闭 AIEvent（处理中→已关闭），不联动 Alert | AI 侧关闭后，关联 Alert 仍在告警主链 | 明确分工：AI 关闭只代表识别工单结束，安全处置以 Alert 关闭为准 | 正式设计父子级联关闭策略（父 Alert 关闭时子检测事件如何归档） |
| 6 | AI 时间线与 Alert 时间线结构相似但不同源 | AiTimelineNode{time,text,state}；Alert TimelineNode{time,at,text,state} | 两套独立 timeline，写操作各自追加 | 同一业务动作（派单）在两处各有一条记录 | 后端 AI 写操作追加 AI timeline；联动到 Alert 的动作（confirm/assign）同时追加 Alert timeline | 正式统一为领域事件流（Domain Event Stream），各视图按事件类型投影 |
| 7 | Overview 聚合口径 | 首页原先写死 5 张卡、7 天趋势、风险分布 | `SafetyProjectionService` 统一聚合 AlertRepository + AiEventRepository；Screen 与 Overview 共用 | 大屏与首页必须口径一致 | 抽取共享 `SafetyProjectionService`，ScreenService 委托同一实现，避免两套统计 | 正式由分析服务/数仓统一提供聚合 API，前端只读投影 |
| 8 | 首页地图数据真实度分层 | 前端原写死人员/设备/围栏坐标 | 后端 `BASE DEMO MAP DATA`（坐标台账）+ `LIVE RISK OVERLAY`（真实未关闭 Alert 覆盖风险） | 坐标并非真实定位，但风险必须真实 | DTO 中 `baseDemo=true`、点上 `liveOverlay` 标记；人员/设备风险由未关闭高风险 Alert 动态覆盖，Alert 关闭即恢复 | 接入人员定位/设备服务后替换底图为实时坐标，overlay 逻辑保持不变 |
| 9 | 风险趋势为 Demo 历史样本 | 前端原写死 7 天数组 | InMemory 种子仅覆盖当日，无法还原 7 天 | 趋势图无真实历史可聚合 | 后端补固定 7 天样本曲线并标记 `demo=true`；当日真实事件聚合后替换 | 时序数据正式写入 openGemini，趋势由时序库聚合 |
| 10 | AI 摄像头健康 | CameraHealth 三态，前端模拟按钮本地改状态 | 后端 `GET /cameras` Demo 台账 + `simulate(camera-fault)` 生成异常事件 | 摄像头状态非真实设备上报 | 8 路摄像头 Demo 台账，异常经模拟事件体现；不接视频流 | 接入设备健康上报（运维模块）后替换为实时状态 |
| 11 | WebSocket 事件命名空间扩展 | `isLiveEvent` 原只放行 `alert.*` | 后端新增 `ai.new / ai.changed / ai.reviewed` | AI 页面无法收到实时事件 | 前端白名单扩展为 `alert.* / ai.* / system.*`；WS 只传轻量摘要，收到后 REST 重拉 | 正式建立统一事件目录（schema registry）与版本号 |
| 12 | 幂等与「重复确认不重复建 Alert」 | 前端每次写操作带 Idempotency-Key | 后端 confirm 内判断 `linkedAlertId == null` 才建 Alert；状态机 + 幂等双重防护 | 网络重试可能导致重复 Alert | ① 同 key 回放由 IdempotencyService 拦截；② 已确认状态再次 confirm 被 409 拦截；③ linkedAlertId 判空兜底，三层保证只建一个 | 正式以检测事件唯一 ID + 幂等表保证，Alert 创建走唯一约束 |
| 13 | 演示风险开关的持久形态 | 前端原本地 toggle 赵磊变红 | 后端固定 `ALM-DEMO-RISK`：active 建紧急 Alert，关闭即 review 关闭并广播 | 演示数据进入真实存储 | `POST /overview/simulate-risk`，五端经 WS 同步；重启后恢复种子 | 正式环境移除该演示端点 |
| 14 | 检测框分数可空 | DetectionBox.score 前端为可选 | 后端 AiBox.score 为 Double（区域框 FENCE LINE 无分数） | 后端拆箱曾 NPE（已修复） | 后端 `score==null ? 0 : score` 防护；前端 adapter 对无 score 框不透出分数字段 | 正式检测框 schema 显式区分「目标框（带置信度）」与「区域框（无置信度）」 |

## 2. 当前 Demo 数据边界（明确标注）

### 真实来自后端（权威数据）
- AI 事件列表、详情、指标、筛选分页、Timeline、复核状态、`linkedAlertId`
- AI 确认违规 → 创建 Alert、误报不建 Alert、派单/处理/关闭状态流转
- 首页 summary 的活动/处理中/紧急/严重告警数、AI 待复核数
- 首页实时告警 Feed、风险类型分布、风险趋势（趋势曲线为 Demo 样本，结构真实）
- 首页地图的人员/设备**风险覆盖状态**（由未关闭高风险 Alert 驱动）
- 三端 WebSocket 实时同步（alert.* / ai.*）

### 仍为 Demo / 模拟（SIMULATED）
- 人员/设备/围栏基础坐标台账（BASE DEMO MAP DATA）
- 在岗人员数、设备在线数、高频风险设备数（常量台账，DTO 带 demo 标记）
- 设备健康列表（摄像头/雷达/基站在线数）
- 7 天历史趋势样本曲线
- 摄像头视频流、AI 模型推理、检测截图场景图
- 照片、PLC、雷达、真实定位等硬件链路

## 3. 后续正式设计待办（不在本阶段处理）

1. Detection Event（AI/雷达/围栏多源检测）与 Safety Alert 的正式领域关系与一对多映射。
2. 统一风险等级枚举与「置信度 → 风险等级」可配置映射。
3. AI 识别策略在规则中心登记 ruleId/ruleVersion，打通模型版本与规则版本血缘。
4. 已生成 Alert 的检测事件被撤销（误报）时的 Alert 作废/级联关闭流程与审计。
5. 统一领域事件流，替代当前 AI / Alert 两套 Timeline。
6. openGemini 承载历史趋势，替换 Demo 样本曲线。
7. 人员定位、设备服务接入后替换首页/大屏地图底图，LIVE RISK OVERLAY 逻辑保留。
8. 多实例 WebSocket 广播经 RocketMQ 收敛（当前为单实例 SessionRegistry）。
