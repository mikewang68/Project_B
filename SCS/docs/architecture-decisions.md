# SCS 架构决策记录（ADR）

> 记录影响后续演进的架构决策与已知边界。每条含：背景 / 决策 / 后果与解锁条件。
> 与《improvement-roadmap》互为引用：路线图管"做什么"，本文管"为什么这样、何时可变"。

## ADR-001 单实例部署与 WebSocket 广播边界

**背景**：LiveEventGate 与 WebSocket 会话均为单 JVM 内实现（事务 afterCommit 广播）。
**决策**：当前按单实例部署（project-b-scs.service，18082）运行，不引入跨实例消息总线。
**后果**：水平扩容会导致不同实例上的 WS 客户端事件漏发。
**解锁条件**：需要高可用时，在 LiveEventGate 后挂 Redis pub/sub（Kvrocks 已就绪）桥接广播；
该改动收敛在 common/realtime 一处，业务代码无感。

## ADR-002 后端 18082 明文 HTTP 边界

**背景**：TLS 终止在 node6 nginx（18445），node4 内网段明文回源。
**决策**：内网明文可接受（192.168.101.0/24 受控网段 + Tailscale ACL）；不自行维护证书轮换。
**后果**：若内网被横向渗透，token 可被嗅探。
**解锁条件**：安全等级提升时由平台侧统一在 node1/node6 链路启用 mTLS，SCS 不单独改。

## ADR-003 ai_model 单选用软不变量

**背景**：模型热切换是"循环逐个 save"的非原子操作。
**决策**：不加 is_selected 数据库排他约束，排他性由 AiModelService.selectModel 保证；
findSelected 按编码稳定序取首个选中行兜底。
**后果**：极端并发下可能出现瞬时双选中（窗口极小，读侧有兜底）。
**解锁条件**：如需强一致，改为单条 SQL 原子切换（CASE WHEN 批量 UPDATE）。

## ADR-004 b_project 防删触发器策略（已闭环 2026-10-24）

**背景**：权威 schema 定义了全表防物理删除触发器，但 b_project 实况长期只有 ai_model 挂了。
**决策与执行**（项目组拍板"只做 SCS 自己的 safety 库表"）：
1. 前置核实：server profile 应用层零 DELETE（DemoAlertMaintenance 仅支持 InMemory，优雅降级）；
2. 门禁从 b_project 切换至独立可弃库 **scs_test**（同构 schema、不挂护栏、可随时重建），
   生产库不再承接任何测试写入；连续两轮门禁 41/41 验证切换无损；
3. `deploy/scripts/install-delete-guards.sql`（幂等）为 b_project **safety 模式 26 张表**全量挂
   `trg_prevent_delete_*`；金丝雀验证 DELETE 被 ERROR 硬拦截、事务中止零数据受损。
**注意**：今后任何对 b_project 的数据清理只能走 UPDATE 软删/状态流转；scs_test 重建流程见
`run-opengauss-tests.sh` 头注释（drop → setup → 权威 DDL → 授权 → 摘护栏）。

## ADR-005 测试顺序耦合（已根治 2026-10-24）

**背景**：`MasterDataConsistencyTest` 在 Linux 文件系统顺序下可能先于污染者执行而失败
（共享 Spring 上下文中，先跑的测试类经 `FenceService:195` 自建围栏，其运行时默认
"自定义区域"进入了仓储；一致性检查却断言**全部**围栏 area 落在字典内）。
**根治**：`fenceSeedsUseCanonicalAreas` 断言前先 `fenceRepository.resetDemoData()`
恢复纯种子再校验——运行时自建围栏的"自定义区域"本就是合法业务数据，不属于种子一致性范畴。
**附带流程改进**：`run-opengauss-tests.sh` 门禁通过后自动执行 `cleanup-test-data.sql`
尾清理，集成测试数据不再滞留 b_project。

## ADR-006 发布与回滚链约定

**背景**：正式 JAR 位于 /opt/project-b/SCS（scszxj:scszxj 640），无 git 流水线。
**决策**：每次发布前保留带版本语义的备份文件，链条保持完整：
`bak_20261022`（原始）→ `bak_20261024b_p012`（P0-1/2/5）→ `bak_20261024`（P0-3/4）→ 当前。
发布流程固化在 `deploy/scripts/deploy-prod-scs.sh`（预检→备份→安装→重启→验证），
实机门禁 `run-opengauss-tests.sh` 全绿是发版前置条件；新 JAR 记录 SHA-256 于路线图。

## ADR-007 观测体系选型

**背景**：实验集群已有 Nightingale（node1:17000）与 Cockpit；SCS 指标走 Prometheus 文本协议。
**决策**：后端只承诺 `/actuator/prometheus`（含 scs_* 业务指标）这一标准出口；
抓取与告警由平台既有体系（Nightingale/categraf 或 Prometheus）接入，SCS 不自建 Grafana。
看板 JSON 与告警规则作为资产随仓库交付（deploy/monitoring/），不绑定特定实例。

## ADR-008 八项设计决策的最优实现（2026-10-24 全部落地）

第二轮复查上报的 8 项设计决策，经拍板"按最优设计实现"后全部交付：

1. **围栏草稿/生效双表示（D1，最重）**：B5-04 版本保护在围栏域完整落地——
   EFFECTIVE/MISMATCH 围栏编辑进入 TO_REVIEW 时，JDBC `active_version` 保持指向生效版本
   （新几何仅写草稿版本行），读路径 join 改为 `version_no = active_version`；
   发布时根表单条 UPDATE 原子切换指针+状态。InMemory 以双仓（store/draftStore）镜像同一机制。
   编辑器视图（get/list/require）叠加草稿 overlay 保证编辑连续性，运行时读数不受影响。
2. **closedAt 全链路（D2）**：DemoAlert 增加 closedAt 字段；库表 closed_at 列本就存在，
   此前无读侧映射。写侧优先沿用读回值（跨天生命周期多次保存不漂移），首次关闭才现取；
   处置时长可由 closedAt - occurredAt 派生，durationSec 保持"检测持续时长"语义不变。
3. **时间线 state_code 收敛（D3）**：append-only 语义收窄为"message/occurred_at 只读"，
   state_code 随内存快照同步回写（active→done 收敛不再重启丢失/持续累积）。
4. **关联抢占原子化（D4）**：ai_event.linked_alert_id 以条件 UPDATE（CAS）抢占——
   多实例并发确认同一 AI 事件只有一个告警抢占成功，败方重复告警转 CANCELLED（状态流转，
   防删护栏友好），双建风险消除。
5. **AI 跨聚合去重（决策：维持现状）**：不同 AI 事件的同一违规各自建单是有意设计
   （源头已由载荷摘要幂等去重），无代码改动。
6. **告警关闭镜像同步（D6）**：AlertClosureListener 监听器接口 + ObjectProvider 惰性注入
   （解 Alert→AI 循环依赖）；review/demoRisk 关闭路径触发，AI 事件镜像闭环（幂等）。
7. **WS 慢客户端防护（D7）**：会话注册表统一以 ConcurrentWebSocketSessionDecorator 包装
   （发送 2s 超时 / 256KB 缓冲上限），慢客户端被断开而非拖住业务提交线程。
8. **upgradeRisk 拦 CLOSED（D8）**：已关闭告警不可升级风险等级（与 escalate 状态门禁对齐）。
