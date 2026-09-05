# 前后端 mock 契约（内部对齐文档）

> **性质与裁决背景（2026-07-13，主控）**：CLAUDE.md / 团队约定里引用的"PRD 第 10 章 mock 契约"经核实不存在（PRD 第 9 章为业务视角接口需求、明确不定义 URL/字段；第 10 章为非功能需求）。裁决：**在知识库出现正式契约文档之前，以 PRD §5 各页面级字段表为契约的事实来源**，本文件是其 API 形态的内部落点，供 backend-dev / frontend-dev 对齐。与 PRD 冲突时以 PRD 为准；本文件是开发内部记录，不是需求文档，不写回知识库。

约定：响应统一走骨架的 `{code, msg, data}` 包裹；字段命名 camelCase；前端契约层位于 `web/src/api/*.js`，`useMock` 开关切换 mock/真实接口，后端实现按本文件字段形状交付。

## 1. 能源总览（驾驶舱）v0.1 ｜ REQ-057–062、019、029、039/040/042、053

**端点**：`GET /overview/summary`
**前端契约层**：`web/src/api/overview.js`（mock：`web/src/views/dashboard/mock.js`，种子 42）

请求参数：

| 参数 | 取值 | 说明 |
|---|---|---|
| timeRange | `today` / `week` / `month` / `custom` | **已废弃（2026-07-14 裁决）**：PRD §5.1 的 KPI 卡为固定口径（当日/当月），总览页无时间筛选语义（时间维度分析属 §5.4 统计分析页）。UI 已移除该筛选组；后端保留参数兼容但不影响任何数据 |
| zone | `ALL` / `A` / `B` | 装卸区（2 区口径，PRD §4 数据范围） |
| energyType | `ELEC` / `WATER` / `AIR` | 能源介质（PRD §5.1 当日总能耗卡"电/水/气切换"） |

响应 `data`（OverviewPayload）：

| 字段 | 类型 | 内容 | REQ |
|---|---|---|---|
| signature | object | 口径签名：`version, formulaVersion, priceVersion, baselineVersion, sigId, seed, generatedAt, coverage`（2026-07-13 对齐 mock 实际字段，QA-S5） | REQ-062 |
| demoState | object | 演示态声明：`enabled, hint, reqAnchor`（基线/EnPI 演示态开启标记） | REQ-029 |
| filters | object | 请求筛选参数回显：`timeRange, zone, energyType` | — |
| kpis | KpiCard[6] | 6 张 KPI 卡：`code, label, unit, value, tag, tagKind, delta{value,kind,label}, subLabel, reqAnchor`；code 枚举 `ENERGY_DAY / BASELINE_DEV / OPEN_ALARMS / COVERAGE / COST_MTD / SUGGESTIONS`。**spark[]/sparkKind 已移除（2026-07-14，审计 C1）**：PRD §5.1 KPI 表无此字段，后端从未产出数据，属 mock 时代形态占位 | REQ-058、053 |
| trend | object | 24h 负荷曲线：`hours[24], load[24], baselineHigh/Mid/Low[24], anomalies[{hourIndex,value,ruleId,note}], peak, valley, nowIndex, unit, samplingInterval, formulaVersion`（基线带 ±12%；**anomalies 仅承载 demo-R09 基线偏差点**——2026-07-13 裁决修正历史文案"尖峰挂 R03"，R05/R04 等其它规则事件不进 trend，在 alarms 呈现） | REQ-058、029 |
| topObjects | TopObject[5] | Top5 **成本口径**排行（跨介质按 ¥ 汇总，月度） | REQ-053/058 |
| topObjectsByEnergy | TopObject[5] | Top5 **能耗口径**排行（2026-07-14 增补：按当前所选 energyType 的月累计量排序，value 为量值、unit 跟随介质 kWh/m³；两口径的前五名可能不同，REQ-053"最大用能对象/最大成本对象"双定义） | REQ-053/058 |
| alarms | AlarmItem[] | 最新告警滚动（级别/合并/版本冻结口径） | REQ-039/040/042 |
| quality | object | 数据质量摘要 + 48 计量点信号阵列（12×4）。**口径注（2026-07-14 终稿，QA-S3）**：`coverage` = **当日累计口径**（DEMO_NOW 日 ∑合格样点/∑理论样点，合格=ok/late/est/fix，与 R11 告警同源）；`cells[48]` = 每点**最新读数快照**短码（阵列色块用）；`categories[]` = cells 分布计数；`total` = 点位数 48（不与 coverage 分母混用）；`signature.coverage` 同 `quality.coverage`。双口径独立展示不冲突 | REQ-019 |

字段明细以 `overview.js` 头注释 + `mock.js` 构造函数为可执行契约；本表变更需主控确认。

**FX-12/FX-13 口径注（2026-07-15 体检修复）**：
- `COST_MTD`：单档介质（水/压缩空气）单价挂 `tou_period='flat_only'`，成本三段统一回退该价（此前恒 0 为 bug）；REQ-062 签名引用的单价版本自此与实际计算一致。锚定值：2026-07 系统级 电 ¥59,501.99 / 气 ¥4,030.33 / 水 ¥1,313.12。
- `BASELINE_DEV`：基线按 zone 分 scope——ALL 读 `BASELINE-SYSTEM-ELEC-2026` 与 system 行偏差；A/B 读各自 `BASELINE-AREA-{A|B}-ELEC-2026` 分区基线真算偏差（废除"area 行复制 system 偏差"捷径，A/B/ALL 三值必须互异，锚定 07-12：A -25.34 / B -31.53 / 系统 -27.99）。R09 仍只挂 system 行，告警计数不受分区真算影响（复位后仍 R09=3、共 20 条）。水/气基线"不适用"维持 QA-#23 裁决不变。

**backend-dev 交付要求**：按上表形状实现 `/overview/summary`（聚合一次返回），数据来自 datagen 构造表；交付后通知 frontend-dev 把 `useMock` 翻为 false 联调。

## 全局约定：演示日锚定（2026-07-13 用户决策）

构造数据止于 2026-07-12，演示日的"当日/今日/now"口径**由后端配置 `DEMO_NOW` 锚定**（.env.dev 配置，如 `DEMO_NOW=2026-07-12 23:59:00`；未配置时回退 `max(e_raw_reading.sample_time)` 所在日）。所有"今日"类聚合窗口、趋势图 NOW 游标、告警"今日触发"计数均以此为准，不用 wall-clock。知识库构造规范不改（保持绝对日期与 INJ 叙事不变）；如 8 月彩排时客户在意日期新鲜度，再议改规范为相对日期生成。

## 全局约定：规则编号（2026-07-13 主控裁决）

代码、mock、告警数据中的规则编号**一律使用 PRD §7 demo 规则库 R01–R11**（该表每条已标注与需求基线附录 K.7 的映射，如 demo-R01 采集离线 = K.7-R02、demo-R11 覆盖率不足 = K.7-R04）；禁止直接引用 K.7 原编号，两套编号语义不同（K.7-R03=数据跳变 vs demo-R03=连续零值），混用必错。数据构造规范 INJ 表引用的即 demo 编号。

## 2. 原始数据与质量 v0.1 ｜ REQ-010–023（+062 签名）

**页面**：`/raw-quality`（第二幕，质量着色+补传差异化卖点）
**前端契约层**：`web/src/api/rawQuality.js`（mock：`web/src/views/raw-quality/mock.js`，种子 42）
**REQ 锚点**：REQ-010 采集字段 · 011 幂等键（采集点+采样时间）· 012 迟到/补采重算标记 · 013 采集任务状态 · **014 P0 断网缓存与补传可视** · 018 质量标记枚举 · 019 覆盖率 95/80 两档 · 020 重算任务 · 022 原始/换算/修正/估算对应 · 023 质量总览

### 2.1 `GET /raw-quality/summary`

请求参数：`pointId`（空=只返回点位树）、`timeStart/timeEnd`（ISO8601，默认近 24h）、`zone`（ALL/A/B）。

响应 `data`（RawQualityPayload）：

