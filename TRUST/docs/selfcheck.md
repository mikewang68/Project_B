# TRUST 接入自检包

本工具供其他模块在修改存证调用代码后，检查实际请求和调用行为是否符合 TRUST 通用 JSON 存证接口。运行只需 Java 17；不需要 Maven、数据库或完整 TRUST 应用。

只覆盖通用事件的提交、查询及显式 retry。身份 API 和旧 WMS API 不在范围内。所有结果是“本地模拟”，不能证明真实链上交易、数据库事务、IPFS 归档或线上授权正确。

## 接入步骤

1. 从自己的真实业务代码导出请求原始字节，不手工另造一份看似正确的请求替代它。
2. 使用合成来源配置运行 `check`。
3. 启动 `serve`，让自己的真实客户端读取连接文件并切换测试地址、合成凭据。
4. 在自己的测试中断言回执解析、业务状态及重试决策，等待模拟器结束，再执行 `report`。调用方测试和自检报告必须同时成功。

本工具不会扫描任意源码，也不会自动运行外部命令或修改使用者的代码、CI 配置。业务写库与待发送事件是否处于同一事务，必须由调用方自己的业务测试验证。202 只表示受理；接收端无法观察调用方是否提前标记成功，必须在调用方测试里断言。

## 命令

以下命令从交接包所在目录运行；请求、报告和连接文件均使用合成数据。实际检查记录放在私有 `.local` 目录，不提交 Git。

```text
java -jar trust-selfcheck.jar check --config examples/source.json --report .local/check.json examples/request.json
java -jar trust-selfcheck.jar serve --config examples/source.json --scenario normal --seconds 120 --connection .local/connection.json --report .local/serve.json
java -jar trust-selfcheck.jar report --input .local/serve.json --out .local/summary
```

`check` 可在末尾接1～1000个请求文件，直接检查原始字节，避免先解码或重新序列化隐藏重复键、非法编码等缺陷。诊断提供字段位置、规则和建议；数据库中的来源归属、前序记录及真实授权列为未验证。报告以 `request-1` 等顺序对应输入文件，不保存可能含业务信息的文件名。

`serve` 只监听 `127.0.0.1`，默认使用系统空闲端口。连接文件在启动后生成，包含本次地址、合成凭据和场景标识。每次场景独立内存状态；默认 120 秒，允许 1～600 秒。必要请求未到达、场景未完成、异常终止不能判通过。

连接文件的 `baseUrl` 已包含 `/api/v1/integrations/evidence/events`，不要再次拼接该前缀。请求使用 `Authorization: Bearer <token>`。`readOnlyToken`、`otherSourceToken`、`otherOrgToken` 是权限测试夹具，后两者仅用于越界查询；它们不是可提交事件的第二来源。文件还包含 `expiresAt` 和本场景的 `requiredChecks`。普通 JSON 响应带 `X-TRUST-Selfcheck: local-simulation` 标识，故障场景的主动断连不会产生响应。

模拟器运行到配置期限才结算，不提供“提前完成”HTTP 接口。客户端测试结束后应等待 `serve` 退出，再运行 `report`；提前终止不能视为通过。每轮最多 64 次请求，超过后判行为失败，防止无界提交或轮询。

`report` 读取已经结束的记录，生成 `.txt`、`.json`、`.xml`（JUnit）文件。缺失、损坏或未完成记录不得当成成功。每次 `check`、`serve` 必须使用尚不存在的输出路径；每轮用独立目录即可避免把旧成功记录误认为本轮结果。路径必须位于 `.local`，工具不向公开目录写实际报告或连接信息。

| 退出码 | 含义 | CI 处理 |
|---|---|---|
| 0 | 所选检查全部通过 | 仍须同时检查调用方测试结果 |
| 1 | 请求或行为不符合契约 | 阻止本次接入验收，按诊断修正 |
| 2 | 配置错误、执行错误或未完成 | 修复执行条件并重新运行 |

## 来源与样例

`examples/source.json` 只声明合成来源：`sourceId`、`orgId`、`sourceSystem`、`operations` 和 `allowedEventTypes`，不填线上令牌。`read` 为基本权限；`submit`、`retry` 按场景配置。事件类型必须明确许可，不能把 `WAREHOUSE_IN` 或 `DISPATCH` 放入通用入口。

`examples/request.json` 是 JSON-only 示例，提交字段为 `schemaVersion`、`sourceEventId`、`eventType`、`businessObjectId`、`occurredAt`、非空 `payload`，更正时另带 `supersedesId`。附件地址仅为普通业务字段；工具和通用存证均不宣称对应附件内容已存证。

## 调用方必须断言的行为

- 202 后保留待处理状态；只有明确 `COMMITTED` 才标记对应模拟完成。
- 提交断连或 503 结果未知时，使用原 `sourceEventId` 查询恢复，不生成新号再次登记。
- 同号同内容保持幂等；同号异文 409 不能被当作成功或无条件循环重试。
- 等待有期限；超时、`ACTION_REQUIRED`、认证或权限错误应按业务策略停止或交人工处理。
- 查询仅查询；不能在轮询时偷偷发起显式 retry。
- 调用方测试失败时，即使模拟器报告通过，整个接入检查仍失败。

## 场景与必要调用

