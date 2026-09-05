# 人员 / 围栏 / 设备防碰撞后端化 —— 领域集成问题记录（Backend Demo）

> 阶段：第六阶段（人员定位、电子围栏、设备防碰撞后端化 + 前后端联调）
> 范围：仅记录当前 Demo 中 **Personnel / Fence / Collision 与 Alert 之间的字段、状态、概念差异**，本阶段不做正式领域重构，只做最小可跑通的 Demo 处理；每条均给出后续正式领域设计建议。
> 技术形态：Spring Boot 3.5.5 模块化单体、InMemory Repository、REST 为权威读取、`/ws/live` 只发变化摘要。

## 0. 当前 Demo 的统一关系

```
Personnel（感知）  Fence（判定边界）  Collision（感知/判定）
        └────────────┬───────────────────┘
                 AlertService.createRiskAlert
                          ↓
                       Alert（唯一安全处置事件源）
                ┌───────────┼────────────┐
            管理端 /alarms  移动端 /mobile  安全大屏 /safety-screen
```

- Personnel / Fence / Collision **不持有独立告警 Repository**，风险统一进入 Alert 主链。
- 去重键：人员越界 `PERSON_INTRUSION:{personnelId}:{fenceId}`；碰撞 `COLLISION:{currentId}:{relatedId}`。
- 未关闭风险命中同一去重键时复用已有 Alert；碰撞从严重升级到紧急时 `upgradeRisk` 升级**同一条** Alert，不新建。

## 1. 风险等级词表不统一

| 维度 | 当前取值 | 问题 | Demo 处理 | 影响 | 正式设计建议 |
|---|---|---|---|---|---|
| Alert.risk | 一般 / 预警 / 严重 / 紧急 | 四档 | 作为统一对外等级 | — | 保留四档主等级 |
| Personnel.risk | 正常 / 关注 / 高风险 | 三档，与 Alert 不同词表 | 人员自身只表达“感知状态”；建 Alert 时由围栏 `riskLevel` 映射到 Alert 四档 | 人员“高风险”与 Alert“紧急/严重”不是一一对应 | 建立统一 RiskLevel 枚举与“感知风险→处置等级”的显式映射表，前端 RiskBadge 只认一套 |
| Collision.risk | 安全 / 预警 / 严重 / 紧急 / 待确认 | 多了“安全”“待确认”两个过程态 | “待确认”用于雷达断数，不建 Alert；安全/预警不建单，严重首建、紧急升级 | “待确认”既是设备健康态又被放进 risk 字段，语义混合 | 拆分 DeviceHealthState 与 RiskLevel，距离判定输出 RiskLevel，传感器可用性单独建模 |

## 2. 人员状态（state）与在线状态（status）混用

- 现状：DemoPersonnel 同时有 `status（在线/离线）`、`state（normal/warning/danger/offline）`、`risk（正常/关注/高风险）`、`braceletStatus（在线/离线/低电量）`，四者存在信息重叠（例如离线时 status=离线、state=offline、braceletStatus=离线）。
- Demo 处理：以 `state` 供地图着色，`status` 供列表统计，`braceletStatus` 供手环筛选，未做一致性约束，异常模拟时四个字段一起改。
- 影响：前端需要多字段判断“这个人到底怎么了”，统计口径（在线/异常/手环离线/低电量）可能交叉计数。
- 正式建议：人员主数据（在岗、班组、手环硬件）与实时态势（位置、定位质量、风险标签）分离；风险标签改为可叠加的多值集合而非单一 state。

## 3. 围栏版本与“规则版本”的关系未定义

- 现状：Fence 有自己的 `version（v3.2）`；人员越界建 Alert 时把围栏 version 写进 Alert 的 `ruleVersion`、`ruleId=RULE-PER-001` 固定占位。围栏状态机为 草稿/待评审/待发布/已生效/已停用/版本不一致，与后续“规则配置中心”的规则状态机高度相似但不是同一套。
- Demo 处理：围栏编辑已生效对象会回退“待评审”并 minor bump；EDGE 同步状态（pending/syncing/success/failed）仅为节点数组模拟；`simulate-mismatch / redeliver` 手工制造与修复版本不一致。
- 影响：同一“边界规则”可能在围栏模块与规则模块各有一份版本，正式阶段会出现到底谁是规则源的问题。
- 正式建议：围栏只保留几何与适用范围（GIS 边界资产），“进入围栏触发什么等级/联动”收敛到规则中心；Alert 只引用规则发布版本号，不引用围栏自身 version。

## 4. Collision 距离阈值与正式安全距离标准