| 字段 | 内容 | REQ |
|---|---|---|
| signature | 口径签名（同总览结构） | REQ-062 |
| points | 采集点树 `{zone, deviceId, deviceName, pointId, pointName, energyType, unit, samplingInterval, status}`，status 按附录 C（待映射/启用/停用/维护中/已换表/已归档） | REQ-006/007 |
| selected | 选中点位详情 `{..., currentTaskState, coverageToday}` | REQ-013 |
| readings | 原始读数 `{ts, cumulative, delta, statusValue, unit, quality, sourceBatchId, ingestedAt, isBackfill, remark}`。**statusValue（2026-07-14 增补）**：状态类点位专用（running/standby/stopped/maintenance），计量类点位为 null；状态点的 cumulative/delta 为 null，前端按状态带渲染 | REQ-010/011/018/022 |
| coverage | `{pct, threshold(95/80), band: ok/degraded/insufficient, missingSlotCount}` | REQ-019 |
| tasks | 采集任务 `{taskId, taskName, dataSource, lastSuccessAt, lastFailureAt, lastError, failureBatchCount, currentState, affectedPoints[], samplingInterval, timeoutThreshold}` | REQ-013 |
| backfillBatches | 补传批次 `{batchId, triggeredBy, triggeredAt, cacheStart, cacheEnd, pointIds[], recordsIngested, dupHandledCount, failureReason?, status, affectedPeriods[]}` | REQ-014 |
| recomputeHints | 重算周期 `{periodKey, scope, status, triggeredBy, oldVersion, newVersion, diffSummary{metric,oldValue,newValue,deltaPct}, finishedAt?}` | REQ-012/020 |
| qualityBreakdown | 质量分布 `{quality, count, pct, sampleTs?}` | REQ-018/023 |

**quality 枚举**：按附录 C 八态定义 `ok/miss/late/dup/jump/est/fix/frozen`；demo 主动构造前 6 态，`frozen/dup` 保留字段定义不构造（裁决确认）。

### 2.2 `POST /raw-quality/backfill`（保留，裁决确认）

请求 `{pointId, cacheStart, cacheEnd, dryRun}`；响应 `data = {batch: BackfillBatch, derivedRecomputeHints: RecomputeHint[]}`（2026-07-13 对齐 mock 实际字段名：派生待重算为数组，含日+月两条）。演示态语义：同步返回成功；真接口后端可同样同步实现（构造数据量小）。**约束：写操作产生的状态变化必须可通过重跑 `datagen/generate_demo_data.py` 完整复位**（演示可重复性）。

### 2.3 `POST /raw-quality/recompute`（保留，裁决确认）

请求 `{periodKey, scope}`；响应：更新后 RecomputeHint（status=done，diffSummary 就位，版本号递增）。复位约束同 2.2。

### 演示叙事（INJ 落点）

- **INJ-01 主案例**：A 区 4 电表 07-06 09:20–13:40 断传（quality=miss、coverage=degraded、任务 failed）→ 补传（quality=fix + isBackfill、批次 succeeded）→ 重算（hint done，diffSummary 当日总能耗 12480→18320，+46.8%）。关联规则 demo-R01 采集离线。
- **INJ-02**：BC-A1 07-01 单点 jump（×100），排除正式统计。关联 demo-R04 数据跳变。
- **INJ-07**：WP-A1 07-05 覆盖率 70%，band=insufficient，明示"数据不足"。关联 demo-R11 覆盖率不足。

## 3. 第三幕：异常告警 + 设备能耗画像 v0.1 ｜ REQ-031–044

> 2026-07-14 主控裁决：第三幕必演范围是“异常告警 + 设备能耗画像”两页；作业对照嵌入画像页，本幕不另建完整作业归因页。页面直接调真实 API，禁止新建 mock 统计值、日期或对象数据。

### 3.1 异常告警

**页面**：`/energy/alert/alert-list`
**REQ 锚点**：REQ-039 三档级别·REQ-040 规则版本冻结·REQ-041 状态流转·REQ-042 合并次数·REQ-043 通知日志·REQ-044 统计复盘。

#### `GET /alerts`

查询参数：`level/status/ruleCode/zone/pageNum/pageSize`。

响应 `data`：

- `items[]`：`eventId, level, status, ruleCode, ruleName, ruleCategory, ruleVersion, object{type,id,code,name}, area{code,name}, firstOccurredAt, lastOccurredAt, occurCount, assignedTo`。
- `total`：筛选后总数。
- `statistics`：`total, closeRate, falsePositiveRate, averageHandleHours`，全部由事件表真实聚合。
- `filters`：筛选回显。

#### `GET /alerts/{eventId}`

响应 `data`：

- `event`：列表事件字段 + `closedAt, closeType, closeReason`。
- `ruleSnapshot`：从 `e_alert_rule_version` 按事件冻结版本读取 `ruleCode, ruleName, expression, thresholds, level, version, effectiveFrom`，不用当前规则覆盖历史。
- `curveSnapshot`：`unit, points[{ts,value}], window{start,end}, thresholdLine?`，按事件对象与触发窗从真实聚合/原始表重建。
- `workOrderComparison`：`matched, orders[]`；INJ-03 异常窗应为 `matched=false`。
- `transitions[]`：`fromStatus, toStatus, operator, remark, occurredAt`，来自 `e_alert_flow_log`。
- `notifications[]`：`channel, targetRole, sentAt, result, retryCount`，来自规则引擎/派发流程的真实记录。

#### `POST /alerts/{eventId}/transition`

请求：`{toStatus, assignedTo?, remark, closeType?, closeReason?}`。

- 法定链路：`new -> ack -> dispatched -> processing -> closed/false_closed/escalated`。
- 第三幕允许 `new -> dispatched` 的“确认并派发”demo 快捷动作；后端必须在同一事务内补写 `new -> ack` 和 `ack -> dispatched` 两条流转日志，不得跳过完整状态证据。
- 派发必填 `assignedTo`；关闭/误报关闭必填 `closeReason`；非法迁移返回 4xx，不落库。
- 响应为更新后的 `event` 和本次新增 `transitions[]`。所有写操作必须可由 datagen reset + pipeline bootstrap 复位。

### 3.2 设备能耗画像

**页面**：`/energy/analysis/profile`
**REQ 锚点**：REQ-033 状态与能耗叠加·REQ-034 设备画像/班次对比·REQ-035 低效证据，引用 REQ-031/032 工单和作业量。

#### `GET /equipment-profiles`

查询参数：`zone, energyType, periodStart, periodEnd`；默认以 `DEMO_NOW` 为右边界的最近 7 日。

响应 `data`：`signature, filters, equipments[]`，其中设备卡为 `equipmentId, equipmentCode, equipmentName, equipmentType, area, energyTypes, periodEnergy, ratedPowerKw, heatRatio, abnormalCount, quality`。`heatRatio` 由同筛选结果归一化计算，前端不写死档位阈值。

#### `GET /equipment-profiles/{equipmentId}`

查询参数：`energyType, periodStart, periodEnd, eventId?`；`equipmentId` 为设备编码（如 `GC-A1`）。

响应 `data`：

- `equipment`：台账字段 + 区域。
- `composition`：`workEnergy, standbyEnergy, stoppedEnergy, auxiliaryEnergy, totalEnergy`，按状态点与设备电表同时间窗真实对齐计算。
- `peak`：`loadKw, occurredAt`。
- `peerComparison[]`：同 `equipmentType` 设备的 `equipmentId, equipmentName, energy, ratioToAverage`。
- `stateEnergySeries`：`unit, points[{ts,powerKw,state,quality}], stateSegments[{start,end,state}], alertWindow?`。
- `meterPoints[]`：可下钻到第二幕的点位。
- `workOrderMatch`：`matched, orders[], uncoveredWindows[]`，覆盖窗与能耗时间轴必须同源。
- `shiftComparison[]`：至少两班次，字段 `shift, energy, workload, workloadUnit, unitEnergy, trial=true`；显式标注“试算”，不输出正式考核结论。
- `inefficiencyEvidence`：`detected, ruleCode, durationHours, actualEnergy, peerAverage, deviationRatio, workOrderMatched, reason`，INJ-03 必须展示“待机高功率 + 无工单”证据。
- `alerts[]/suggestions[]`：关联列表；第四幕未开发时建议为真实空态。
- `quality/signature`：质量摘要和口径签名。**口径注（2026-07-15 裁决）**：设备画像 `signature.baselineVersion` 格式为 `PROFILE-PEER-{窗口天数}D`（默认 7 天窗为 `PROFILE-PEER-7D`，亚天窗口向上取整），如实反映 `_peer_comparison` 实际查询窗；历史文案 `PROFILE-PEER-8W` 与实现不符已废弃。

