# SCS 改进路线图与实施清单（2026-10 基线）

> 依据：交接文档 + 代码实况核对（2026-10-24）。所有"现状"均经源码验证，文件行号可点击定位。
> 重要更正：交接文档称"9 个 Repository 仍为内存实现"，实际 Phase 1 已基本完成——
> 7 个 Jdbc*Repository 已交付（Alert/Fence/Rule/Personnel/Collision/AiEvent/EdgeNode），
> 经 `@Profile("server")` / `@Profile("!server")` 双轨切换，`--spring.profiles.active=server` 启动即为真实持久化。
> LiveEventGate 已接入事务 afterCommit（`LiveEventGate.java:82-101`）。

## 优先级总览

| 编号 | 改进项 | 优先级 | 依赖 | 状态 |
|------|--------|--------|------|------|
| P0-1 | Edge 断网补传队列落库（edge_pending_event） | P0 | 无，表已建 | ✅ 完成（2026-10-24） |
| P0-2 | 运维事件日志落库（ops_event_log） | P0 | 无，表已建 | ✅ 完成（2026-10-24） |
| P0-3 | AI 模型配置落库（需先补 DDL） | P0 | schema 变更评审 | ✅ 完成（2026-10-24） |
| P0-4 | 大屏/统计 SQL 聚合改造（Step 8） | P0 | 无 | ✅ 完成（2026-10-24） |
| P0-5 | openGauss 集成测试常态化 | P0 | 无，脚本即可 | ✅ 完成（2026-10-24） |
| P1-1 | JwtVerifier 支持 RS256 公钥验签 | P1 | IAM 组公钥到位前可先开发 | ✅ 完成（2026-10-24） |
| P1-2 | 生产 BFF /safety/api/* 路由切换 | P1 | 平台集成组（雷文） | 外部协作 |
| P2-1 | UWB 手环 UDP 报文接入 | P2 | 现场硬件 | 待评估 |
| P2-2 | 毫米波雷达 Modbus/CAN 驱动 | P2 | 现场硬件 | 待评估 |
| P2-3 | PLC 联锁停机 ≤2s 端到端实测 | P2 | P2-1/P2-2 | 待评估 |
| P2-4 | MinIO 对象存储接入（抓拍原图） | P2 | MinIO 服务 | 1~2 天 |
| D-1 | RocketMQ 真实启用与可靠性验证 | 运维 | 联调窗口 | 待办（外部窗口） |
| D-2 | Grafana 看板与指标告警 | 运维 | 无 | ✅ 完成（2026-10-24） |
| D-3 | 数据生命周期策略（备份/归档/保留期） | 运维 | 无 | 🟡 备份+测试数据清理已完成；归档分区/触发器策略/openGemini 保留期待 DBA |
| D-4 | 架构决策记录 + 交接文档同步机制 | 流程 | 无 | ✅ 完成（2026-10-24，docs/architecture-decisions.md ADR-001~007） |

---

## P0-1 Edge 断网补传队列落库 ✅（2026-10-24 完成）

**交付物**：`JdbcEdgeEventQueueRepository`（@Profile("server")）+ `InMemoryEdgeEventQueueRepository` 补 @Profile("!server")；
行主键 id 由 idempotencyKey 确定性派生 UUID（复用 `JdbcAlertRepository.resolveUuid`），配合
`uq_edge_pending_event_idempotency` 唯一约束实现数据库层幂等收敛；`linked_alert_id` 落 UUID 并 LEFT JOIN
反查 alert_no 还原业务编号；payload_summary/local_linkage JSONB 读写（null 语义保真：jsonb 'null' 往返）；
不实现 DemoClearableStore（防删触发器 + Phase B 契约）。单测 12 项（upsert 分支/并发竞态/行映射/过滤归一化）
+ Step 6 实机集成测试 8 项（重启存活/幂等收敛/重放顺序/联动告警反查/JSONB null/跨仓储事务）。
server profile 断网补传队列重启不再丢失。

**现状**：`InMemoryEdgeEventQueueRepository` 无 @Profile 注解，两种 profile 都走 JVM 内存——
后端重启即丢失待补传事件，"断网不瘫痪、恢复后幂等回传"的设计承诺存在数据丢失窗口。

**有利条件**：
- 权威表 `safety.edge_pending_event` 已在 schema 中定义（`openGauss-schema-final-draft.sql:646`），
  含 `uq_edge_pending_event_idempotency` 唯一约束（幂等判重权威）与 `uq_edge_pending_event_node_id`；
- 接口 javadoc 已预先写明目标 SQL：
  `findPendingForReplay` → `WHERE edge_node_id=? AND status IN ('PENDING','FAILED') ORDER BY edge_occurred_at NULLS LAST, event_id`。

**步骤**：
1. 新建 `JdbcEdgeEventQueueRepository`，挂 `@Profile("server")`；给 `InMemoryEdgeEventQueueRepository` 补 `@Profile("!server")`；
2. `save()` 用 `INSERT ... ON CONFLICT (idempotency_key) DO UPDATE`（openGauss 兼容 PostgreSQL 语法），同时以 `(edge_node_code, event_id)` 唯一约束兜底；
3. `payload_summary` / `local_linkage` 两个 JSONB 列：入库用 `PGobject::setJsonb` 或 `?::jsonb`，出库解析为领域对象，参考 `JdbcAiEventRepository` 的既有 JSONB 处理模式；
4. `query()`/`pendingCount()`/`count()` 直接翻译为带过滤条件的 SQL；
5. `DemoClearableStore` 只保留在 InMemory 版实现上（server profile 无 Demo 清场）；
6. 补 `OpenGaussStep` 集成测试：覆盖幂等冲突（同 idempotency_key 不同 payload_hash → DUPLICATE）、SYNCING 不被重放捞取、重启后队列仍在。

**验收**：server profile 下 kill -9 后端进程，重启后 PENDING 事件完整、可继续重放；集成测试纳入 Spike 系列。

## P0-2 运维事件日志落库 ✅（2026-10-24 完成）

**交付物**：`JdbcOpsEventLogRepository`（@Profile("server")）+ `InMemoryOpsEventLogRepository` 补 @Profile("!server")。
append 走 INSERT RETURNING 回填序列主键；recent() 完整复刻内存口径：level 忽略大小写、时间边界含等号、
非法 ISO 串静默忽略、limit 缺省 50 / 上限 300、ts 倒序 + id 倒序；`time` 展示字段由 occurred_at 按上海时区
重新生成。已知有意差异（表约束所致）：append-only 不做 300 条环形裁剪（防删触发器），读侧 limit 上限保证有界；
读回 id 为序列数值串（内存版为 OPS-LOG-nanoTime 前缀串），仅作展示键。单测 5 项 + Step 6 集成测试过滤口径用例。

**现状**：`InMemoryOpsEventLogRepository` 同样双 profile 内存。表 `safety.ops_event_log`
（`openGauss-schema-final-draft.sql:678`）为 append-only 设计，序列 `seq_ops_event_log` 已存在。

**步骤**：
1. 新建 `JdbcOpsEventLogRepository`（@Profile("server")）：`append()` 即 INSERT；`recent()` 为
   `ORDER BY occurred_at DESC` + nodeId/level/type/时间范围过滤 + LIMIT；
2. InMemory 版补 `@Profile("!server")`；
3. 集成测试验证 append-only 语义与过滤条件组合。

**验收**：运维页 `/operations` 事件流重启不丢；过滤查询走索引。

## P0-3 AI 模型配置落库（需先补 DDL）

**现状**：`InMemoryAiModelRepository` 内存实现；**权威 schema 25 张表中没有 ai_model 表**——
这是 3 个残留仓库中唯一需要先做表设计的。

**步骤**：
1. 设计 `safety.ai_model` DDL：模型编码/名称/架构类型（YOLOv8 等）/版本/置信度与 IoU 阈值参数/
   是否选用（is_selected，全表至多一行 true，可用排他约束或触发器保证）/在线状态/软删标记/时间戳；
2. DDL 评审后合入 `openGauss-schema-final-draft.sql` 并在 node4 `scs_test` 库先行验证；
3. 新建 `JdbcAiModelRepository`：`findSelected()` 走 `WHERE is_selected AND NOT is_deleted`；
4. 前端 `/ai/models` 的"热切换"写路径经 server profile 验证重启后仍生效。

**验收**：模型选用与参数调优配置重启不丢；DDL 进入权威脚本并同步数据字典文档。

## P0-4 大屏/统计 SQL 聚合改造（迁移计划 Step 8）

**现状**：`AnalyticsService` 仅依赖 `AlertRepository`（内存全量拉取后 Java 聚合），
`module/projection` 全目录无任何 JdbcTemplate——数据量增长后每次统计近乎全表加载。

**步骤**：
1. 在 projection/analytics 模块引入 `JdbcTemplate` 聚合查询层（仅 server profile 生效）：
   按 `safety.v_*` 有效视图做 GROUP BY 风险等级/类型/日期的计数、MTTR 时间差均值、区域热度排行；
2. 内存实现保留给 test/dev profile，接口契约不变（沿用现有 persistence-neutral 风格）；
3. 对比改造前后同一数据集的统计结果一致性（可用集成测试断言双轨等值）；
4. 关注点：大屏高频刷新接口（/safety-screen）加短 TTL 缓存（Kvrocks 已接入，见 IdempotencyService 同款客户端）。

**验收**：30 日窗口告警量 10 万级时，统计接口 P95 < 500ms；双轨结果一致。

## P0-5 openGauss 集成测试常态化 ✅（2026-10-24 完成）

**交付物**：`deploy/scripts/run-opengauss-tests.sh`（bpoc-node4 一键执行）。自动加载 runtime/backend.env
凭据（不打印 Secret），激活 RUN_OPENGAUSS_SPIKE + STEP1/2/25/3/4/5/6 全部门控，默认全量执行
`OpenGaussStep*`；支持 `SCS_TEST_DATABASE=scs_test` 切换测试库与 `ONLY_STEPS="Step1,Step6"` 选择性执行。
新增 Step 6（边缘队列 + 运维日志实机验收）一并纳入门控。**发布前检查单必跑项**。

**现状**：`OpenGaussStep*IntegrationTest` 系列被 `RUN_OPENGAUSS_SPIKE=true` 环境变量门控
（`OpenGaussSpikeSupport.java`），日常 `mvn clean test`（307 项）**完全不覆盖 JDBC 代码**，
SQL 漂移无例行回归兜底。

**步骤**：
1. 在 node4 上编写 `deploy/scripts/run-opengauss-tests.sh`：直连本机 5432 scs_test 库，
   设 `RUN_OPENGAUSS_SPIKE=true` + 连接环境变量后跑 `mvn test -Dtest='OpenGaussStep*'`；
   （node4 直连可绕开本地隧道不稳定的限制；本地开发仍可用 SSH 隧道 + 同脚本）
2. 脚本纳入发布前检查单与 verify-deployment.sh 调用链；
3. 明确触发时机：每次 schema 变更、每次 Jdbc*Repository 改动、每次发版。

**验收**：发版流程中"真实库集成测试通过"成为强制门禁。

## P1-1 JwtVerifier 支持 RS256 公钥验签

**现状**：`SecurityInterceptor.java:37` 注明算法白名单为 **HS256/384/512（对称）**；
Phase 2 与平台 IAM 对接要求 **RS256 非对称公钥验签**——属能力缺失，非配置问题。

**步骤**：
1. `JwtVerifier` 引入非对称验签分支：支持 RS256/RS384/RS512/ES256，公钥经 PEM 或 JWKS URL 注入
   （优先 JWKS，为密钥轮换留路）；
2. 算法白名单改为配置项（`app.security.jwt.allowed-algorithms`），防止 alg 混淆攻击
   （必须显式拒绝 header 中声称 HS* 的 IAM 公钥体系 token）；
3. 兼容期保留 HS256 通道（本地联调/服务间 token），以配置区分；
4. 补安全测试：过期/早于 nbf/算法篡改/公钥不匹配/aud 与 scope 不符各分支。

**验收**：IAM 组下发的 RS256 token 全链路通过；16 项既有安全测试不回归。

## P1-2 生产 BFF /safety/api/* 路由切换

**步骤**：与平台集成组（雷文）确认 BFF 路由、证书与公钥分发方式 → 在预发环境联调 → 切流。
依赖 P1-1 完成。此为外部协作项，排期跟随平台侧。

## P2-1 ~ P2-3 现场硬件接入与联锁实测

**步骤**（三件套联动，建议同一排期窗口）：
1. P2-1 UWB 手环：UDP 报文解析服务（协议帧结构需向厂商索取），接入前以录制报文回放做驱动开发；
2. P2-2 毫米波雷达：Modbus/CAN 驱动层 + 测距序列按设计写入 openGemini（`OpenGeminiClient` 已就绪）；
3. P2-3 联锁实测：搭建极限网络（丢包/断链/高延迟）与高负载工况，实测"边缘自治停机信号下发 ≤2s"红线，
   产出实测报告；不达标则回溯边缘判定链路优化。

**验收**：真实传感器数据贯通定位/测距页面；≤2s 红线有量化实测报告背书。

## P2-4 MinIO 对象存储接入

**现状**：pom 无任何对象存储客户端；AI 违规抓拍高清原图与整改照片无真实落盘方案。

**步骤**：
1. pom 引入 minio-java；新增 `ObjectStorageService` 抽象（便于未来换 OSS 实现）；
2. `ai_event` 证据链路上报图片 → 存 MinIO → 库内仅存对象 key/URL；
3. 前端 `/ai` 证据核验改走 presigned URL 取图；
4. 桶策略：按日期分区、生命周期规则。

## D-1 ~ D-4 工程化与运维

- **D-1 RocketMQ 启用**：当前测试保护硬关闭（`RocketMqAdapter` @ConditionalOnProperty）。
  联调窗口开启后验证：消息可达、重复消费幂等、断连重连补投。
- **D-2 Grafana 看板**：micrometer-registry-prometheus 已在 pom，补 /actuator/prometheus 采集配置、
  后端 JVM/连接池/告警吞吐看板、阈值告警规则。
- **D-3 数据生命周期**：openGauss 定期备份（gs_basebackup 或逻辑导出）入 cron；
  `safety_alert`/`ops_event_log` 按月归档策略；openGemini 轨迹库设置 retention（高频时序不设保留期必爆磁盘）。
- **D-4 架构决策记录**：①单实例 WebSocket 广播限制（扩容需 Redis pub/sub 桥接，当前单点为有意决策）；
  ②后端 18080 内网明文边界说明；③交接文档随阶段收尾同步更新的流程约定。

---

## P0-3 AI 模型配置落库 ✅（2026-10-24 完成）

**交付物**：`safety.ai_model` 表（26 张表之一，DDL 已入权威 schema 脚本 6.3 节 + `deploy/scripts/ddl-ai-model.sql`
独立应用脚本）；`JdbcAiModelRepository`（@Profile("server")，**空表自动补种 5 款预置模型**——种子与内存版共用
新抽取的 `AiModelSeedData`，生产环境 Demo 灌种关闭时 /ai/models 页面开箱可用）；`v_ai_model` 有效视图 +
防删触发器。单测 8 项 + Step 7 实机集成测试 4 项（全字段往返/热切换与调优重启存活/软删除/不误补种）。
**模型选用与置信度/IoU 调优从此重启不丢**。

## P0-4 大屏/统计 SQL 聚合改造 ✅（2026-10-24 完成）

**交付物**（复用既有 `AlertRepository.filter/page` SQL 通道，新增计数契约）：
1. `AlertRepository` 新增 `countByStatus()/countOpenByRisk()/countByEventType()`（Jdbc 版 GROUP BY 下推，
   InMemory 版流式等价实现）——大屏每次刷新由 6+ 次全量聚合加载（含 timeline 子表）降为 3 个轻量 GROUP BY；
2. `SafetyProjectionService`：active/processing/closed/countByRiskOpen/riskDistribution 全部改读计数契约，
   recent() 走分页通道 SQL LIMIT；
3. `AnalyticsService.filter()`：时间窗/区域/风险等级三个直接列条件下推存储侧（此前每次统计全量拉表内存过滤），
   班组/类型/设备/人员等 Java 派生维度在收敛后的结果集上过滤；deviceDetail/personDetail 增加 keyword 超集预过滤。
单测 3 项新增（计数下推）+ 既有统计/投影测试全绿。已知语义变化：occurredAt 为空的告警不再进入统计窗口
（与告警分页口径对齐；库表 occurred_at 为 NOT NULL，实际无影响）。

## 2026-10-24 第二次发布记录（P0-3 / P0-4）

| 项 | 值 |
|---|---|
| 新 JAR SHA-256 | `f790e29a7c49b4dcad512ac9c132e8c8dd75c3d9fc1e7912f57da9202520b6d5` |
| 回滚备份 | `/opt/project-b/SCS/safety-gate-service.jar.bak_20261024`（上一版 `3e15d7f2...`；原始版另有 `.bak_20261022`） |
| 验证 | 服务 active 3.1s 启动；health 全 UP；node6 18445 入口 200；匿名 API 401；ai_model 预置 5 款在线 |
| 实机门禁 | **Step1~7 全量 41/41 PASS**（含首次转绿的 Step5） |
| 数据库变更 | ai_model 建表 + 触发器 + 视图；safety_fence/-_version 补 buffer_distance 列（漂移修复） |

**本次排障修复的三个既有缺陷（均属"代码与 openGauss 实况不兼容"，线上此前已带病运行）**：
1. `JdbcFenceRepository` 使用 PG 9.3+ 的 `LEFT JOIN LATERAL`，openGauss（9.2 血统）无法解析——正式环境
   /fences 列表查询本就 500；已改为 max(id) 标量子查询连接（语义等价）；
2. b_project 的 safety_fence / safety_fence_version 缺 `buffer_distance` 列（权威 schema 有、库没有）——
   围栏保存在正式环境本就失败；已按权威定义加列（NUMERIC(6,2) NOT NULL DEFAULT 5.00）；
3. Step5 集成测试 RuleQuery 参数顺序写反（keyword/category/status/risk），从未真正通过过；已修正。

**新登记的基础设施治理项（归 D-3，需 DBA 决策，本次未擅自全库应用）**：
- b_project 整库未挂防删触发器（权威 schema 14 节定义了 25 表全覆盖，实况仅 ai_model 有）；
  本次仅 ai_model 挂了触发器，其余表是否补挂需基础设施负责人统一决策；
- 集成测试数据在生产库 b_project 累积（测试自带前缀且可收敛，但缺统一清理机制）。

## P1-1 JwtVerifier 支持 RS256 公钥验签 ✅（2026-10-24 完成）

**交付物**（`JwtVerifier` G6 增强 + `SecurityInterceptor` 接线 + 3 个新配置项）：
1. **非对称验签**：RS256/RS384/RS512/ES256（JDK Signature，无三方依赖；ES256 做 RFC 7515 raw R||S ↔ DER 转换）；
2. **公钥注入**：PEM（`APP_SECURITY_JWT_PUBLIC_KEY_PEM`，支持字面 `\n`）或 JWKS 端点
   （`APP_SECURITY_JWT_JWKS_URL`，按 `kid` 选钥、15 分钟 TTL 缓存 + 未知 kid 限流刷新，为密钥轮换留路）；
3. **算法白名单配置化**（`APP_SECURITY_JWT_ALLOWED_ALGORITHMS`，缺省 HS 三件套=行为完全兼容）；
4. **算法混淆结构性免疫**：HS 家族只认配置密钥、RS/ES 只走公钥 Signature，两条路径零共享密钥材料。
   fail-closed 构造（RS 无公钥源 / 半截配置 / 空白名单均拒绝启动）；既有 HS 通道与 11 参构造器完全兼容。
   测试 16 项（含混淆攻击两种变体、JWKS 轮换、篡改/错钥/过期/未生效/issuer）。
   修复过程中发现并修正 ES256 DER 长度编码 bug（SEQUENCE 内容长度多加 2）。

**IAM 联调切换方法**（公钥到位后，改 `/etc/project-b/SCS/service.env` 后 restart 即可，无需改代码）：
```bash
APP_SECURITY_JWT_PUBLIC_KEY_PEM="-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----"  # 或
APP_SECURITY_JWT_JWKS_URL="https://<iam>/.well-known/jwks.json"
APP_SECURITY_JWT_ALLOWED_ALGORITHMS="RS256"      # 过渡期可 "RS256,HS256" 双通道并存
```

### 2026-10-24 第三次发布记录（P1-1）
新 JAR SHA-256 `d32838bea731373388edb0c962daa23c3ea8b1e5526cd0fedcc53731e64de264`；
回滚链：`bak_20261024`（P0-3/4 版）→ `bak_20261024b_p012`（P0-1/2/5 版）→ `bak_20261022`（原始版）。
本地 427 项测试全绿；node4 认证测试 41/41 + 实机门禁 41/41；部署后 health 全 UP、匿名 401、18445 入口 200。
**运行行为零变化**：正式环境未配置非对称项，白名单缺省 HS 三件套，现行 HS256 通道照常工作。

### 2026-10-24 第四次发布记录（D 系列）

| 项 | 值 |
|---|---|
| 新 JAR SHA-256 | `21fefdcfb75a90518294c12c2e13a6a159a27e564b5a5f3f43256dc900b5eafb`（回滚链同前） |
| D-2 交付 | `SafetyMetricsConfiguration` 业务指标（scs_alerts / scs_alerts_open / processing / closed / scs_edge_pending_events，经 /actuator/prometheus 输出已在线验证）；`deploy/monitoring/`（Grafana 看板 JSON + Prometheus 告警规则 + Nightingale 接入说明） |
| D-3 交付 | `deploy/scripts/backup-opengauss-scs.sh`（gs_dump 容器内执行 + 14 天轮转 + sha256），cron `/etc/cron.d/scs-opengauss-backup`（每日 02:30）已装，首备份 28M 成功；`cleanup-test-data.sql` 已执行（清 99 边缘事件/45 运维日志/35 模型行/测试告警，指标实时回落佐证） |
| D-4 交付 | `docs/architecture-decisions.md`（ADR-001~007：单实例广播边界/明文边界/单选用软不变量/触发器策略缺口/测试顺序耦合/发布回滚链约定/观测体系选型） |
| 验证 | 本地 430 项全绿；实机门禁 41/41；部署后 scs_* 指标在线、匿名 401、18445 入口 200 |

### 2026-10-24 第五次发布记录（Code Review 修复批）

| 项 | 值 |
|---|---|
| 新 JAR SHA-256 | `1607bd4762f28a41dba1b16b16ae370d571b35d75652649c56913decb6d06886` |
| 验证 | 本地 437 项全绿（+7 握手鉴权）；实机门禁 41/41 + 认证测试 48/48；部署后 WS 匿名握手 401、Swagger 404、池 max=8、健康全 UP |

**修复清单**（对应 review 报告编号）：
- **#1 WS 握手鉴权**：新增 `WsHandshakeAuthInterceptor` 委托 SecurityInterceptor 同源校验（会话 Cookie 随握手自动携带——**前端零改动**；另支持 X-API-Key / Bearer / `?token=`）；开关 `app.security.ws-auth-enabled` server 缺省 true；
- **#2 补传事务化**：`EdgeReplayService.replayOne` 4 连写纳入 REQUIRES_NEW 单事件事务，失败整笔回滚，事件不再卡死 SYNCING；逐条独立提交语义保持；
- **#3 防碰撞并发**：CollisionService 读方法共用服务监视器 + trend 返回快照副本，消除 CME 与可见性问题；
- **#4/#5 事务补齐**：RuleService.publish（不再卡 PUBLISHING）与 AiModelService.selectModel（不再双 ACTIVE）@Transactional 化；
- **#6 intern 锁替换**：AiEventService 改用 KeyedLock（引用计数分段锁）；
- **#7** EdgeOpsService.refreshRuntime 读改写入锁；
- **#9** JwtVerifier JWKS 拉取失败补 warn 日志；
- **#10** Fence/Rule/AiModel 广播统一经 gate.buffer（afterCommit 收口），新增 nullable-gate 构造器保持测试直连兼容；
- **#8** springdoc 生产关闭（api-docs/swagger-ui 404）；**#12** application-server.yml 补齐 P1-1 三键；**#13** 连接池 4→8（service.env OPENGAUSS_POOL_MAX=8，指标 hikaricp_connections_max=8 佐证）。

**review 遗留（P3，未修）**：29 处 JDBC 仓储 catch 静默（建议统一加 debug 日志的机械改动）、Kvrocks 命令超时代码内兜底、ADR-005 测试顺序耦合。

## 建议执行顺序

```
第 1 周   P0-1 ✅ → P0-2 ✅ → P0-5 ✅  （2026-10-24 完成；396 项测试全绿；Step6 实机 8/8 通过；已发布正式环境）
第 2 周   P0-3 → P0-4 + P1-1 并行    （DDL 评审与 RS256 开发可同步等 IAM 公钥）
之后      P1-2 联调窗口；P2 系列按硬件到场排期；D 系列穿插进行
```

---

## 附：2026-10-24 正式环境发布记录（P0-1/P0-2/P0-5）

| 项 | 值 |
|---|---|
| 发布目标 | node4 `/opt/project-b/SCS/safety-gate-service.jar`，systemd `project-b-scs`，端口 18082 |
| 新 JAR SHA-256 | `3e15d7f2a3b1765526a2e4732826f75894d9cbf52b30119f603ae56e5b02ff78`（node4 构建，源码与本地权威树字节级一致） |
| 旧 JAR 备份 | `/opt/project-b/SCS/safety-gate-service.jar.bak_20261024`（SHA `c14e8dec...49b1c`） |
| 回滚命令 | `sudo cp -a /opt/project-b/SCS/safety-gate-service.jar.bak_20261024 /opt/project-b/SCS/safety-gate-service.jar && sudo systemctl restart project-b-scs` |
| 验证结果 | 服务 active，启动 3.5s；`health/ready` 200（application/cache/database/openGemini UP，rocketmq 按配置 DISABLED）；node6 `https://192.168.101.74:18445/health/ready` 200；匿名 API 401（鉴权生效）；`/ws/live` 400（端点在位） |
| 发布范围 | 仅后端 JAR；node6 前端静态目录未变更（本次无前端改动） |

**运行配置事实**（勘察确认，纠正旧手册记载）：正式环境 `SPRING_PROFILES_ACTIVE=server`、
openGauss `b_project` 库（非手册 3.3 节所记 b_poc）、`safety_admin` 用户；25 张表含
`edge_pending_event`/`ops_event_log` 均在该库就位（Step6 实机测试验证）。

**本次发现并登记的遗留问题**：
1. `MasterDataConsistencyTest` 测试顺序耦合（`FenceService.java:195` 空 area 默认"自定义区域"，
   Linux 执行顺序下先被一致性检查捕获）——与本次改动无关的既有缺陷，待修；
2. openGauss 防删触发器使集成测试的清理 DELETE 静默失效，`scs_*_it_` 前缀测试数据会在
   b_project 累积（归入 D-3 数据生命周期治理）；
3. leiwen 用户的 IAM 联调隔离实例（`/home/leiwen/scs_isolated/`）在 node4 独立运行，本次未触碰。

每项完工定义（DoD）：代码合入 + 单测/集成测试通过 + server profile 在 scs_test 库实测验证 + 本文档勾选状态更新。
