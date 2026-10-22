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

首次确定尚未发送的记录，先持久保存完整请求字节、摘要和发送意图，再 POST；不要求先空查。可能已经发送或结果未知时，先 `GET /by-source-event/{sourceEventId}`。只有实例、来源、凭据正确、入口启用且明确返回 `RECEIPT_NOT_FOUND`，才以原号原文重新提交；入口关闭、普通 404 或网关错误不能证明未受理。受理后保存内部 id，后续 `GET /{id}`。号码、已知 id 或摘要漂移及 409 都进入人工核查。

ACCEPTED/PROCESSING/RETRY_WAIT/CONFIRMING 均不是上链成功。COMMITTED 需与有效 txId 和记录相符。ACTION_REQUIRED 需要核查并显式补办；outcomeUnknown=true 时不能声称链上无交易。即使调用方显示四态，也须保留原始状态、未知标记和错误码。

获准后以空对象调用 `POST /{id}/retry`；不改事件、原文或内部 id。运行中为 409，READY/DONE 不重复排队。AUDIT 自身重试不能代替 TRUST 补办，查询也不会恢复终止性失败。

## 归档与兼容边界

通用入口将完整 JSON 原文和含 canonical 的清单归档到 IPFS，当前不加密；链上登记摘要及 CID。真实启用前须确认内容许可、可见范围和保留要求。原文和 canonical 各不超过 262144 字节。当前无供来源方使用的通用原文下载/恢复 API，运维核验工具不等于业务恢复接口。

[日志专用接口](audit-evidence.md) 保留兼容：请求 eventKey/eventId/payloadHash/event、加密正文、内部 AUDIT_LOG、单独授权恢复。两套路径、凭据、摘要对象、状态与归档语义不可混用，也不应让同一业务日志同时走两条链路。

真实联调前须确认：新版 ChainWorker 提交版本、字段与摘要向量、来源矩阵、全文归档许可、实例和访问路径、read/submit 凭据交付、retry 决策、吞吐/入口限流、补传及未知结果对账。材料准备完成不代表上述事项已经确认。

## TRUST 维护方的准备与默认联调标准

TRUST 负责来源配置、凭据、钱包绑定、精确链策略、受理后的处理和原文/链核验；业务范围及内容许可由权属方提供，AUDIT 提供运行节点、凭据接收人、日志量与已有投递记录。不要将 TRUST 自己未做完的配置和工具统称为双方待确认。

合成测试使用明显标记的独立来源，不冒用业务 appCode 或正式范围。初始发送并发 1、全局新事件提交不高于每秒 1 次、每事件轮询间隔至少 5 秒；遇持续积压或依赖异常暂停新投递。此值是测试约束，不是已测容量 SLA，也不是服务端强制限额。应用当前不承诺通用入口专属 429/Retry-After；如网关产生 429，先核实发送状态，尊重有效 Retry-After，缺失时采用 5/10/20/30 秒有界退避，持续失败转人工对账，不生成新号。

### 凭据轮换和停止投递

1. AUDIT 暂停该来源新投递，持久保存号码、请求字节及未决回执；TRUST 记录配置摘要与回退配置。
2. TRUST 生成新随机 token，通过受限渠道交指定后端维护人；配置只保存 SHA256。原子替换同一来源的摘要，保留组织、sourceId、sourceSystem、类型和已批准权限。
3. 验证旧 token 失效、新 token 能查询原记录，内部 id 和摘要保持一致，再恢复投递。当前每来源只有一个摘要，没有新旧 token 并行窗口或自动到期能力；轮换需要协调暂停。
4. 若只停止新增投递，移除 submit/retry、保留 read；已受理且来源仍启用的任务继续处理。替换 token 可使泄露的旧凭据失效；禁用来源还会停止该来源任务后续推进，不等同于仅停止客户端。外部已经发出的交易不能撤回，未知结果必须保留并对账。
5. 配置更新前校验所有现有来源、身份供给与 WMS 凭据不冲突，保留原文件所有者和受限权限，使用同目录临时文件原子替换。失败时恢复上一配置并复查凭据和在途状态，不回灌数据库覆盖新记录。

### OPERATION_LOG 精确链策略

旧钱包 API 导出的 eventTypes 仍为 WAREHOUSE_IN/DISPATCH，旧 `signer-policy.sh` 也不是通用日志授权入口；不要直接套用或据此声明 OPERATION_LOG 已获准。

使用 [通用日志维护命令](../scripts/generic-signer-policy.py)，由具有既有网络维护权限的操作员运行。它不替普通应用申请管理员密钥，不改变旧 WMS API。准备两个不同文件：独立允许名单和执行计划，均 schemaVersion=1。

- 允许名单 `targets[targetId]` 固定 enabled、purpose=`isolated-operation-log-policy`、隔离 channel、contract=`evidence`、orderer/ordererTlsCa 和 peers（address/tlsCa）；只允许明确回环端点和 `trust-iam-` 隔离通道。
- `changes[changeId]` 包含 policy、binding、expectedPrevious。policy 使用链契约的 orgId/sourceSystem/mspId/fingerprint/enabled/revision/changeId/eventTypes，类型必须仅为 OPERATION_LOG。首次 expectedPrevious=null/revision=1；更新按批准前态递增版本。
- binding 从本实例当前钱包和绑定申请查询取得，包含 orgId/sourceSystem/mspId/fingerprint、state=APPROVED、action=BIND、walletState=ACTIVE、proposedBy/reviewedBy、requestId/currentBindingRequestId、带时区 observedAt。双人身份必须不同，绑定及指纹须与策略一致，观察超过 15 分钟须重新查询。该文件是维护方的现场观察，不是第三方签名凭证；类型许可需独立批准，不能由绑定审批推导。
- 计划含 targetId/target、changeId/change，值必须与独立允许名单完全一致。默认只检查本地输入；`--check` 查询所有批准 Peer 并输出差异；`--apply` 仅在所有 Peer 与批准前态一致时提交一次。已存在相同策略则不重复提交；分歧或版本冲突停止，提交响应未知先查询，不自动重发。

```sh
python3 scripts/generic-signer-policy.py --allowlist /private/approved.json --plan /private/plan.json
python3 scripts/generic-signer-policy.py --allowlist /private/approved.json --plan /private/plan.json --check
python3 scripts/generic-signer-policy.py --allowlist /private/approved.json --plan /private/plan.json --apply
```

实际基址、来源映射、批准记录、凭据、计划和执行证据放私有目录。合成单端验证、独立实例故障测试、AUDIT 双端联调和真实启用分别记账，不互相替代。