### 3.3 第三幕数据和复位约束

- INJ-03 已有真实构造输入；`e_alert_event` 必须继续由规则引擎产生，禁止 seed。
- `e_equipment_profile` 是 backend 计算产物，禁止 datagen 写死结果；本幕允许画像 API 基于 raw/stat/status/work-order 表现查现算。
- 新增 `e_alert_flow_log(event_id, from_status, to_status, operator, remark, occur_time)` 作为 REQ-041 留痕的最小持久化结构，datagen reset 必须清空。
- 复位验收：`python datagen/generate_demo_data.py --reset` 后 `e_alert_event=0`；再执行 `POST /pipeline/bootstrap?force_republish=true` 后应恢复真实规则事件，其中 R05 只有 GC-A1 一条，异常窗无工单。

## 4. 第四幕范围裁决：INJ-05 / R08 支线口径（2026-07-15 主控裁决）

- **不新开发**：G.8 夜间照明为第四幕支线（范围切分文档裁决"注入数据即可顺带演示"），INJ-05 注入、R08 规则、告警呈现均已在前幕落地，第四幕不建页面、不接建议闭环、不改规则与数据。第四幕开发工单只围绕 G.6 建议闭环 REQ-045~050。
- **演示与 QA 口径（复位后实测锚定）**：告警页筛出 **1 条 R08 合并告警**——首次触发 2026-07-03 00:00、最近触发 2026-07-07 23:00、合并 16 次，触发记录覆盖 07-03、07-07 两个夜间异常窗口。这是引擎"同对象、同规则"聚合的正确形态；历史文案"两条告警"作废。
- **注入窗口口径注**：`_apply_inj_05` 按日历日注入（07-03、07-07 各自的 0–6 点 + 22–24 点），与数据构造规范"两晚 22:00–06:00"的跨日夜窗表述存在无害差异；QA 走查以上述预期值为准，勿再当 bug 报。

## 5. 第四幕：节能建议闭环 v0.1 ｜ PRD §5.8 · REQ-045–050

> 页面：`/energy/alert/suggestion`。主线为真实 R06 告警 → 转建议 → 派发人工巡检 → 执行留痕 → 四维验证 → 三档关闭 → 归档复盘。详情沿用第三幕抽屉模式，并通过 `suggestionId` query 恢复上下文。

### 5.1 全局边界

- 响应统一为 `{code, msg, data}`，字段 camelCase，写操作使用现有认证上下文中的用户，客户端不能伪造操作人。**422 双形状注（2026-07-15 体检确认）**：业务级校验失败（缺必填、状态门、窗口非法等）返回骨架包裹 `{code:422, msg,...}`；Pydantic 字段级校验失败（漏字段、类型错、extra_forbidden）返回 FastAPI 原生 `{detail:[...]}`，为骨架全局行为，各幕一致，前端 `requestError.js` 已兜住两种形状，不作为第四幕缺陷单独整改。
- **总览联动口径（FX-11）**：总览 KPI"待办节能建议" = 状态 ∈ {待审核, 已分派, 执行中}（PRD §5.1 表），验证中/延期/已关闭不计入；fresh fixture 下 KPI 值 = 3 且必须恒等于卡片分项之和。历史注释"REQ-045 四态"口径作废。
- `sourceType` 沿用现有枚举 `rule/manual`。R06 主线为 `rule`；历史存量建议只能是 `manual`，且 `sourceAlertId=null`。
- R06 建议实例绝不 seed，必须从真实告警幂等生成。
- R06 默认模板是 REQ-045 直接授权的配置数据，必须随建议域结构 seed，不依赖 8 条历史建议的知识库规格。
- R08 按 §4 范围裁决不新增模板、转换入口、API 特判或建议数据；服务端转换范围只包含已有真算路径的 R06。即使运行期误建并启用 R08 模板，告警详情仍必须返回不可转换，转换写接口返回 422 且不得写入建议、flow 或 verification；前端只消费 `canConvertToSuggestion`，不得按规则号特判。
- 所有运行期创建、流转、验证和关闭时间锚定 `DEMO_NOW`，禁止使用 wall-clock 或等待定时任务推进验证期。
- **REQ-091/096 禁令**：请求、响应、模板、页面和导出不得出现设备启停、远程关阀、功率/设定值调节、自动控制或下发指令；建议动作只能是派发人工巡检、核验、记录与复盘。

### 5.2 数据对象

#### `e_suggestion_template`

最小字段：

`templateId, templateCode, templateName, category, sourceRuleCode, applicableObjectType, actionContent, requiredData, estimatedSaving, costImpact, reliabilityImpact, verificationMethod, defaultImplementationDifficulty, defaultSafetyImpact, enabled, version, createTime, updateTime`。

- 对 R06 提供内置默认模板；模板措施只描述人工巡检和处理记录。
- 默认模板 seed 不受历史建议知识库闸门约束；闸门只约束 8 条历史建议及其 flow/verification fixture。
- 模板变更形成新版本。生成建议时冻结模板内容与版本，历史建议不随模板更新。
- 实施难度和安全影响是 REQ-049 排序因子的显式落点；模板只提供默认值，最终值复制到建议实例。

#### `e_suggestion` 主表扩展

除现有字段外至少包含：

`sourceFingerprint, sourceSnapshot, templateId, templateVersion, templateSnapshot, triggerBasis, objectType, objectId, priorityScore, priorityBand, priorityFormulaVersion, priorityFactors, repairAt, baselineStart, baselineEnd, reportStart, reportEnd, savingValue, savingUnit, closeReason, rejectionReason, invalidCategory, deferredFromStatus, deferReason, deferUntil, createdBy, closedBy, closedAt, rowVersion`。

- R06 当前是 AREA-B 的 `area` 级告警，建议不能强制要求 `equipmentId`；验证数据载体为 B 区管网干管 `AF-B-MAIN`。
- `sourceFingerprint + templateId` 提供幂等唯一性；不能只依赖 reset/bootstrap 后可能变化的自增告警 ID。
- `sourceSnapshot` 冻结：`eventId, ruleCode, ruleName, ruleVersion, ruleCategory, level, object, area, firstOccurredAt, lastOccurredAt, occurCount, triggerSnapshot, triggerBasis`。
- 节能量使用通用 `savingValue/savingUnit`，R06 不写入电力专用 `savingKwh` 口径。

#### `e_suggestion_flow_log`

字段：

`flowId, suggestionId, fromStatus, toStatus, operator, operatorRole, action, remark, payloadSnapshot, occurTime`。

- 创建、分派、开始执行、执行记录、进入验证、延期/恢复和关闭均追加证据，不覆盖历史。
- 多次操作可能共享同一 `DEMO_NOW`。详情接口必须按 `flowId ASC` 返回；`flowId` 为必填整数，前端禁止按 `occurTime` 排序。

#### `e_suggestion_verification`

字段：

`verificationId, suggestionId, version, status, repairAt, baselineStart, baselineEnd, reportStart, reportEnd, usageComparison, costComparison, workloadComparison, qualityComparison, savingValue, savingUnit, savingPct, calculationNote, formulaVersion, signature, generatedBy, generatedAt`。

- `status`：`waiting/effective/ineffective/insufficient`。
- 已用于关闭的验证快照不可覆盖；重新计算产生新版本。

### 5.3 列表、详情与复盘接口

#### `GET /suggestions`

查询参数：`status, sourceType, ruleCode, zone, priorityBand, pageNum, pageSize`。

`status=closed` 是五列看板“已关闭”的聚合查询别名，服务端必须映射为 `valid_closed OR invalid_closed`；它不是持久化状态值。

响应 `data`：