每个场景重新启动 `serve`，使用新的连接文件。以下 `POST` 指事件提交，查询指带本次合成凭据的 GET。负向响应只在所选场景允许的步骤中属于预期，额外错误会使该场景失败。输入格式和边界的负向样本优先用 `check`；不要向 `normal` 随意加入非法请求后期待通过。

| 场景 | 必要步骤 |
|---|---|
| `normal` | POST 得到202；按原号和id查询，观察首次查询PROCESSING、第二次COMMITTED。 |
| `idempotency` | 首次提交；同号等价但原始字节不同的重放得到200；再同号异文得到409。断言首次原文摘要保持。 |
| `correction` | 首次提交；新号带前序id追加更正；再用新号重复更正同一旧版本得到409；新号带前序但不同业务对象得到400。 |
| `permissions` | 正常提交一条；无凭据和错误凭据各401；readOnlyToken提交403；主token提交合法但未许可类型403；otherSourceToken、otherOrgToken分别查已有id得到404；主token查不存在id得到404。 |
| `disconnect` | 首次POST已经受理但断连；先按原号查询恢复，再按id查询到COMMITTED；不得换号或盲目重发POST。 |
| `unavailable` | 首次POST返回503但可能已受理；先按原号查询，再按id查询到COMMITTED。 |
| `timeout` | POST后至少查询两次，状态保持未完成；调用方另断言等待有界及业务未成功。模拟场景完成不代表该事件上链成功。 |
| `action-required` | POST后查询到ACTION_REQUIRED；调用方另断言业务未成功及人工处理策略。 |
| `retry-ready` | POST后显式retry，200且queued=false。 |
| `retry-done` | 正常202受理后，夹具转为DONE/COMMITTED；显式retry，200且queued=false。 |
| `retry-running` | 正常202受理后，夹具转为RUNNING；显式retry得到409 TASK_RUNNING。 |
| `retry-failed` | POST后先查询ACTION_REQUIRED；retry得到202 queued=true；再次retry得到200 queued=false。 |
| `retry-invalid` | POST后先用非空对象或数组等非法retry请求体得到400；再用{} retry得到200 queued=false。 |

显式 retry 使用 `POST <baseUrl>/<id>/retry`，请求体允许空、JSON空白或 `{}`。模拟器不模拟业务后台工作和真实外部故障，上述状态推进仅是可重复的接口夹具。未在场景里安排的真实数据库竞争、恢复时序和外部授权仍需单独验收。

## Java 示例与 CI

在一个终端启动 `normal`，连接文件生成后在另一个终端运行：

```text
java examples/ClientAdapterExample.java .local/connection.json examples/request.json
```

这是 Java 17 源文件运行方式，不需要 Maven。示例实际发送HTTP请求，检查202、按原号查询、按id查询以及PROCESSING到COMMITTED；失败抛出断言并返回非零。它仅用受限字段提取读取工具生成的平面夹具JSON，不能作为生产JSON解析库。

代码标有两个接入替换点：使用本模块真实客户端与实际生成的请求；读取并断言本模块真实业务状态。默认示例里的内存变量仅演示断言位置，示例自身成功不能证明其他模块的业务状态正确。

CI 应保留三个独立退出码：调用方测试、`serve`、`report`，只有三者均为0才放行。先启动模拟器并等待本轮连接文件，再运行实际客户端测试；无论客户端成功与否，都等待模拟器结算并收集私有报告。不要通过只检查最后一条命令、忽略调用方失败或复用上一轮成功文件获得假通过。每轮使用独立 `.local` 子目录，持续集成系统仅把该目录作为私有测试产物。

## 报告与维护

`check` 也使用共用方法检查新记录首版的规范化内容大小；服务端增加的元数据计入262144字节上限。后续更正的真实版本号、已存在记录的重放及数据库归属仍不能离线确定。HTTP头和调用顺序由 `serve` 检查。

本版工具标识为 `selfcheck-1.0.0`，契约基准为 `generic-v1@7d06990`。报告要求完整的场景检查项；截断、未知版本和异常退出码不会判通过。`completed` 表示检查进程正常结算，不能单独用作通过依据；必须读取退出码及检查项。

报告记录工具构建版本、契约版本、场景、检查项及失败原因，不保存凭据、完整业务原文或原始响应正文。连接文件中的合成凭据仅用于本次本地测试；报告和连接信息都应按私有测试产物管理。

TRUST 接口规则变化时必须同步维护自检场景、共用校验及控制器/OpenAPI 一致性测试。工具通过只能作为接口接入的一部分证据，真实组件验收和各模块业务事务验收应单独记录。

## 维护者构建

维护者在 `TRUST/backend` 使用 Java 17 和 Maven 执行 `mvn package`。独立制品为 `target/trust-platform-0.1.0-selfcheck.jar`，交接时复制并重命名为 `trust-selfcheck.jar`，附上 `selfcheck/examples` 和本说明。使用者只需 Java 17。

原 `target/trust-platform-0.1.0.jar` 仍使用 `TrustApplication` 启动业务服务；自检制品使用 `SelfCheck` 入口，不启动 Spring 应用。修改接口规则时先运行输入、HTTP安全链、模拟器、报告及相关接口回归，再验证实际 `java -jar` 制品和 Java 接入示例。
