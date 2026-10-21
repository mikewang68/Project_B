# TRUST通用操作日志适配（公共服务1.1.0）

主路径：业务模块 → AUDIT SDK/API → AUDIT持久日志／发送快照 → TRUST通用OPERATION_LOG。业务模块不领取TRUST直接日志写入凭据。旧协议仅显式legacy模式使用，不能双路发送同一日志。

## 映射与持久性

sourceEventId和businessObjectId均为SHA256(domainId + LF + zoneId + LF + appCode + LF + eventId)的小写64位十六进制。schemaVersion=1、eventType=OPERATION_LOG。外层occurredAt转UTC并向下截到微秒，payload.event保留原纳秒时间及int64；内层payloadHash为AUDIT原事件摘要。

请求按固定键序输出UTF-8并带一个末尾LF，先存log_trust_delivery再发送。三种摘要分别为内层payloadHash、完整请求originalSha256、含TRUST身份及版本的schema-2 eventSha256，不能混用。首版schema-2摘要会本地重算；回执出现更正版本时停止自动处理。

全新快照先持久记录submission_attempted，再执行首次POST；一旦可能发送过，先按原号查回。已知内部ID不见、目标或摘要变化进入人工核查，不换号。首次直接POST与官方示例／模拟器一致；模拟器将受理前404记为额外拒绝，因此初次空查不是本实现必要前置。

## 启用闸门

默认AUDIT_CHAIN_ENABLED=false。generic模式指定AUDIT_TRUST_SOURCES_FILE，每个domain/zone/app精确配置独立永久来源，禁止通配符、来源复用或凭据复用。enabled与archiveApproved均为true才处理；eligibleFrom限定允许接入的接收时间，不默认搬迁历史记录。

config/trust-sources.example.json仅为模板。凭据在私有tokenFile内，轮换保持来源id及命名；既有快照的来源／目标指纹变化会停止。客户端核对capabilities实例ID、隔离标记和schema版本，但能力探针不能代替内容、资源、钱包及OPERATION_LOG链策略批准。当前适配要求隔离联调标记isolationEnabled=true。

## 状态及补办

保存内部id、原始状态、outcomeUnknown及原始回执。最新可信回执由公共API的trust字段返回；观察历史在log_trust_receipt。HTTP 2xx或ACCEPTED不是上链完成。

PROCESSING／RETRY_WAIT／CONFIRMING映射本地ACCEPTED并保留原状态。ACTION_REQUIRED映射FAILED，未知标记保留。COMMITTED需来源、原号、内部ID、原文摘要和重算eventSha256匹配，taskState=DONE、fileState=STORED、chainState=COMMITTED、outcomeUnknown=false，并有交易ID、原文CID、清单CID及摘要。客户端依赖TRUST有效交易核验声明，不宣称自身独立访问Fabric/IPFS验链。

网络／5xx／429有界退避，可能提交过即按原号／id对账；30次错误或总等待到期停止。409及完整性问题不无限重试。

公共retry在generic模式下需要AUDIT RETRY权限，先查远端。已提交只补本地确认；远端任务FAILED且来源retryAllowed=true才显式POST /{id}/retry，固定{}，不改快照、原号或内部id。轮询不会偷偷retry；运行中冲突不抢租约；补办超时报告未知，下一次人工操作仍先查状态。

## 数据库及构建

新库使用service/src/main/resources/schema.sql；已有1.0.0库由获准迁移账号在AUDIT独立schema执行sql/002_generic_evidence.sql，生产auto-DDL保持false。运行角色不得改删日志正文或快照身份／原文／摘要列。delivery仅授必要状态列更新及INSERT/SELECT，receipt表只INSERT/SELECT。具体GRANT由DBA按实际schema和角色审阅。

服务jar为audit-service-1.1.0.jar，SDK为audit-client-1.0.1.jar。先在AUDIT执行mvn install，再构建EHM。SDK事件schema和公共业务API仍为v1。

## 验证边界

官方工具测试需要Java17并传-Dtrust.selfcheck.home=<私有工具目录>，目录中含trust-selfcheck.jar和examples/oplog-source.json。OfficialSelfcheckTest使用实际worker、JDBC快照及真实本地HTTP，执行check/serve/report并断言本地状态；连接文件和原始报告留在工具.local。

官方场景当前覆盖normal、disconnect、unavailable、timeout、action-required。未宣称官方idempotency／correction／permissions／retry专项场景全部通过；本地独立测试覆盖摘要漂移、冲突、来源门禁、只读拒绝及双端显式补办。首版不自动创建更正事件、不提供通用原文恢复API，不等于全量日志归档、真实数据库或多节点验收。

TRUST通用入口会非加密归档完整JSON，且没有业务原文恢复API。真实启用前必须确认内容、可见范围、保留要求及批准人；元数据本身也可能涉密。若需加密，应重新确认方案，不能私自回退旧接口。
