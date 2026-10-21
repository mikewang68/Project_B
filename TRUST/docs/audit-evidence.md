# 操作日志存证与 TRUST 自身日志

本页为保留的日志专用接口。通用入口主方案见 [OPERATION_LOG 接入方案](operation-log-generic.md)，两者凭据、摘要和归档语义不得混用。

机器可读契约见 [audit-openapi.json](audit-openapi.json)。

本接口接收 AUDIT v1 `LogEvent`；与 WMS、通用 JSON、身份 API 的服务凭据和路由隔离。浏览器不能调用本接口或持有服务凭据。应用代码仅实现 TRUST 侧；AUDIT 手工补办调用、其他模块的页面采集和事务发件箱由相应负责人适配。

## 配置与默认行为

将 `deploy/audit-log.example.json` 按实例实际信息配置到 `runtime/secrets/audit-log.json`。示例默认关闭服务入口和对外投递，缺少配置不发起日志外连。管理者 `managerUserIds` 是真实 IAM 稳定用户 ID，默认空列表。组织到 domain/zone 的映射必须明确填写 `delivery.orgScopes`，不能推测或由浏览器决定；未映射记录留在本地待办。

每个日志服务来源配置精确的 domain/zone/app 三元组、`submit/read/retry/restore` 权限和独立凭据 SHA-256。不得复用 WMS、通用存证、身份 API 的凭据。`genericSourceId` 必须指向 `evidence-ingress.json` 中单独配置的日志投影来源，只允许 `AUDIT_LOG`，配备经批准的专用钱包和链授权。不要借用 MANUAL、WMS 或已有业务类型。

归档密钥环是单独的受限文件，格式为 `{"keys":{"archive-v1":"<Base64 of 32 random bytes>"}}`。不得使用钱包私钥、示例零密钥或将密钥提交 Git。`activeKeyId` 只决定新记录的密钥；保留旧 keyId 和旧密钥才能恢复历史记录。部署账号备份密钥环，运行账号只读，密钥备份与密文备份分离保存。

## 对外接口

基址 `/api/v1/integrations/audit/events`，仅专属 Bearer 凭据，JSON UTF-8，单条最多 65,536 字节。不存在默认全组织授权；公开实例应按实际吞吐在已有入口配置适当限流，当前应用不宣称提供分布式配额。

| 方法和路径 | 行为 |
| --- | --- |
| POST 基址 | `{eventKey,eventId,payloadHash,event}`；先保存加密原文及任务再返回 202；同键同摘要回原记录 200，异文 409 |
| GET `/{eventKey}` | 查询 `eventKey,payloadHash,state,transactionId,archiveState,chainState,errorCode`；跨来源或未授权范围查询 404 |
| POST `/{eventKey}/retry` | 请求体必须 `{}`；需要 retry；运行中 409；仅显式恢复失败任务，READY/DONE 不重复排队 |
| GET `/{eventKey}/original` | 需要 restore；默认从 IPFS 取密文，校验、解密后返回 canonicalJson，响应禁止缓存；`fromArchive=false` 明确使用数据库密文备份 |
| GET `/{eventKey}/verification` | 需要 read；核验投影原文、归档摘要和账本登记的一致性；未完成 409、核验未确认 503，不返回原文 |

规范化与 AUDIT `CanonicalJson` 一致：字段键排序、UTF-8、完整 `LogEvent` 字段、Instant 时间和 int64 耗时。原文保留纳秒和最大 int64；不通过 JavaScript 浮点重新计算摘要。事件键为 `SHA256(domainId + '\n' + zoneId + '\n' + appCode + '\n' + eventId)`。附带的时间、用户和行为是来源系统声明；摘要核验不证明这些业务事实真实。

回执 `ACCEPTED/PENDING/COMMITTED/FAILED` 与原 AUDIT ChainWorker 兼容。`COMMITTED` 只来自既有 Generic 任务的有效提交状态与非空交易编号。单独的 archiveState 与 chainState 防止将已归档误认为已上链。正文 AES-256-GCM 加密，AAD 绑定事件键、原文摘要和 keyId；IPFS 仅存密文。链投影仅含摘要、事件键、密文 CID/摘要和 keyId，不含日志正文。投影时间遵守 Generic 的微秒上限；原日志时间与摘要不改变。

