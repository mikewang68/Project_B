# 通用 JSON 存证接入

AUDIT 日志映射及待确认边界见 [OPERATION_LOG 接入方案](operation-log-generic.md)。

本入口供源系统提交完整 JSON 业务快照，默认关闭。TRUST 持久受理后，归档首次请求原文字节及清单到 IPFS，再通过已批准钱包把摘要和 CID 登记到 Fabric。202 只表示数据库已受理，不表示完成上链，也不证明源系统所述业务事实真实。

首版只提供机器 API，无通用页面、附件上传或公开下载。payload 中的 URL、CID、操作员编号均为普通字段；TRUST 不下载链接，不验证其内容或把源系统操作员声明解释为人类密码学签名。来源、组织和提交主体只能由服务端配置确定。

## 配置与权限

将 [配置模板](../deploy/evidence-ingress.example.json) 复制到实例私有 `runtime/secrets/evidence-ingress.json`，由维护方替换占位符并审核后启用全局与对应来源。模板不可直接用于真实写入。每个来源配置稳定 `id`、新生成专用 token 的 `tokenSha256`、`orgId`、`sourceSystem`、`operations`、`allowedEventTypes`。

- `read` 必需，`submit` 可选；`retry` 独立且默认不授。
- 组织加 sourceSystem、来源 id 和 token 摘要必须唯一，不能与旧 WMS 配置冲突，也不能复用身份供给 token。删除旧配置不会释放数据库中已保留的来源命名。
- allowedEventTypes 必须逐项列出，不接受通配符；通用入口禁止 WAREHOUSE_IN、DISPATCH。
- 来源 id 是永久归属标识。轮换 token 保持 id 不变；改名不会继承旧来源回执。
- 接口许可不等于链上签名许可。钱包注册和绑定仍需双人批准，链上来源、证书和事件类型策略需由网络维护方明确配置。接口不会调用 SetSigner、签发钱包或升级链码。

当前钱包策略导出及旧 signer-policy 脚本面向 WMS 类型，不能直接用来给通用事件扩权。真实通用上链须另行提供获准的来源、钱包及网络策略；缺少它们时只能验证本地受理和明确使用替身的归档流程。

## API

路径前缀：`/api/v1/integrations/evidence/events`。所有请求使用该来源专用 Bearer token；IAM 会话、WMS token、身份供给 token 均不能代替。新凭据也不继承旧浏览器或 WMS 权限。完整结构见 [OpenAPI](openapi.json)。

| 方法及相对路径 | 操作 | 权限 |
|---|---|---|
| POST 空路径 | 提交新事件或追加更正 | submit |
| GET /{id} | 按 TRUST id 查询回执 | read |
| GET /by-source-event/{number} | 按原事件号找回丢失的提交响应 | read |
| GET /capabilities | 返回 instanceId、isolationEnabled、ingressSchemaVersions | read |
| POST /{id}/retry | 显式恢复失败任务 | retry |

功能关闭为404，凭据缺失或无效为401，操作/类型未授权为403。查询、更正和重试均同时限定通用记录类别、组织、来源命名和稳定来源主体；越界与不存在统一404。回执不返回 payload、canonical、私钥引用、批准详情或内部异常正文。

请求须为未压缩 UTF-8 `application/json`。必填 schemaVersion=1、sourceEventId、eventType、businessObjectId、occurredAt、非空对象 payload；可选 supersedesId。不接收 batchId、组织、来源、钱包、附件列表等其他顶层字段。

| 字段 | 限制 |
|---|---|
| sourceEventId | `[A-Za-z0-9._-]{1,120}`，禁止完整值 `.` 和 `..`；`.item`、`item.`、`item..1`、`...` 等其他合法点号保留，可按号查询 |
| eventType | `[A-Z_]{1,40}` 且在来源许可中 |
| businessObjectId | 非空字符串，最多120个 UTF-16 代码单元 |
| occurredAt | 带时区时间，规范为 UTC；非零微秒以下精度拒绝 |
| payload | 对象；深度最多16、节点最多2000、key最多120、单字符串最多16384个 UTF-16 代码单元 |
| 数字 | 最多38位有效数字，规范后 scale 绝对值不超过18 |
| 原文及 canonical | 分别最多262144个 UTF-8 字节 |

重复 key、未知顶层字段、尾随 JSON、非法 UTF-8/BOM、孤立代理字符和 U+0000 返回400。字符串不 trim，不做 Unicode 归一化；对象 key 排序，数组顺序保留，1 与 1.0 可视为同一数值，字符串 "1" 与数字1不同。