- `items[]`：`suggestionId, title, measureContent, sourceType, sourceFingerprint, sourceSummary, area, equipment, status, closeType, closeLabel, responsibleUser, responsibleRole, verifyStart, verifyEnd, priority{score,band,formulaVersion,factors,weights,basis}, createdAt, updatedAt`。
- `total`：筛选后总数。
- `boardCounts`：`pending, dispatched, executing, verifying, validClosed, invalidClosed, closed, deferred`。
- `filters`：筛选回显。

默认排序固定为 `priorityScore DESC, suggestionId ASC`，前端不得重新计算或重排。分页必须由 SQL `count + offset/limit` 完成。

五列映射：

- `pending` → 待审核
- `dispatched` → 已分派
- `executing` → 执行中
- `verifying` → 验证中
- `valid_closed/invalid_closed` → 已关闭，分别显示有效/无效标签

`deferred` 不增加第六列。默认看板显示延期数量入口；选择延期过滤后，使用独立列表展示。

#### `GET /suggestions/{suggestionId}`

响应：`suggestion, templateSnapshot, sourceSnapshot, flows[], latestVerification, verificationHistory[], closeInfo, allowedActions[]`。

- `flows[]` 已按 `flowId ASC` 排序。
- `allowedActions[]` 由后端根据角色、责任人、区域范围和当前状态计算；前端不能只凭状态猜权限。
- 来源告警 ID 失效时仍展示冻结证据；若可按 `sourceFingerprint` 找回当前告警，则返回恢复后的深链。

#### `GET /suggestions/retrospective`

查询参数：`month, zone, ruleCode`。响应：

`closeTypeCounts, effectiveRate, ineffectiveCount, unexecutedCount, duplicateCount, ruleOptimizationHints[]`。

优化提示是可解释的规则化统计，不使用 AI；每条提示返回来源规则、统计依据和建议复核方向。

- 路由必须在 `GET /suggestions/{suggestionId}` 之前注册，`retrospective` 不得被动态 ID 路由吞掉。
- `closeTypeCounts` 固定返回 `implemented, rejected, archivedInvalid`；`effectiveRate` 的分母仍按 §5.9/知识库 §8.6 的“实施完成关闭数”口径。
- `month` 对已关闭建议按 `closedAt` 过滤；长期未执行/延期统计按 `updateTime` 过滤。没有可反查的非空 `ruleCode` 时，`ruleOptimizationHints` 可以为空，不得伪造 R06 来源。
- `month` 省略时取 `DEMO_NOW` 所在月；响应回显 `filters={month,zone,ruleCode}`。非法或无法计算下一月边界的月份返回 422。
- 运维账号的关闭类型、有效率、未执行/重复统计和规则提示都按当前账号的 `responsibleUser` 限域；不能只限制列表/详情而让复盘聚合旁路。

### 5.4 创建、模板与执行接口

#### `POST /alerts/{eventId}/suggestions`

请求：`{templateCode?, measureContent?}`；响应：`{suggestion, created}`。

- 告警先满足服务端转换范围、再存在适用模板时才允许转换。当前范围只包含 R06，未指定模板时使用默认模板；仅创建其他规则模板不能扩大转换范围。
- 创建时冻结告警与模板快照、复制优先级默认因子，并写 `null → pending` 创建日志。
- 同一来源重复调用返回原建议和 `created=false`，不增加建议、流转或验证记录。
- 告警详情响应补 `canConvertToSuggestion, relatedSuggestionId`；R08 的 `canConvertToSuggestion=false`。

#### `POST /suggestions`

创建 `sourceType=manual` 的人工建议。必须提供来源说明、对象范围、措施、模板或完整模板快照，以及五因子实例值；不得伪装成规则来源。

#### 模板接口

- `GET /suggestion-templates`：按 `ruleCode/category/objectType/enabled` 过滤。
- `POST /suggestion-templates`：能源管理员新增模板。
- `PUT /suggestion-templates/{templateId}`：生成新版本或停用；不改变历史建议快照。

#### `POST /suggestions/{suggestionId}/activities`

责任运维补执行记录与附件元数据，不改变建议状态、计量数据、审批结果或验证结论。附件文件复用 `/common/upload`，建议域只保存上传结果的名称、URL、大小、类型和说明。

请求固定为 `{remark, attachments?, rowVersion}`；`remark` 和 `rowVersion` 必填，操作人、角色和当前状态只取服务端上下文。成功时仅追加 `fromStatus=toStatus=currentStatus, action=activity` 的 flow，建议状态和关闭/验证字段不变，`rowVersion` 精确加 1。响应必须返回最新 `rowVersion`（可包含在更新后的 suggestion 中）。

该接口锚定 REQ-047 的执行留痕，不属于 K.6“人工修正”维度；QA 权限走查按此口径判定。

### 5.5 状态流转与三档关闭

#### `POST /suggestions/{suggestionId}/transition`

请求按目标状态携带：

`toStatus, rowVersion, assignedTo?, responsibleUser?, remark, verifyStart?, verifyEnd?, deferReason?, deferUntil?, closeType?, savingValue?, savingUnit?, effectSummary?, attachments?, rejectionReason?, invalidCategory?, closeReason?`。

`rowVersion` 和非空 `remark` 必填；操作人、角色及原状态只取服务端上下文。成功响应必须返回最新 `rowVersion`。

正常链路：

`pending → dispatched → executing → verifying → valid_closed / invalid_closed`。

分支：

- `pending → invalid_closed`：驳回或归档无效。
- `dispatched/executing/verifying → deferred`：必填原因和恢复日期，保存原状态。
- `deferred → deferredFromStatus`：只能恢复至延期前状态。
- 已关闭为终态，不允许再次流转。

前置条件：派发必填责任人且 `assignedTo` 必须是有效运维账号；进入执行中必须已有责任人；进入验证中必须至少有一条执行记录。非法迁移返回 409，主表、flow 和 verification 均不落库。驳回档的 `responsibleUser` 同样必须是有效运维账号。

三档关闭由服务端硬校验：

- `implemented`：仅允许从 `verifying` 关闭；必须满足 `(savingValue 或 effectSummary) + 至少一个附件`，填写 `savingValue` 时 `savingUnit` 条件必填，且最新验证状态必须为 `effective/ineffective`。最终状态只由服务端读取**最新**验证快照派生：验证有效进入 `valid_closed`，验证无效进入 `invalid_closed`；请求中的 `toStatus` 与派生结果不一致时返回 422，绝不采信客户端结论，也不得回退使用更早版本的有效快照。
- `rejected`：请求必须显式携带 `rejectionReason + responsibleUser`，后端将其保存为建议责任人后进入 `invalid_closed`；不得复用派发字段 `assignedTo`，也不得默认取当前操作人。
- `archived_invalid`：必填 `invalidCategory + closeReason`，进入 `invalid_closed`。

前端切换关闭类型时必须剔除上一档残留字段；服务端仍是最终判据。写接口锁定建议行，通过 `rowVersion` 防并发覆盖，并在同一事务内更新主表和追加 flow。

所有合法 transition 精确增加一次 `rowVersion`。延期只能恢复到冻结的 `deferredFromStatus`；已关闭状态和同态 transition 均非法。运行时 transition 只由能源管理员执行；历史 fixture 中 `ops_user` 的 `start_execution` 仅是构造证据口径，不扩大运行时权限。

### 5.6 R06 验证口径

#### `POST /suggestions/{suggestionId}/verification/generate`

请求固定为 `{rowVersion}`；服务端锁定建议行并核对版本，在同一事务中追加 `max(version)+1` 的不可变 verification 快照、将建议 `rowVersion` 精确加 1，并返回 verification 与最新 `rowVersion`。`waiting/insufficient` 仍返回 200，但不能用于关闭。

R06 固定口径：

- 修复分界：`2026-07-10 00:00:00`。
- 基线窗口：`[2026-07-02 00:00:00, 2026-07-10 00:00:00)`。
- 报告窗口：`[2026-07-10 00:00:00, DEMO_NOW]`；查询实现按采样间隔转换为等价右开窗口。
- 即点即算，不等待 wall-clock，也不增加 APScheduler 验证任务。
- R06 的 `repairAt/baselineStart/baselineEnd/reportStart/reportEnd` 全由服务端按本节固定；transition 请求可省略 `verifyStart/verifyEnd`，若携带且与固定窗口不一致则返回 422。人工历史建议不因此获得客户端任意改写 R06 窗口的能力。

