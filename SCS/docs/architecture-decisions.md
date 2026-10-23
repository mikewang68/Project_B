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

## ADR-004 b_project 防删触发器策略缺口

**背景**：权威 schema 定义了 25 表防物理删除触发器，但 b_project 实况只有 ai_model 挂了
（P0-3 部署时补建了函数副本）。
**决策**：不擅自全库补挂——既有运维流程可能依赖 DELETE（如测试清理）；
全库触发器补挂属基础设施治理项，由 DBA 统一决策（见路线图 D-3）。
**后果**：正式表存在被误 DELETE 的风险窗口；备份（每日 02:30，保留 14 天）是当前兜底。

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