合成输入见 [generic-maintenance.json](../examples/generic-maintenance.json)。其中 URL 仅示范普通字段，不会被访问。

## 幂等和更正

业务幂等键是 sourceEventId，作用域是组织加来源命名，无需另传 Idempotency-Key。同号相同规范内容返回200、同一 id、replayed=true；同号异文返回409。空白或对象 key 顺序不同的重放保留首次请求字节，不替换原文、摘要或任务。submit 重放不会重置失败任务。

events、generic_event_data、tasks、审计和来源命名保留在同一事务中提交；任一失败不返回202。提交结果未知时先按原号查询，再用原号与原快照重发；不能换号掩盖冲突。

更正使用新 sourceEventId 和前序 supersedesId，仅能更正本来源最新通用版本，保持 eventType、businessObjectId。payload 是完整替换快照。并发更正只允许一个后继成功，另一个409；旧原文和版本不变。旧批次入口更正通用 id 返回409 `EVENT_SCHEMA_MISMATCH`，不会替请求补造批次。

## 回执与恢复

回执含 status、taskState、fileState、chainState、eventSha256、首次原文 originalSha256/originalCid、manifestCid/manifestSha256、txId、blockNumber、outcomeUnknown、errorCode、nextRetryAt。缺少链上高度时为 null，不编造0。GET 为同一数据库快照；不等于每次查询都重新核验 IPFS。

| status | 含义 |
|---|---|
| ACCEPTED | 已持久受理，等待首次处理 |
| PROCESSING | worker 已领取任务 |
| RETRY_WAIT | 依赖失败后等待自动重试 |
| CONFIRMING | 有 PREPARED/UNKNOWN 签名尝试，或链确认进行中 |
| ACTION_REQUIRED | 任务FAILED，需要核查并显式恢复 |
| COMMITTED | 本次链上记录已经查询并核对，任务完成 |

ACTION_REQUIRED 优先于 CONFIRMING；即使 taskState=FAILED，outcomeUnknown=true 仍表示可能已发出交易，不能解读为链上没有记录。恢复先查链；已有一致登记时只补本地确认，不重复提交。缺少真实钱包或批准时禁止回退旧签名身份。

retry 请求体最多64字节，兼容空 body 和仅JSON空白；按JSON语义接受单个空对象，包括 `{ }` 和对象内外换行、空格、制表符。数组、非空对象、其他JSON值、尾随内容、非法UTF-8或超过64字节均返回400 `INVALID_REQUEST`。READY/DONE 返回200和 queued=false；RUNNING返回409，即使租约已过期也不抢占；FAILED原任务转READY返回202和 queued=true。它不改变事件、原文、CID、版本、钱包、批准快照或未决签名尝试。并发retry最多产生一次FAILED到READY转换及对应审计。read+retry但无submit的来源可恢复既有任务，不能新增事件。

功能、来源或事件类型收回后，worker在领取前过滤通用任务，并在外部动作间复核许可；不会让禁用来源排在队首而阻塞旧任务。已经发出的外部调用无法原子撤回，必须保留回执并对账。配置错误使新入口关闭式失败，不能回退旧认证。

稳定错误码包括 INVALID_REQUEST、INVALID_SOURCE_CREDENTIAL、SOURCE_OPERATION_DENIED、INGRESS_DISABLED、RECEIPT_NOT_FOUND、IDEMPOTENCY_CONFLICT、SOURCE_NAMESPACE_CONFLICT、CORRECTION_CONFLICT、TASK_RUNNING、INTEGRITY_MISMATCH、SIGNING_NOT_READY、DEPENDENCY_UNAVAILABLE、PERSISTENCE_UNAVAILABLE。错误正文不包含底层连接配置或异常详情。

## 原文归档与旧入口隔离

数据库用 LEGACY_V1/GENERIC_V1 分类。旧记录仍强制非空 batch_id；通用记录强制 batch_id=NULL 和 signing_required=true。首次请求原文字节、摘要和长度不可由运行角色更新或删除；来源归属表也不可由运行角色修改或删除。只有原文 CID 和错误码可按任务更新。

通用 canonical/清单使用 schemaVersion=2，先归档首次原文，再归档含 canonical 全文及原文 CID/摘要/长度的清单。链登记复用现有摘要结构和 contractVersion=2，不把 payload 直接写入链上。原文与清单的取回均校验精确字节及摘要。