四维来源与口径：

- 用量：`AF-B-MAIN` 谷时段（22:00～次日 06:00）合格原始读数，展示非作业时段日序列；覆盖率理论点数也只计算窗口内谷时段采样槽。报告右开边界固定按 `floorToSample(DEMO_NOW)+sampleInterval` 归一；300 秒采样在当前演示时钟下得到 `2026-07-13 00:00:00`，基线/报告理论点数分别为 768/288。用有效小时均值和日均归一化，禁止直接比较 8 天与 3 天总量。
- 成本：逐时用量乘生效压缩空气单价版本，标注“演示折算成本影响”，不声称财务结算节省。
- 作业量：AREA-B 未取消工单按日汇总，作为背景对照；为 0 时不计算或声称单位作业能耗改善。
- 数据质量：合格样点/理论样点；`[80%,95%)` 返回降级提示，任一窗口 `<80%` 返回 200 和 `status=insufficient`，不输出确定性结论。

四份 comparison 都冻结同形日序列 `series={labels,baseline,report}`：用量为每日有效小时均值、成本为每日有效小时成本、作业量为每日未取消工单量、质量为每日覆盖率；`baseline/report` 是数值数组，前端只消费、不补算。四维数值数组必须与整窗结果一起进入 canonical payload 后再生成 `signature`，因此任一数值点变化都必须改变签名；`labels` 与展示说明 `note` 只用于呈现，在签名生成后追加，不进入签名。backend 与 datagen 必须共同调用 `calculate_daily_metric_series`，禁止分别维护第二套日汇总公式。

节能量：`(基线有效小时均值 - 报告有效小时均值) × 报告期有效小时数`。验证结果返回公式、单位、窗口、单价版本、质量说明和口径签名。报告归一化值低于基线为 `effective`，否则为 `ineffective`。

请求参数非法返回 422；正常的 `waiting/insufficient` 属业务状态，返回 200。重新生成形成新版本，关闭时引用具体 `verificationId`。

### 5.7 优先级（REQ-049）

实例保存五个 0～100 因子：

- 能耗规模 25%
- 成本影响 25%
- 持续时间 20%
- 实施难度 10%，按 `100 - implementationDifficulty` 反向计分
- 安全影响 20%

公式：

`priorityScore = energyScale×0.25 + costImpact×0.25 + duration×0.20 + (100-implementationDifficulty)×0.10 + safetyImpact×0.20`。

历史人工建议的通用加权版本为 `PRIORITY-V1`；R06 因前三项还包含从告警/raw/单价映射到 0～100 的确定性公式，实例版本为 `PRIORITY-R06-V1`。`score >=75` 为高、`50～74.99` 为中、其余为低。接口必须返回原值、权重、综合分、版本及可读依据，前端只展示后端结果。

### 5.8 权限、错误与页面行为

- 能源管理员：模板、创建、转建议、分派、验证、延期/恢复和关闭。
- 运维：查看被分派建议，仅可通过专属接口补执行记录/附件；不能审核、维护模板、生成最终验证结论或关闭。
- 调度/财务：按 K.6 无页面入口或只读受限；不能写。**2026-07-15 第五幕修订**：财务新增唯一窄授权入口——§6.7 成本异常转建议，且必须通过服务端可信成本来源校验；其余建议域写权不变。
- 系统管理员：即使骨架超管可见页面，也保持无建议业务修改权。
- 所有深链和写接口由后端复核角色及区域数据范围。
- `generateVerification` 只对运行期 `sourceType=rule && ruleCode=R06` 的验证中建议开放；已有历史快照的人工建议可以按其最新 `effective/ineffective` 结果关闭，但不得暴露无法执行的 R06 真算动作。
- transition、activities、verification/generate 三个写接口都执行 `SELECT ... FOR UPDATE + rowVersion` 校验；主表与 flow/verification 单次提交，任一异常整体回滚。

前端禁止拖拽卡片改状态。写失败前不得乐观移动卡片：

- 404：关闭失效抽屉并清理 `suggestionId`。
- 403：进入统一权限拦截态。
- 409：刷新详情和看板，提示状态或版本已变化。
- 422：保留表单输入并展示服务端业务错误。
- 超时：页面内保留重试入口，不只显示全局 toast。

### 5.9 复位约束与验收预期

复位顺序：建议 flow/verification 子表 → `e_suggestion` → 模板；告警域继续按第三幕约束清理。`--reset` 必须先清空运行期真实生成的建议、流转和验证快照，再无条件恢复 REQ-045 授权的 R06 默认模板；8 条历史建议只在知识库规格确认后恢复。

历史过渡口径“存量规格未落地、reset 后建议为 0”已于 2026-07-15 随知识库 §8 落地而作废，不再作为 QA 判据。当前权威预期：

1. reset 后 R06 默认模板 1 条且启用，并恢复 8 条 `sourceType=manual` 历史建议、25 条完整 flow、3 个验证快照；`sourceType=rule`、R06 关联建议、R08 模板/建议/流转/验证均为 0，`e_alert_event=0`。
2. bootstrap 只重建告警，上述建议域计数保持不变；R06 和 §4 约定的 R08 合并告警各恢复 1 条。
3. R06 转建议后总建议数为 9、规则来源建议为 1、创建 flow 增加 1；第二次调用计数不变且返回同一 `suggestionId`。

**前置挂起（已解除，2026-07-15 主控复核）**：知识库《demo-演示数据构造规范》§8 已补入默认模板与 H1~H8 历史存量规格，主控只读复核通过（优先级分值逐条重算无误、流转/快照计数与本契约一致、复位预期同口径）。历史 fixture 闸门解除，`datagen/` 可按知识库 §8 实施；仍禁止在前端硬编码五列统计、禁止把本文件反向当成需求事实源。归档复盘"有效率"分母口径按知识库 §8.6 第 4 条：有效率 = 有效关闭 / 实施完成关闭数。

## 6. 第五幕：成本核算与报表 v0.1 ｜ PRD §5.9 · REQ-051–062（+030、073/074/076）

> 页面：`/energy/cost/record`（成本核算）与 `/energy/cost/report`（报表导出）。主线为财务进入真实默认态 → 发现 6 月环比异常 → 带参下钻 B 区峰平谷构成 → 三步反查用量、单价版本和分摊规则 → 查看重算版本与差异 → 通过既有 `POST /suggestions` 转人工建议。页面常驻声明：**“管理核算口径，不替代财务结算”**。

### 6.1 全局边界、默认态与深链

- 响应继续使用 `{code, msg, data}`，字段 camelCase；Excel 二进制下载端点除外。第五幕两页只消费真实 API，禁止增加 mock 月份、金额、峰平谷占比、异常标记或版本数据。
- 成本读权限：`energy_mgr / finance / admin`；其中 `admin` 只读。按 K.6 拆分写权：单价维护与重算发起允许 `energy_mgr / finance`；成本口径、分摊规则维护与重算复核仅 `finance`；`ops / dispatch` 无成本页与成本导出权限。
- 首次进入成本页使用真实默认：`statMonth=DEMO_NOW 所在月`、`zone=ALL`、`energyType=electricity`。当前演示态为 2026-07，必须标记“进行中 · 截至 2026-07-12”，不得伪装为完整月。
- 默认视图同时返回 5/6/7 三个月系统级环比序列。2026-06 电费 ¥161,920.03 对 2026-05 ¥138,014.50，展示环比 `+17.3%`，异常标记来自规则引擎真实 R10 事件，不由成本页另写阈值或另造判断。2026-05 数据从 05-04 起，必须标记为部分月；该环比分母是 05-04～05-31 的已覆盖金额，不声称完整自然月同比性。
- 点击 6 月异常标记或环比红标后，前端将筛选切换为 `statMonth=2026-06&zone=B&energyType=electricity&focus=R10`，展示 B 区峰平谷构成；INJ-06 的峰段占比较基线期抬升 12pp 必须可见。
- 告警详情返回同形成本深链。R10 告警页入口与成本页异常入口都进入同一现场，URL 形态为：
  `/energy/cost/record?statMonth=2026-06&zone=B&energyType=electricity&focus=R10&sourceEventId={eventId}`。
