# AUDIT 操作日志的通用存证接入方案

本方案供 AUDIT 维护方确认，不表示其客户端已适配或真实来源已启用。主入口 `/api/v1/integrations/evidence/events`；基础协议见 [通用契约](generic-evidence.md) 和 [完整 OpenAPI](openapi.json)。

## 责任与来源

业务模块通过公共日志 SDK/API 汇聚到 AUDIT；AUDIT 保存不可变日志与待发送快照并集中送链；TRUST 负责受理后的持久处理、归档、有效交易核验、回执与显式补办。各端入库、TRUST 受理、归档及上链完成分别记账。

每个约定 domain/zone/app 隔离范围配置独立来源 id、sourceSystem、组织及凭据。同组织不同来源不可共用 sourceSystem。凭据只在 AUDIT 后端使用；read 必需、submit 按需、retry 默认不授。轮换 token 保持来源 id 和命名。

AUDIT 负责正确选路，TRUST 按凭据绑定来源隔离。payload 的 domainId/zoneId/appCode 只是内容，不是授权条件；凭据隔离不证明声明的业务范围真实一致。

## 固定请求映射

| 字段 | 映射 |
|---|---|
| schemaVersion | 整数 1 |
| sourceEventId | `SHA256(domainId + '\n' + zoneId + '\n' + appCode + '\n' + eventId)` 的 64 字符小写十六进制，即原 eventKey |
| eventType | OPERATION_LOG，须独立列入来源白名单及链策略 |
| businessObjectId | 同一个 eventKey，避免原 eventId 的 128 字符上限超过通用字段的 120 字符限制 |
| occurredAt | 原事件时间转 UTC，向下截取到微秒，不四舍五入 |
| payload | `{ "event": <完整不可变 LogEvent>, "payloadHash": <AUDIT canonical 字节 SHA256> }` |
| supersedesId | 普通投递省略；更正用新号并明确关联前序回执 |

原纳秒时间、eventId、用户标记、对象引用和 int64 耗时保留在 payload.event。最大 int64 不可经 JavaScript Number 往返。不得携带密码、密钥、令牌、对话正文或原始请求正文。

发送前持久保存完整 UTF-8 请求字节与摘要；重试不重新生成时间、号码或序列化快照。TRUST 规范等价重放保留首次原文；originalSha256 与本地持久快照不符时停止自动完成判断并核查，不换号绕过。

合成 [请求样例](../examples/audit-operation-log.json) 和 [摘要向量](../examples/audit-operation-log-vectors.json) 覆盖纳秒、128 字符 eventId 和最大 int64，不构成真实业务授权。

## 摘要、回执与恢复

| 摘要 | 对象与边界 |
|---|---|
| payload.payloadHash | AUDIT 完整 LogEvent 的键排序 UTF-8 canonical 字节；通用入口作为业务字段保存，不替来源验证此内层摘要 |
| originalSha256 | 首次完整通用请求原文字节，包括空格和换行；与发送方持久快照核对 |
| eventSha256 | TRUST schema-2 canonical，含内部 ID、来源及版本；须从实际回执和独立核验获得，不是前两者 |

样例给出三种摘要；其中 eventSha256 使用 vectors 的固定合成来源、内部 ID 和版本，对应 [canonical 原始字节](../examples/audit-operation-log-canonical.json)。实际提交由服务端生成内部 ID，因此实际 eventSha256 通常不同，不得把样例值作为实际回执。请求样例末尾含一个 LF，canonical 样例末尾无换行；摘要覆盖文件所有字节。

提交前或结果未知时 `GET /by-source-event/{sourceEventId}`；本来源确实 404 才以原号原文提交。受理后保存内部 id，后续 `GET /{id}`。号码、已知 id 或摘要漂移及 409 都进入人工核查。

ACCEPTED/PROCESSING/RETRY_WAIT/CONFIRMING 均不是上链成功。COMMITTED 需与有效 txId 和记录相符。ACTION_REQUIRED 需要核查并显式补办；outcomeUnknown=true 时不能声称链上无交易。即使调用方显示四态，也须保留原始状态、未知标记和错误码。

获准后以空对象调用 `POST /{id}/retry`；不改事件、原文或内部 id。运行中为 409，READY/DONE 不重复排队。AUDIT 自身重试不能代替 TRUST 补办，查询也不会恢复终止性失败。

## 归档与兼容边界

通用入口将完整 JSON 原文和含 canonical 的清单归档到 IPFS，当前不加密；链上登记摘要及 CID。真实启用前须确认内容许可、可见范围和保留要求。原文和 canonical 各不超过 262144 字节。当前无供来源方使用的通用原文下载/恢复 API，运维核验工具不等于业务恢复接口。

[日志专用接口](audit-evidence.md) 保留兼容：请求 eventKey/eventId/payloadHash/event、加密正文、内部 AUDIT_LOG、单独授权恢复。两套路径、凭据、摘要对象、状态与归档语义不可混用，也不应让同一业务日志同时走两条链路。

真实联调前须确认：新版 ChainWorker 提交版本、字段与摘要向量、来源矩阵、全文归档许可、实例和访问路径、read/submit 凭据交付、retry 决策、吞吐/入口限流、补传及未知结果对账。材料准备完成不代表上述事项已经确认。