AUDIT 的既有手工重试只重置其自身发送任务。其负责人需先调用 TRUST retry（并处理权限/运行中冲突），再恢复 AUDIT 的查询投递；仅重复查询不会解除 TRUST 的终止性失败。未知交易继续使用现有 PREPARED/UNKNOWN 账本核对流程，不以重新提交制造重复登记。

TRUST 向 AUDIT 的自身日志投递在 HTTP 401/403/409 等终止错误、无效回执或连续 30 次暂时失败后停止自动重试，记录 FAILED。管理者通过 `GET /api/v1/audit/delivery` 查看待办元数据，修复依赖后调用 `POST /api/v1/audit/delivery/{id}/retry`（`{"reason":"原因"}`）显式补办；只重排 FAILED，既有正文和事件 ID 不改变。没有 domain/zone 映射的记录继续保留，补齐配置后自动发送。

## 页面操作与设置

`POST /api/v1/audit/page-actions` 接收 `requestId(UUID), action, menuId, objectId, path, summary`，返回 `{requestId,state:"RECORDED"}`。身份及组织来自每次核实的真实 IAM 会话。页面收到持久化回执后才执行操作；后续业务 HTTP 使用 `X-TRUST-Page-Action-Id`，服务端检查动作属于当前用户和组织。`POST /page-actions/{id}/result` 接收 `outcome(SUCCESS/FAILED/DENIED),summary`，只是 CLIENT 观察结果，不代替 BUSINESS 结果或有效交易回执。

`GET /api/v1/audit/settings` 返回 canManage、collectionEnabled、archivePaused、chainPaused、pendingCount、failedCount、oldestPendingAt、lastError 及 deliveryPendingCount/archivePendingCount/chainPendingCount。管理者看全局处理积压，其他用户仅看映射到自身组织的统计。`PATCH` 必须同时填写两个布尔开关及 1～300 字符 reason；读审计权限不授予管理权限。设置变更和拒绝记录留痕。暂停不会关闭采集或删除任务；已发出的网络操作可能结束，暂停阻止后续调度。

本轮业务页面首次进入加载、之后手动刷新；不使用周期业务 GET。所有已鉴权业务读取和修改请求均持久留痕，页面查询与业务结果独立分类并关联。主动查询运行状态、日志设置和投递待办同样记录 REQUEST 及其结果、核验页面动作关联归属；只有基础健康探针、页面采集基础设施、服务凭据入口与后台内部处理不再次送链。读取类本地旧审计不会重复发送为 BUSINESS。日志恢复访问保留追加式本地访问记录，避免恢复/补办递归产生新的日志链任务。模拟 IAM 的本地日志固定归入 `SIMULATED_LOCAL` 并清空可信操作人，不向 AUDIT 投递。

投递的 5 秒总期限包含响应正文接收，回执最多 65,536 字节；返回响应头后迟迟不结束正文也会取消连接并保留待办。暂停上链阻止已生成投影的新提交，仍允许查询账本对账并确认已经发生的有效交易；解除暂停不会为同一事件制造新编号。

密码、令牌、逐键输入、查询字符串、表单正文不得进入 summary。无法本地持久化请求时阻止正常业务动作；远端 AUDIT 离线只积压本地发件箱。HTTP 处理结果缺失时保留 STARTED，不能声称业务已回滚。已验证会话的退出是受控恢复入口：无需在线 IAM；本地审计故障仍可清除会话并写安全运行告警，不把无法记录的退出声称审计成功。

## 原文恢复与保留

不物理删除审计原文、动作及投递记录。先备份数据库（含密文、CID、keyId、任务和绑定），再备份 IPFS 已 pin 的密文和原有通用存证清单，最后单独备份完整密钥环。只备份链上摘要不能恢复原文。

恢复演练必须分别验证：AUDIT 原文不可用时从 TRUST 的 IPFS 归档取回；IPFS 暂不可用时从数据库密文备份恢复；旧 keyId 轮换后仍可解密；密文/摘要篡改被拒绝；交易未完成不返回核验通过。恢复到 AUDIT 数据库由其负责人完成，TRUST 只提供规范化原文与核验结果。

本地测试使用专用 PostgreSQL，IPFS/链可以为明确标注的替身；这不等于真实 openGauss、Fabric、IPFS 或其他模块接入验收。真实组件写入须采用批准的测试来源、通道、钱包与授权方案。
