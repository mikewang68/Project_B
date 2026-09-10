# 运维监控 + 云边断网自治：当前边界与遗留问题（Operations / Edge Autonomy Issues）

> 阶段：S3「装卸作业安全卡控系统」— 运维监控 Backend + 前后端联调 + 云边断网自治 Demo
> 形态：**SIMULATED EDGE AUTONOMY**。当前只有一个 Spring Boot 进程，云 / 边是同进程内的架构行为模拟，
> **不是**真实部署了一套边缘计算平台。代码与 UI 均已显式标注该性质。
> 最后更新：2026-09-10

---

## 1. 当前 Edge 为同进程模拟（不是真实边缘节点）

- `DemoEdgeNode`（EDGE-01 ~ EDGE-04）、离线队列、本地联动、恢复状态机全部运行在同一个 Spring Boot 进程内，
  节点之间没有独立进程、独立 JVM、独立网络命名空间，也没有真实边缘 Agent。
- “断网”由 `POST /api/v1/edge/simulate-link {state:disconnect}` 置 `cloudConnected=false` 模拟；
  并非真正切断网络。节点心跳、指标在离线时冻结 / 恢复时继续，仅为行为模拟。
- 前端拓扑断线、自治横幅、恢复时间线全部来自该模拟状态，不代表真实链路质量。
- **真实部署边界**：正式形态应为每个作业区一台边缘网关（Agent 进程），与中心平台通过消息通道交互；
  本阶段的接口路径与状态机（断网→自治→对账→补传→在线）可作为未来边缘 Agent 与中心协议的蓝本，但不能直接等同。

## 2. 真实 Edge 部署的差距清单

| 能力 | 当前 Demo | 真实部署需要 |
| --- | --- | --- |
| 边缘进程 | 同进程对象模拟 | 独立边缘 Agent / 网关进程，独立生命周期与资源隔离 |
| 云边通信 | 方法调用 | 双向通道（MQ / gRPC stream），断网检测、重连、退避 |
| 本地规则引擎 | 复用中心规则快照（版本号模拟） | 边缘可独立运行的规则运行时与规则包下发 / 校验 |
| 本地联动 | 内存记录 success / skipped | 对接真实 PLC / 声光 / 语音硬件，带回执与失败重试 |
| 离线缓存 | JVM 内存队列（重启丢失） | 边缘本地持久化（嵌入式 KV / 本地文件 / SQLite 类） |
| 时钟 | 固定偏差模拟 | 真实 NTP / PTP 校时与偏差监控 |
| 安全 | 无边缘侧鉴权 | 边缘设备身份、证书、通道加密、准入 |

## 3. 离线缓存真实落盘方案（当前仅内存）

- 当前 `InMemoryEdgeEventQueueRepository` 使用 `ConcurrentHashMap`，**进程重启后离线事件丢失**，仅够 Demo。
- 正式方案建议：
  1. 边缘侧使用嵌入式持久化（RocksDB / SQLite / 本地 WAL 文件），事件先写本地 WAL 再判定补传，保证掉电不丢；
  2. 单条事件状态机（PENDING→SYNCING→SYNCED / FAILED / DUPLICATE）落盘，补传进度可恢复；
  3. `payloadSummary` 之外的视频 / 图片证据走对象存储分片，事件体只保留引用与摘要（当前已不存大对象，沿用该原则）；
  4. 缓存容量保护：按风险等级淘汰、磁盘水位告警（Demo 中 `cacheAlert` 仅做数值模拟）。
- 中心侧正式持久化在 openGauss，属于后续“openGauss 正式持久化”阶段，本阶段不做。

## 4. MQ（RocketMQ）最终接入方式

- 本阶段**未连接** RocketMQ（服务器 node5 虽有 5.5.0，但按阶段约束不接入），离线补传是顺序方法调用。
- 正式接入后：
  - 边缘→中心：补传事件走可靠 topic，消费端以 `eventId / idempotencyKey` 做消费幂等；
  - 中心→边缘：规则 / 围栏版本下发、远程指令走下行 topic，需要边缘 ACK 与重投；
  - 跨实例 WebSocket 广播：当前 `/ws/live` 只能在单实例内广播，多实例后需要 MQ 广播扇出（见第 10 节）。
- 当前 `IdempotencyService` 基于 Kvlocks（Redis 协议）设计，本地无 Kvrocks 时降级为直接执行；
  正式多实例必须以共享存储保证写操作幂等。

## 5. Rule / Fence 版本是否统一（当前刻意分离）

- 规则版本（`activeRuleVersion` / `expectedRuleVersion`）来自现有 `RuleRepository` 最新已生效规则（RULE-PER-001），
  **未建立第二套规则库**；围栏版本（`activeFenceVersion` / `expectedFenceVersion`）当前为 Demo 对齐字段。
- 二者**没有强行合并**：规则是“判定与联动策略”，围栏是“地理边界数据”，变更频率、下发粒度、回滚语义都不同。
- 遗留决策点：
  - 是否引入统一的“边缘配置包版本”（rule + fence + 参数一起打版、一起对账）；
  - 还是维持两条独立版本线、恢复时分别对账（当前实现）。建议正式阶段按边缘配置包统一，减少半同步状态。

## 6. 时间同步方案（当前非真实 NTP）

