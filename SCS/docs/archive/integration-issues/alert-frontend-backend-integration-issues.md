# 告警中心前后端联调问题与适配记录（alert-frontend-backend-integration-issues）

- 模块：告警中心 + 告警处置闭环（`/alarms`）
- 前端：Vue3 + TS（`frontend/src`）；后端：Spring Boot 3.5.5 Backend Demo（内存数据，`backend/.../module/alert`）
- 记录时间：2026-09-05
- 原则：本阶段只做**适配**，不改后端契约、不做正式领域设计；下表“后续正式设计建议”留待领域模型阶段统一处理。

## 一、问题清单

| # | 字段 / 主题 | 前端当前定义 | 后端当前定义 | 影响 | 当前适配方式 | 后续正式设计建议 |
|---|---|---|---|---|---|---|
| 1 | 顶部指标口径 | `mock/alertEvents.ts` 的 `ALERT_METRIC_BASE`（total=128、closed=94 等历史基数） | `GET /alerts/metrics` 仅对内存 12 条实时聚合（total=12、closed=3…） | 接通后 KPI 数字比 Mock 小，属预期，不是 Bug | 主数据流只用后端返回；`ALERT_METRIC_BASE` 仅在显式 Mock 回退时使用 | 后端区分“今日/累计”口径，返回时间窗口与趋势基数 |
| 2 | 规范时间戳 | `AlertEvent` 原只有展示用 `time`（HH:mm:ss） | 额外返回 `occurredAt / slaDeadline / updatedAt`（ISO-8601 带时区） | 前端类型缺字段 | `types/alert.ts` 补 3 个可选字段；`adapters/alert.ts` 集中映射 | 正式模型统一“展示时间由前端按 ISO 时间戳格式化”，后端不再返回 HH:mm:ss |
| 3 | SLA 计算 | 仅用 `slaRemainingSec` 快照 | 读取时按 `slaDeadline` 重算 `slaRemainingSec`，关闭后该字段被 NON_NULL 剔除 | 快照不会走秒；关闭事件字段缺失（非 null） | `SLAIndicator` 以 `slaDeadline` 为核心本地每秒走秒，`slaRemainingSec` 仅兜底；adapter 兼容 undefined | 统一为“后端只给绝对 deadline，剩余时间一律前端计算” |
| 4 | 派单字段命名 | Dialog 产出 `assignee/priority/limitMin/note` | `AssignRequest` 同时兼容 `assignee/assigneeId/assigneeName`、`limitMin/slaLimitMin/deadline` | 命名冗余，易混用 | API Client 固定只发 `assignee + priority + limitMin + note` | 正式收敛为一套命名（建议 assigneeId + slaDeadline） |
| 5 | 时间范围筛选 | 筛选区有“近 2 小时 / 近 4 小时”枚举（`timeRange`） | `AlertQuery` 承载 `timeRange`，但 `InMemoryAlertRepository.matchTimeRange` **只实现 from/to，timeRange 未参与过滤** | 选择近 2/4 小时当前等价“全部”（不会误清空，列表仍可用） | 保持传参，前端不本地补过滤 | 后端实现滚动窗口（近 N 小时），或前端改为传 from/to 绝对时间 |
| 6 | “已确认”状态 | 类型与 ActionBar 含“已确认” | confirm 直接 待确认→待派单，种子无“已确认”数据 | UI 需同时兼容两种状态 | ActionBar 保留 `已确认 || 待派单` 同分支 | 正式状态机确认是否保留独立“已确认”态 |
| 7 | 序列化空值 | 前端可选字段允许 null | 类级 `@JsonInclude(NON_NULL)`，空值字段直接不输出 | JSON 中路径不存在而非 null | adapter 全部按“缺省=undefined”归一，不依赖 null | 明确全局空值序列化策略并在接口文档标注 |
| 8 | AI 证据结构 | `AiEvidence{scene,boxes[],confidence,model,camera,time}`，`DetectionBox.score:number` | sealed `AiEvidence`，`score` 为 double，scene 为字符串 | 字段一致，但 scene 取值（helmet/fence）必须双端严格一致 | `mapAlertEvidence` 做 kind 白名单；scene 依赖现有种子对齐 | scene/box label 做成后端枚举字典，前端按字典渲染 |
| 9 | 幂等行为 | `http.ts` 每个非 GET 自动生成 `Idempotency-Key` | Kvrocks 共享幂等；本地无 Kvrocks 时**降级直放** | 本机演示重复点同一操作不会回放首结果，而是由状态机返回 409（同样不会重复写） | 前端不做客户端去重，依赖后端；按钮请求中 disable | 服务器接入 Kvrocks 后验证跨实例回放；正式期补幂等响应标识 |
| 10 | 错误结构 | 原 `http.ts` 只抛“请求失败：HTTP xxx” | 统一错误体 `{code,message,traceId,details,timestamp}` | 409 文案无法直达用户 | 增强 `http.ts`：解析后端 message/code/traceId，DEV 控制台打印 traceId | 维持该统一错误体，前端建立按 code 的交互映射表 |
| 11 | 列表分页 | 原前端一次加载全量再本地 filter，无分页控件 | 后端分页 `{page,pageSize,total,list}`，pageSize 上限 200 | 需要真实分页与后端驱动筛选 | 新增 `el-pagination`；筛选变化回到第 1 页并重新请求；筛选项下拉用一次 pageSize=200 的请求生成 | 下拉选项改走字典接口（areas/eventTypes/assignees），不借大页列表 |
| 12 | 处置附件 | TreatmentDialog 自动生成“现场处置照片_xxxx.jpg（Mock）” | 原样保存到 `treatment.attachment` | 非真实附件 | 保持现状，字段随 treatment 提交 | 接入文件服务后返回真实附件 URL/ID 列表 |
| 13 | 事件升级 targets | 升级时传 `['调度员','管理人员']` | `EscalateRequest.targets` 仅记录，不做真实通知 | 无真实通知下发 | 前端固定传演示对象，Timeline 由后端追加 | 对接通知中心 / 消息网关 |
| 14 | 操作人 | 无登录体系；review 固定“刘志明”，confirm/start 不传操作人 | 缺省取 Demo 用户（李娜） | 操作人为演示值 | confirm/start 不传由后端兜底；review 传固定 reviewer | 接入当前登录用户上下文（auth/me → 操作人） |
| 15 | 联动演示 | LinkageStatus 以前由前端定时推进步骤 | `POST /linkage {mode}` 后端一次性返回 7 步最终态 | 前端不再自行推进 | 直接以后端返回 linkage 渲染；success/fail 两模式均接通 | 正式期联动步骤应来自实时事件流（WebSocket/MQ） |

## 二、已确认一致、无需适配的点

- 风险等级（一般/预警/严重/紧急）、状态中文枚举、来源（人员安全/设备防碰撞/AI违规/设备异常/系统异常）双端完全一致。
- 证据 5 类 kind（personnel/collision/ai/device-metric/system-metric）字段一一对应，现有 `AlertEvidence.vue` 无需改动即可渲染。
- Timeline 节点结构 `{time,text,state}` 一致；写操作后由后端追加并随详情返回，前端不再本地 push。
- 13 个接口路径与方法双端一致，业务前缀统一 `/api/v1`，成功响应不做 code/data 包装。

## 三、遗留与后续

- 后端为内存存储：进程重启后回到 12 条种子，F5 刷新（进程不重启）状态保持，符合本阶段预期。
- 正式领域设计阶段需要统一：Alert 与 AIEvent 边界、状态机终态、操作人/附件/通知的真实化、timeRange 口径、指标累计口径。