- 月份、区域、介质和分组维度可自由切换；深链参数只设置初始筛选，不锁死页面。
- 任何预览、查询或报告装配读操作都不得顺带触发统计回填、成本重算、告警计算或建议生成。

#### REQ-051–062 在第五幕的承载边界

- REQ-051/052/054/055、059/062：由本节完整承载。
- REQ-053：第五幕承载月度分组成本与“月度累计成本 Top5”；需求基线中的日 Top5/年 Top10 周期切换因 PRD §5.9 页面未定义该交互，不纳入 demo 范围，避免前端自造周期控件。
- REQ-056：demo 最小实现只承载两类提示——R10 峰段成本占比异常（真实规则事件）与单价缺失提示（成本服务真实校验）。单位成本突增、总成本超预算、分摊异常、最大用能对象异常变化不在第五幕 demo 范围，不构造阈值或假事件。
- REQ-057/058：核心入口、总览、趋势/堆叠/占比/排名与筛选能力已由第一幕总览及第三幕设备画像承载；第五幕只复用相关菜单、筛选和图表形态，不重复建设全量页面能力。
- REQ-060：大屏刷新与质量提示由第一幕总览承载，不属于第五幕页面实现。
- REQ-061：P2，只保留 §6.6 灰态订阅入口，不实现定时生成或发送状态。

### 6.2 成本版本模型与不可变性

#### `e_cost_record` 版本化扩展

在现有字段上增加并冻结以下版本证据：

`costVersion, isCurrent, formulaVersion, tariffSnapshot, allocRuleSnapshot, sourceStatSnapshot, computedBy`。

- `costVersion` 对外形态为 `v1 / v2 / ...`；数据库可保存递增整数，但接口统一带 `v` 前缀。同一 `(statMonth, energyType)` 重算批次下，system/area/equipment 行使用同一个 costVersion，便于形成完整的 `v1 → v2` 批次差异。
- 唯一键扩为 `(objectType, objectId, statMonth, energyType, costVersion)`。`system` 行必须使用非空约定值或不可空的 normalizedObjectId 参与唯一键，禁止让 MySQL 的 `NULL != NULL` 语义放过同版本重复 system 行。版本行插入后，`usageQty / peakQty / flatQty / valleyQty / peakCost / flatCost / valleyCost / totalCost` 以及三个输入快照和计算签名都不可原位修改；废除金额 UPSERT 覆盖。
- 同一 `(objectType, objectId, statMonth, energyType)` 有且仅有一条 `isCurrent=true`。切换 current 必须锁定同范围旧行，在同一事务内插入新版本、取消旧 current、设置新 current并写差异记录；事务失败整体回滚。
- `tariffSnapshot` 冻结实际参与各峰平谷桶计算的 `tariffId, energyType, touPeriod, price, currency, effectiveFrom, effectiveTo, versionNo`。单档水/压缩空气只能冻结并引用实际参与三桶回退的 `flatOnly` 版本，禁止签入未参与计算的 peak/flat/valley 版本。
- `allocRuleSnapshot` 冻结实际参与计算的 `ruleId, ruleName, scope, method, config, effectiveFrom, effectiveTo, versionNo`；不是指向“当前规则”的可漂移外键。
- `sourceStatSnapshot` 至少冻结来源统计表、统计版本、合格用量和质量摘要，使成本版本可独立说明输入。

成本行签名使用版本化 canonical payload：

`{objectType, objectId, statMonth, energyType, costVersion, usageQty, peakQty, flatQty, valleyQty, peakCost, flatCost, valleyCost, totalCost, formulaVersion, tariffSnapshot, allocRuleSnapshot, sourceStatSnapshot}`。

canonical payload 稳定序列化后生成完整 SHA-256；接口可另返短码。`isCurrent/status/reviewedAt/frozenAt` 是生命周期元数据，不进入成本数值签名。任一金额、用量、实际参与版本或 costVersion 变化必须改变签名；只改变 current 标记不得改变历史版本签名（REQ-062、P-20、FX-12）。

#### current-only 消费方迁移红线

多版本落库前，下列三处直接消费 `e_cost_record` 的路径必须全部显式增加 `is_current = 1`：

1. `overview_dao`：总览 `COST_MTD` 与 Top5 成本排行。
2. `rule_engine_service.py` R10：月份枚举、成本口径聚合和峰段占比计算；不得让历史版本参与 `DISTINCT stat_month` 后的金额 SUM。
3. `cost_controller` 现有 `/cost/month/summary` 与 `/cost/month/top` 兼容接口。

遗漏任一处均为阻断缺陷。v1 current 回归锚定继续为：2026-07 系统级电 ¥59,501.99、压缩空气 ¥4,030.33、水 ¥1,313.12；bootstrap 后 R10=1、告警总数=20。历史版本存在时数值与告警计数必须保持不变。

#### 成本状态

数据层使用标准枚举：

`draft → pendingReview → reviewed → frozen`，重算新版本初态为 `pendingRecompute`；作废为 `void`。

- 冻结版本禁止修改金额、输入快照与签名；后续变化只能形成新 costVersion。
- 重算生成的 v2 立即成为页面 current，并以 `pendingRecompute` 明示；在完成复核前只允许页面与报告预览展示，正式导出/归档返回 409，避免未审批重算进入正式报表（REQ-074）。
- 复核通过后 current 版本进入 `reviewed`；复核拒绝时新版本进入 `void`，旧版本在同一事务内恢复 current，差异记录保留拒绝证据。

### 6.3 成本月度视图与三步反查

#### `GET /cost/month-view`

查询参数：

`statMonth, zone=ALL|A|B, energyType=electricity|water|compressed_air, groupBy=area|equipment|energyType`。

响应 `data`：

- `signature`：当前筛选结果的展示签名，引用 current costVersion 与实际版本快照。
- `filters`：规范化后的筛选回显。
- `period`：`statMonth, state=complete|partial|inProgress, periodStart, periodEnd, dataStart, dataEnd, asOf, label`；进行中月右边界来自 DEMO_NOW，历史月份若未覆盖完整自然月则为 partial。
- `summary`：`usageQty, totalCost, previousMonthCost, momPct, status, currentCostVersion, quality`。
- `monthTrend[]`：固定返回可用月份序列，字段 `statMonth, totalCost, momPct, periodState, dataStart, dataEnd, periodNote, anomaly{detected, eventId, ruleCode, level, note}, drillParams`；按月份升序。fresh fixture 中 2026-05=`partial`（05-04～05-31）、2026-06=`complete`、2026-07=`inProgress`（截至 07-12）。
- `groups[]`：按所选 `groupBy` 返回 `objectType, objectId, objectCode, objectName, area, energyType, usageQty, totalCost, momPct, currentCostVersion, status, signature`；对象按 ID 升序。
- `touComposition`：电力返回 `peak/flat/valley` 三段的 `usageQty, usagePct, price, cost, costPct, tariffVersion`；单档介质返回 `flatOnly`，前端不得伪造峰平谷拆分。
- `anomalyEvidence`：仅在 `focus=R10` 或当前筛选命中 R10 时返回，否则为 null。字段为 `eventId, ruleCode, source="alertSnapshot", reportMonth, baselineMonths[], baselinePeakShare, reportPeakShare, diffPp, thresholdPp, frozenAt`；全部直接映射 R10 事件 `snapshot_json` 的 `baseline_peak_share / report_peak_share / diff_pp / threshold_pp`，前端据此并列展示“基线占比 → 当月占比 → 抬升 pp”。成本页不得用 touComposition 重新计算对照值，也不得自行选择基线月份。
- `peakWindows[]`：`start, end, object, loadKw, usageQty, cost, tariffVersion, sourcePointIds[]`，用于 REQ-052 定位峰值窗口。
- `topCostObjects[]`：当前筛选周期的成本 Top5，后端排序，前端不二次聚合。
- `costWarnings[]`：REQ-056 最小范围的服务端提示，枚举只含 `peakShareAnomaly / tariffMissing`；前者关联真实 R10 eventId，后者来自成本计算对所选周期/介质的适用单价校验。其它成本异常类型不返回占位假数据。