- 当前 `clockOffsetMs` 为模拟值：初始 +32ms，`timeDrift` 场景置 +3200ms，校时后回到 +120ms；
  阈值 `DemoEdgeNode.CLOCK_DRIFT_THRESHOLD_MS = 1000ms`（Demo 值，代码已注释）。
- 正式方案：
  - 边缘网关接入 NTP（内网时间源）或 PTP（高精度作业场景），中心定期采集偏差；
  - 事件统一携带 `edgeOccurredAt`（边缘时钟）与 `serverReceivedAt`（中心时钟），偏差超阈值时进入时间对账，
    但**不回改历史事件时间**（当前 `ruleVersionUsed` / `edgeOccurredAt` 的审计冻结语义已按此实现）。

## 7. 事件幂等键的正式规则

- 当前幂等三层：
  1. 队列层：同一 `eventId` / `idempotencyKey` 已 SYNCED 即判 DUPLICATE（Backend 兜底，不依赖前端）；
  2. Alert 层：补传 Alert 的 `dedupKey = EDGE-REPLAY:{node}:{eventId}`；
  3. 写接口：非 GET 请求由前端自动带 `Idempotency-Key`，后端写操作重复安全。
- 当前 `idempotencyKey = node:eventType:epochSecond:seq`，**Demo 口径**。正式规则建议：
  - 由边缘侧按 `边缘节点ID + 本地单调序列 / UUIDv7` 生成，全局唯一、趋势递增、可离线生成；
  - 中心侧保留幂等表（持久化、去重窗口覆盖最大离线时长 + 补传延迟）；
  - 明确“同键不同负载”为冲突（409），当前 Demo 不校验负载一致性。

## 8. 断网事件顺序保证

- 当前按 `edgeOccurredAt` 升序、相同时间按 `eventId` 升序**稳定排序后顺序补传，不并发**（`InMemoryEdgeEventQueueRepository.queueOrder`）。
- 正式环境需要：
  - 边缘侧保证本地序列号单调（同一节点严格有序）；
  - 中心按节点 + 序列号保序消费，跨节点不要求全局序；
  - 乱序到达（重发 / 分片）时由幂等层吸收，并能按业务键合并；
  - 超大积压需要批量 / 限速补传，避免恢复瞬间压垮中心（当前 Demo 量级小，逐条顺序即可观察）。

## 9. Edge 事件与 Cloud Alert 的关系

- 离线期间：边缘只产生 `EdgePendingEvent`（含本地判定摘要与本地联动记录），**不进入 AlertRepository、不广播 alert.new**，
  因此 `/alarms` 数量在断网期间不增长（本阶段核心验收点，已有测试覆盖）。
- 补传时：由现有 `AlertService.createEdgeReplayAlert` 生成**同一条 Alert 主链**（不新建 EdgeAlert 模型），
  `origin=EDGE_REPLAY`、`edgeReplay` 元数据记录 edgeNodeId / offlineEventId / syncDelaySec / ruleVersionUsed，
  Alert `occurredAt` 保持边缘发生时间，Timeline 固定记录“边缘产生→边缘联动→云中断→入缓存→恢复→补传→平台接收”七节点。
- 遗留：当前实时在线事件不经过 EdgePendingEvent（直接走风险建单）；正式形态是否让所有边缘事件统一先过本地队列
  （在线时快速透传、离线时缓存）以统一链路，需要在边缘 Agent 阶段确定。

## 10. 多实例 WS 广播

- 当前 `WebSocketSessionRegistry` 为单实例内存会话表，`ops.*` 与 `alert.new` 等事件只能广播到本实例连接。
- 多实例（后续三节点部署）后：前端连接落在哪个实例不确定，需要 RocketMQ / Redis Pub-Sub 做实例间扇出，
  或引入 sticky session；本阶段不实现，接口与事件载荷已按“轻量通知 + REST 重拉权威数据”设计，便于后续替换广播通道。

## 11. openGemini 指标持久化（当前为内存样本）

- 本阶段**未写入** openGemini（服务器 node6 虽有 1.5.2，按阶段约束不接入）。
- CPU / 内存 / 延迟 / 队列深度趋势为**按请求确定性生成的稳定样本 + 当前实时值**（30 点），
  没有后台高频采样线程（避免无限 ScheduledExecutor，应用可正常退出）。
- 正式方案：指标按固定低频采样写入 openGemini，运维趋势 / 容量分析从时序库查询；
  实时状态仍走 WS 轻量事件，历史曲线走时序库，二者职责分离。

---

## 本阶段“仅 Demo、不做”的事项一览（对齐任务书第六十三节）

真实边缘节点进程、真实 PLC / 定位 / 雷达、真实 NTP、真实 RocketMQ、openGauss 正式持久化、openGemini 写入、
Easegress / Nginx 正式部署、三服务器部署、高可用、分布式锁 / 事务、复杂 Event Sourcing、正式领域模型重构——
本阶段均不涉及。本地无中间件时，Database DOWN、Cache DOWN、MQ / Timeseries DISABLED 是**合法状态**，
运维页以 `environment=DEV` + `capabilityState(UP / DEGRADED / DISABLED / UNAVAILABLE)` 正常展示，不白屏、不全标红。