旧台账、详情、溯源、任务列表/重试、钱包签名列表、核验和ZIP导出只处理旧记录。同组织 IAM 用户也不能用 `/api/v1/events/{id}/verify` 或 `/export` 读取通用记录，返回404。首版无对外通用原文下载或ZIP接口；内部 schema-2 校验能力不构成会话访问授权。操作员可在获准独立资源中使用[只读原文核验工具](generic-verification.md)，分别校验三种摘要与链登记；默认不连接资源，显式执行及保存原文均受私有允许名单和输出限制。旧 canonical、清单及导出结构不重新序列化成新版本。

## 联调工具

`scripts/generic-evidence.py` 只连接经维护方明确列入私有允许名单的独立回环实例。默认所有操作均预演，不连接网络；真正查询也需要 `--send`。模板 [generic-client-targets.example.json](../deploy/generic-client-targets.example.json) 默认关闭，示例端口不是在线环境承诺。

私有 credential 文件仅含 `{"token":"<new-private-token>"}`；使用权限受限文件传入，不把 token 放命令行、Git或共享日志。允许名单须指定 purpose=dedicated-generic-test、origin、instanceId、enabled=true。只接受规范的 `http://127.0.0.1:<port>`，拒绝旧应用端口、远端、localhost别名、代理和重定向。发业务请求前核对 /capabilities 的实例名、隔离开关及版本。该回环检查仍要求维护方确保对应端口或隧道实际属于获准测试实例。

```sh
python scripts/generic-evidence.py submit --target-file .local/targets.json --target local-example --input examples/generic-maintenance.json
# 实际发送或查询时另加 --credential-file .local/source-token.json --send
python scripts/generic-evidence.py status --target-file .local/targets.json --target local-example --source-event synthetic-maintenance-001
python scripts/generic-evidence.py wait --target-file .local/targets.json --target local-example --source-event synthetic-maintenance-001 --timeout 60
python scripts/generic-evidence.py retry --target-file .local/targets.json --target local-example --id 00000000-0000-0000-0000-000000000001
```

工具保持输入文件精确字节。wait只读轮询，不自动retry；COMMITTED退出0，ACTION_REQUIRED退出2，等待超时退出3；本地错误、HTTP或网络结果未知退出1。发送超时应按原号查询，不能把重发脚本当成真实来源系统outbox。

## 迁移与验证

按隔离实例运维流程使用迁移角色执行 Liquibase。005 迁移为已有记录设置 LEGACY_V1、保留已有来源命名、建立分类约束与原文表。运行账号不执行DDL；不要手工删除迁移记录或仅取消batch NOT NULL来替代迁移。停用新入口可暂停消费，不能回滚删除已受理原文。

`mvn test`运行离线测试。真实SQL条件测试需显式设置 TRUST_GENERIC_DB_TEST=1、专用本机 TRUST_TEST_DB_URL、受限 TRUST_DB_USER/PASSWORD 及迁移测试凭据。测试默认目标为 `127.0.0.1:25432/trust_iam_dev_test`；需要新建条件测试库时，可显式设置 `TRUST_TEST_DB_NAME=trust_iam_test_<suffix>`，仅测试 helper 接受此受限命名，端口与回环检查仍生效，不改变生产隔离允许名单。GenericMigrationDatabaseTest 用迁移角色执行实际迁移及授权、用应用角色复验限制；GenericDatabaseIntegrationTest 用受限角色验证事务、并发和归档恢复。迁移升级测试仅创建并清理自己的随机schema。

仅本机 PostgreSQL 兼容测试可额外设置 TRUST_TEST_POSTGRESQL=1 并使用 jdbc:postgresql URL；PostgreSQL 驱动仅为test依赖，不改变生产 openGauss 驱动及隔离允许名单。TRUST_DB_INTEGRATION 与 TRUST_IDENTITY_DB_TEST 可分别启用旧链路和身份条件回归。不得把 PostgreSQL、CA/Fabric替身或未运行条件测试写成真实组件通过。

进程级用例 `actualJvmCrashAfterCommitRecoversWithoutSecondLedgerSubmission` 启动独立Java进程，先持久受理，在替身账本落盘且PREPARED落库后直接halt，再启动另一个进程接管过期租约。它检查不同PID、首次原文字节、单次签名尝试、账本回执不改及COMMITTED恢复；IPFS/Fabric仍是本机持久替身，不证明真实网络故障恢复。实际部署、真实网络策略和组件验收由维护方另行安排。

旧附件持久上传使用Linux目录fsync，Windows上相应旧上传综合测试无法完整运行；需在获准Linux环境复验，不能改弱生产持久化来获得测试通过。实际执行数字、日志、配置、PID和回执只放 `.local/`。