R10 异常只来自告警服务返回的真实事件；成本月度服务复用告警查询/聚合函数取得标记和 anomalyEvidence，不复制规则阈值。没有 R10 事件时返回 `detected=false, anomalyEvidence=null`，不得按 `momPct` 在页面临时推导红标。fresh fixture 的差值约为 12pp，精确显示值以事件冻结快照为准。

#### `GET /cost/trace`

查询参数：

`statMonth, objectType, objectId?, energyType, costVersion?`；省略 costVersion 时只取 current，指定历史版本时精确读取不可变快照。

响应按财务剧情固定返回三步：

1. `usageEvidence`：`sourceStatSnapshot, usageQty, peakQty, flatQty, valleyQty, quality, sourcePointIds[], sourcePeriod`，可继续下钻原始/统计数据。
2. `tariffEvidence`：冻结的 `tariffSnapshot`、逐桶公式 `quantity × price = cost` 与 `formulaVersion`，不查询当前单价覆盖历史证据。
3. `allocationEvidence`：冻结的 `allocRuleSnapshot`、原始共享表计、分摊对象和 `allocationDetails[{objectId, basisValue, ratio, allocatedCost}]`。

同时返回：

- `costRecord`：指定版本的完整用量、金额、状态、current 标记与签名。
- `recomputeChain[]`：与该对象/月份/介质关联的 `oldCostVersion → newCostVersion` 差异记录。
- `relatedAlert`：R10 真实告警摘要与回链。
- `suggestionContext`：供 §6.7 预填人工建议的服务端可信上下文。

### 6.4 单价版本与分摊规则

#### 单价版本

- `GET /cost/tariffs`：按 `energyType, effectiveOn, includeHistory` 查询，响应按能源类型、时段、版本和生效日起稳定排序。
- `POST /cost/tariffs`：请求 `{energyType, effectiveFrom, effectiveTo?, prices, remark}`；电力 `prices={peak,flat,valley}`，水/压缩空气 `prices={flatOnly}`。服务端分配新版本号和维护人，客户端不得提交 tariffId/createBy。
- 单价版本维护允许 `energy_mgr / finance`，与 K.6 两角色均含“单价”配置一致。
- 已生效版本不可原位改价或删除；调整必须创建新版本并保留旧版本。日期区间冲突、价格段缺失或非法介质返回 422，不落库。
- 创建成功响应包含 `tariffVersion, snapshot, affectedPeriods[], recomputeRequired=true`，但不隐式重算；用户确认后显式调用 §6.5 重算接口，避免“维护单价”产生隐藏副作用。

#### 分摊规则

- `GET /cost/allocation-rules`：按 `scope, effectiveOn, includeHistory` 查询，返回 `ruleId, ruleName, scope, method, config, effectiveFrom, effectiveTo, versionNo, createBy, createTime, affectedMeters[]`。
- `POST /cost/allocation-rules`：新增不可变规则版本；`method` 只允许 `ratio / weight / workload / manual`，`config` 必须能解释各对象占比且合计满足服务端校验。
- 分摊规则属于成本口径，只允许 `finance` 维护；`energy_mgr` 可查看和在成本反查中读取冻结快照，但不能新增版本。
- 历史成本只展示成本版本行内冻结的 allocRuleSnapshot；规则表当前值不能覆盖历史反查。

### 6.5 重算版本与差异记录

#### `e_cost_recompute_record`

新表保存一次成本重算的冻结证据：

`recomputeId, periodKey, statMonth, energyType, scope, oldCostVersion, newCostVersion, triggerReason, triggerType, triggeredBy, triggeredAt, tariffSnapshot, allocRuleSnapshot, diffSummary, reviewStatus, reviewedBy, reviewedAt, reviewRemark`。

- `diffSummary[]` 按对象保存 `objectType, objectId, objectCode, metric, oldValue, newValue, deltaValue, deltaPct, oldSignature, newSignature`；字段形状对齐第二幕 `recomputeHints.diffSummary` 的 `metric/oldValue/newValue/deltaPct`，第五幕只增加对象定位、绝对差额和签名证据。
- `reviewStatus` 固定为 `pending / approved / rejected`。
- 差异记录冻结后不可改写 old/new 版本、金额或输入快照；复核只追加复核字段。

#### `GET /cost/recomputations`

查询参数：`statMonth, energyType, reviewStatus, objectType, objectId, pageNum, pageSize`。响应 `items[], total, filters`；列表含总差额与对象差异数量，详情可展开完整 diffSummary。

#### `GET /cost/recomputations/{recomputeId}`

返回完整版本链、输入快照、按对象 diffSummary、复核留痕，以及跳转 `/cost/trace` 的 old/new 两组参数。差异 → 历史 costVersion → 冻结单价版本是固定反查链。

#### `POST /cost/recomputations`

请求：`{statMonth, energyType, triggerReason, tariffVersion?, allocRuleVersion?}`。

- `triggerReason` 必填；操作人、旧版本、下一版本号、统计输入和实际版本快照只由服务端读取。
- 重算发起允许 `energy_mgr / finance`；重算复核仅允许 `finance`。
- 服务端锁定该月/介质全部 current 行，基于同一统计输入计算完整新版本，插入 v2、生成逐对象差异、原子切换 current 并写审计日志。禁止只重算页面当前一行而形成混合批次。
- 请求不得携带任何用量、金额、diff 或签名。重复提交保护按骨架 repeat-submit 与服务端事务共同处理。
- 新版本状态为 `pendingRecompute`，差异记录为 `pending`；响应返回 `recompute, currentCostVersion, affectedObjectCount`。

#### `POST /cost/recomputations/{recomputeId}/review`

请求：`{action=approve|reject, remark}`；remark 必填。通过/拒绝行为按 §6.2 状态规则执行，并记录 REQ-073/074 审计证据。

演示简化：`finance_user` 可在同一账号下发起并复核重算，以便单账号完成第五幕；审计日志仍分别记录发起与复核动作。正式部署按 REQ-074 将发起人与复核人分离，并禁止同一用户自审。

### 6.6 报表单一快照、导出与归档

#### 模板与默认周期

`GET /reports/templates` 返回：

- `ENERGY_DAILY`：分区/分能源用量、异常数、覆盖率；默认日为 DEMO_NOW 当日。
- `ENERGY_MONTHLY`：用量、成本、异常、建议、质量说明五段式；默认报告月为 DEMO_NOW 的上一完整月，即 2026-06。
- `EQUIPMENT_PROFILE / SUGGESTION_RETROSPECTIVE / COST_DIFF`：设备画像、建议复盘、成本差异专项，按需选择周期。

进行中月份允许生成预览；周期栏强制显示“截至 {DEMO_NOW 日期}”。报表订阅返回 `subscription={enabled:false, priority:"P2", label:"报表订阅（规划中）"}`，前端灰态展示且不提供伪写接口（REQ-061）。

#### 单一 canonical report payload

后端提供唯一报告装配器；预览、Excel 导出与归档都调用它，不允许前端拼五段，也不允许为导出另写一套查询口径。

月报五段来源固定为：

1. `usageSection`：`e_stat_month` 的正式统计版本。
2. `costSection`：所选周期的 current costVersion；含状态、current costVersion、单价和分摊冻结摘要。预览允许呈现 `pendingRecompute` 并显式标“重算待复核”，正式导出/归档只允许 `reviewed/frozen`，与 §6.2/§6.6 的 409 门一致。
3. `alertSection`：复用告警页 statistics 的同一 service 聚合函数；月归属按 `firstOccurredAt` 落月，告警继续由规则引擎真算，禁止 seed 或报表自算。
4. `suggestionSection`：复用第四幕 `GET /suggestions/retrospective` 的同一 service 函数和同一 month 参数；关闭三档、有效率、未执行/重复统计不得另写月报公式。
5. `qualitySection`：与页面一致的覆盖率、质量分布和不足说明。

装配器只读，不触发回填、重算、规则运行或建议生成。同参数重复装配必须得到相同 canonical payload。确定性规则：对象按 ID 升序；峰/平/谷、质量状态、关闭类型按契约枚举序；金额保留 2 位、比率保留 2 位；键名和数组顺序固定。