- Demo 处理：阈值固定为 `≥10 安全 / [6,10) 预警 / [3,6) 严重 / <3 紧急`，接近序列 `[9.2,7.1,5.4,3.8,2.8]` 写死在后端；相对速度、制动距离、天气修正本阶段未参与计算（前端原 Mock 提到修正但后端只保留 relSpeed/radarQuality 展示字段）。
- 影响：阈值不随设备类型、载重、速度动态变化，不能作为真实制动依据。
- 正式建议：阈值迁入规则配置，按设备类型/方向/速度计算 TTC（碰撞时间）与制动距离，雷达质量参与置信度而非直接给等级。

## 5. Collision Alert 去重与升级的生命周期边界

- 现状：去重键 `COLLISION:a:b` 只在“存在未关闭告警”时生效；风险回落到安全不会自动关闭 Alert（解除走 reset，仅清配对状态）。严重→紧急复用同一 Alert 并记录 `upgradedFrom`；紧急回落严重不回退等级。
- Demo 处理：一次完整演示为 严重建单 → 紧急升级同单 → 联动/人工接管 → 在告警中心走处置闭环关闭。
- 影响：真实场景中“同一对设备反复接近—远离”会产生多条 Alert（前一条关闭后再次接近重新建单），这是预期但缺少“重复风险事件”聚合。
- 正式建议：为风险会话（risk episode）分配 episodeId，一次持续接近周期内的升降级挂在同一 episode，关闭与再开由滞回阈值与人工处置共同决定。

## 6. 联动（Linkage）状态归属

- 现状：Collision 配对内有 `steps（六步）`，Alert 也有 linkage 状态；`/collision/linkage` 与 `/collision/simulate linkageFail` 内部调用 `AlertService.linkage`，以 Alert 侧为权威，Collision 配对只缓存最近一次步骤用于页面回显。
- Demo 处理：PLC 失败同时体现在 Collision 配对（controlFailure/plcStatus）与 Alert（linkageFailed、Timeline）；人工接管统一走 Alert.takeover。
- 影响：当前两处状态靠“同一 AlertId”手工对齐，若只刷新配对不刷新 Alert 可能短暂不一致。
- 正式建议：联动执行记录建模为独立实体，挂在 Alert 聚合下，设备侧只持有“当前活跃联动指令引用”，不复制步骤状态。

## 7. 坐标体系：百分比 Demo 坐标 vs 正式 GIS

- 现状：人员、设备、围栏 polygon 全部使用 0–100 百分比画布坐标，Point-In-Polygon 用二维射线法（`Polygon2D`），围栏内部点用包围盒中心 + 网格兜底。
- Demo 处理：满足“人员进入围栏命中→建单”的演示；`/overview/map` 与大屏地图直接消费同一投影。
- 影响：无法表达真实经纬度/米制距离、多层站场、高度。
- 正式建议：统一到工程坐标系（或经纬度 + 局部仿射变换），PIP 与距离计算在服务端用真实坐标，百分比坐标只作为前端渲染投影。

## 8. InMemory 存储与重启语义

- 现状：Personnel/Fence/Collision/AI/Alert 全部 InMemory，Repository 均为接口 + InMemory 实现，带 `reset()` 供测试隔离；进程重启恢复种子数据。
- 影响：演示中断/重启后状态丢失，多实例（node4/node5）状态不共享。
- 正式建议：下一阶段用 openGauss 实现各 Repository（接口已抽象，替换实现即可）；实时位置/配对瞬时态走 Kvrocks / openGemini，WebSocket 多实例广播走 RocketMQ（本阶段刻意未做）。

## 9. WebSocket 事件与 REST 的一致性约定（当前已确立，正式保留）

- `person.moved / fence.changed / collision.changed / collision.risk.changed / collision.linkage.changed` 只携带 `id + 摘要字段`，**不传完整对象/轨迹/证据**；客户端收到后按需 REST 重拉。
- 广播失败不回滚业务写操作（通知是 best-effort）。
- 正式建议：该原则保留；多实例时业务服务只发领域事件到 RocketMQ，由网关层 fan-out 到各实例 WebSocket 会话。

## 10. 暂不处理清单（本阶段明确遗留）

- 不建 PersonnelIncident / FenceAlert / CollisionIncident 第二套事件模型。
- 不做正式状态机、DDD 聚合划分、openGauss 表结构、RocketMQ 业务广播。
- 人员/设备基础坐标、轨迹样本、雷达数据、PLC、摄像头仍为 SIMULATED；真实接入时替换 Repository / Simulator，不改前端契约。