#### 预览、导出与归档接口

- `POST /reports/preview`：请求 `{templateCode, period, filters}`，响应 `{reportMeta, sections, signature}`；只读、幂等。
- `POST /reports/export`：同形请求，由同一装配器生成 `.xlsx`；响应头至少含 `Content-Disposition` 与 `X-Report-Signature`。未复核的 current 成本版本返回 409，不生成正式文件。
- `POST /reports/archives`：同形请求，显式执行归档；把完整 canonical payload、签名、查询条件和版本快照冻结到 `e_report_archive`。归档是唯一允许持久化报告 payload 的动作。
- `GET /reports/archives`、`GET /reports/archives/{archiveId}`、`GET /reports/archives/{archiveId}/export`：只读已冻结 payload；再次预览/下载绝不重查业务表。归档后发生成本重算时，旧归档件仍引用旧 costVersion，与差异记录互为证据。

`e_report_archive` 最小字段为：`archiveId, templateCode, templateVersion, periodStart, periodEnd, filtersSnapshot, payloadSnapshot, versionSnapshots, fullSignature, generatedAt, archivedBy, archivedAt`。除归档人/归档时间外，其余字段直接来自装配完成的 canonical report payload；归档后全部不可改。

#### REQ-030/062 双落位

每个业务工作表顶部固定 5 行，随后一行列头，冻结窗格置于数据区（标准表为 `A7`）：

1. 统计周期与查询条件。
2. 数据来源与统计口径摘要。
3. 质量摘要。
4. 系统统计时钟生成时间与相关版本摘要。
5. 签名 ID 短码与“同参数重导出比对签名”的复核提示。

工作簿另含独立“口径说明”工作表，完整记录 `dataSources, period, queryConditions, calculationNotes, qualitySummary, generatedAt, versionSnapshots, fullSignature, verificationMethod`。成本类数据来源必须写到 costVersion 粒度，使“导出文件 → 重算差异 → 单价版本”可在纸面反查。

涉及 2026-05 的环比必须在 `calculationNotes` 披露“2026-05 为 05-04 起的部分月，环比分母为已覆盖周期金额”；异常判断仍只认 R10 冻结证据，不以 momPct 代替规则判定。

- 所有 generatedAt 使用 DEMO_NOW，并在口径页注明“生成时间为系统统计时钟”；禁止 wall-clock。相同参数与相同数据重导出，签名必须一致。
- 报表签名覆盖完整明细数据、全部实际参与的版本快照、查询条件、质量摘要和 generatedAt，不得只签元数据。使用稳定 canonical payload 生成完整 SHA-256；顶部显示短码，口径页显示完整签名和复核说明。
- 版本快照按相关性裁剪：含成本段才列 costVersion/单价/分摊版本；含建议段才列建议模板版本。日报不含成本段，不得引用成本版本；设备画像专项不含建议时不得引用建议模板版本。
- 顶部块不合并或破坏数据列结构；列头保持 Excel 筛选/透视可用，冻结窗格位于固定 5 行和列头之下。

### 6.7 成本异常转人工建议

成本页“转节能建议”复用第四幕 `POST /suggestions`，保持 `sourceType=manual`，不得伪装成规则自动建议。请求在既有人工建议字段上增加可选可信来源：

`sourceContext={kind:"costAnomaly", statMonth, objectType, objectId, areaId?, energyType, costVersion, recomputeId?, alertEventId?, costSignature}`。

- 前端只传定位键；后端重新读取指定不可变 costVersion、差异记录和 R10 告警，校验签名后写入 `sourceFingerprint/sourceSnapshot`，不信任客户端传来的金额、占比或版本快照。
- 建议标题、来源说明和措施可由页面预填，用户确认后创建；优先级五因子仍走第四幕后端公式。
- 建议详情返回成本来源深链；成本页按 `relatedSuggestionId` 回显已转建议，避免重复创建。
- 财务写权限是严格窄授权：仅当 `sourceContext.kind=costAnomaly` 且服务端能校验 current/历史成本版本证据时，`finance_user` 可调用该入口；不带可信成本来源的通用人工建议仍只允许 `energy_mgr`，第四幕其它创建、流转、验证和关闭权限不变。
- K.6 锚定注：该入口锚定 PRD §5.9“成本异常 → 转节能建议”剧情，不扩展为 K.6 通用建议写权；QA 权限走查按“可信成本来源窄授权”验收，处理方式沿用第四幕“运维补录不属于 K.6 人工修正维度”的同类口径。
- 该出口只创建人工巡检/分析建议，不得包含设备启停、远程控制或自动下发指令（REQ-091/096 禁令继续有效）。

### 6.8 权限、错误与 INJ-08

- `finance_user`：查看成本，维护成本口径、单价和分摊规则，发起并复核成本重算，预览/导出/归档报表，以及通过 §6.7 窄授权转人工建议。
- `energy_mgr`：查看成本，维护单价、发起重算，预览/导出/归档报表，并从跨幕视角查看关联告警与建议；不能维护分摊规则或复核成本重算。
- `admin`：只读审计与系统配置；无业务数据修改权。
- `ops_user` 访问任一成本页面或 `/cost/*` 业务端点必须返回 403，并现场写 `e_audit_security`；禁止 seed INJ-08。`dispatch_user` 同样无成本数据权限，但第五幕演示拦截主体固定为 ops_user。
- 越权深链、历史 costVersion、归档下载和导出端点都必须重新做服务端鉴权，不能只靠菜单隐藏。
- 400/422：非法筛选、区间、价格段、分摊配置或缺必填；403：权限拦截；404：版本/差异/归档不存在；409：current 冲突、版本已变化、未复核版本正式导出、冻结版本写入；所有写失败均不得产生半条版本、差异或归档记录。

### 6.9 复位确定性与验收锚点

复位顺序：报表归档 → 成本差异记录 → 全部成本版本。`python datagen/generate_demo_data.py --reset` 后 `e_cost_record / e_cost_recompute_record / e_report_archive` 均为空；随后 `POST /pipeline/bootstrap?force_republish=true` 按 `e_stat_month` 的实际唯一业务键 1:1 生成各月 v1，初态为 `reviewed + isCurrent=true`，并令每个 `(对象×月×介质)` 恰有一条 current，使 fresh fixture 可直接预览、导出和归档。禁止在 datagen 直接 seed 成本结果或差异结果。

演示重算（典型为新增生效单价版本）生成同月/介质完整 v2、逐对象差异记录并原子切换 current；复核后可正式导出。再次 `--reset + bootstrap` 必须恢复仅 v1 current、无差异、无归档的初始现场，可重复演示。

阻断性验收：

1. v1 current 锚定：2026-07 系统级电 ¥59,501.99、压缩空气 ¥4,030.33、水 ¥1,313.12；2026-06 系统级电 ¥161,920.03，较 2026-05 部分月 ¥138,014.50 展示为 +17.3%，并披露 5 月仅覆盖 05-04～05-31。
2. 默认页为 2026-07/ALL/electricity 且标“进行中”；6 月异常自显，点击后带参落到 2026-06/B/electricity，峰段占比抬升 12pp。
3. 落入 v2 后 overview COST_MTD、Top5、存量 cost API 与 R10 只读 current；重算未改变 INJ 输入时 R10=1、告警总数=20，不因历史 v1 双计数。
4. 任一成本数值或实际参与版本变化都会改变成本签名；仅切换 current 标记不会篡改历史签名。
5. 月报预览、Excel 和归档 payload/签名一致；同参数重导出签名一致；旧归档在成本重算后保持旧 costVersion 与旧金额。
6. Excel 每个业务表固定 5 行顶部块并有独立口径页；日报不出现成本版本，月报成本段可反查到 current costVersion、差异与单价快照。
7. ops_user 访问成本页被 403 拦截并现场新增一条安全审计；finance_user 可完成“发现 → 下钻 → 反查 → 重算差异 → 转建议 → 月报导出”全链路。

## 待补契约

统计分析页仍待按 PRD §5.4 字段表起草；第五幕成本核算与报表以本节 v0.1 为内部契约草案，主控确认后再进入实施计划。
