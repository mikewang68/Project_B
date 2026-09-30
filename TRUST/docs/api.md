# 接口语义 v0.2

统一前缀 `/api/v1`。WMS 接收端使用受限服务凭据；IAM 登录与钱包采用平台会话。模拟身份与模拟业务不代表真实 IAM/WMS 已上线，完整字段和安全要求见 [OpenAPI](openapi.json) 与 [托管钱包及 WMS 契约](managed-wallet-wms.md)。

## 登录与身份

1. `GET /csrf` 获取 token，并保存会话 cookie。
2. `POST /iam/login` 以 JSON 提交 username、password、orgId，携带 `X-CSRF-TOKEN`。仅在原开发模式允许 `POST /login` 表单登录；该身份不能管理钱包。
3. 登录成功后再次获取 `/csrf`；后续写请求使用新 token。
4. `GET /me` 获取角色与数据归属；`POST /logout` 退出。

所有查询、下载、核验和导出受数据范围限制。管理员和录入员可录入、上传、更正、补办；查询员可读、核验、导出。审计查询仅限同范围管理员。

## 接口清单

| 方法与资源 | 语义 |
|---|---|
| POST /iam/login | IAM 登录；每次请求重新校验身份、组织与权限 |
| GET /identity-mode | 无需登录，返回 simulated、iamRequired、localLoginEnabled；用于展示身份来源及测试标识 |
| GET /wallets | 本组织钱包、绑定、申请及签名审计 |
| POST /wallets/requests | 登记、绑定、停用申请；需要 manage 权限与原因 |
| POST /wallets/requests/{id}/review | 另一稳定身份复核，拒绝自审、跨组织与旧版本 |
| GET /wallets/requests/{id}/policy | 导出当前已批准绑定策略，revision 显式传入 |
| POST /integrations/wms/events | Bearer 服务身份接收收货或发运；202 不表示上链完成 |
| GET /integrations/wms/events/{id} | 同服务回执查询，id 是内部 UUID |
| POST /evidence | multipart 的 file 字段，返回证据 id、SHA-256 和暂存状态；事件接收后归档 |
| GET /evidence/{id}/download | 从 IPFS 取回并核对摘要，附件方式返回；未归档返回 409 |
| POST /events | 接收事件，数据库事务完成后返回 202；不代表已上链 |
| GET /events | q、page（从 0 开始）、size（1–100），返回 items 与 total |
| GET /events/{id} | 事件、证据、版本与未到达关联记录 |
| POST /events/{id}/corrections | 新事件追加关联版本；必须使用新的来源事件号 |
| POST /imports | UTF-8 JSON 数组或 CSV，返回逐行结果；文件上限 1 MiB、200 行 |
| GET /trace | kind=BATCH/BUNDLE/HANDOVER/EVENT，value 为编号；EVENT 格式为来源系统:来源事件号 |
| GET /tasks | 最近 200 条同范围任务及原因 |
| POST /events/{id}/retry | 重排处理任务；正在处理时返回 409 |
| POST /events/{id}/verify | 即时核验事件、证据引用、IPFS 文件/清单和链上字段 |
| POST /events/{id}/export | 核验通过后返回 ZIP；未通过返回 409 |
| GET /status | 数据库、IPFS、Fabric、同范围事件和任务汇总 |
| GET /audit | 最近 200 条同范围操作审计 |

## 事件输入

必填：sourceSystem、sourceEventId、eventType、businessObjectId、batchId、occurredAt。来源标识使用字母、数字、点、下划线或连字符；eventType 使用大写字母与下划线；时间使用带时区的 ISO 8601。

可选：quantity、unit、location、supplier、receiver、bundleIds、handoverId、relatedBatchIds、relatedEventRefs、evidenceIds、details。数量最多 15 位整数、6 位小数；details 为有长度限制的字符串键值。多值项按集合归一化，数量以十进制保存。

```json
{
  "sourceSystem": "S2-WMS",
  "sourceEventId": "DISPATCH-060",
  "eventType": "DISPATCH",
  "businessObjectId": "STEEL-ORDER-001",
  "batchId": "STEEL-2026-001-60",
  "occurredAt": "2026-09-10T10:00:00+08:00",
  "quantity": 60,
  "unit": "吨",
  "handoverId": "HANDOVER-060",
  "relatedBatchIds": ["STEEL-2026-001"],
  "relatedEventRefs": ["S2-WMS:ARRIVAL-001"],
  "evidenceIds": [],
  "details": {"note": "模拟记录"}
}
```

业务时间与接收时间分别记录；迟到记录可以补齐关联，不通过到达顺序判断真实作业顺序。相同归属、来源系统和事件号构成幂等键；归一化内容相同时返回原记录，不同内容返回 409。来源系统保留业务控制，存证故障不额外改变原业务放行规则。

更正保留原文、原摘要和原版本。更新证据需要上传新文件并在新版本引用；不能在原版本替换附件。更正只能从当前最新版本追加，旧版本分叉被拒绝。

## 状态与失败

- file_state：PENDING / STORED。
- chain_state：PENDING / CONFIRMING / COMMITTED / FAILED。
- tasks.state：READY / RUNNING / DONE / FAILED。

只有文件取回校验成功且链上有效提交后，整体存证才完成。提交超时先查询链上是否已登记；以链上已存在的有效状态恢复结果。交易无效或网络错误不作为完成。

HTTP 400 为输入问题，401 / 403 为身份或操作权限问题，404 为记录不存在或不可访问，409 为冲突/未满足当前操作条件，413 为规模限制，415 为文件类型问题，503 为数据库或可靠接收条件未满足。数据库不可用时不返回“已接收”，来源系统需要保留事件后重试。

EVENT 的 value 必须是 `sourceSystem:sourceEventId`，不是内部 UUID。批次、捆号、交接号只选择检索起点；后续只遍历显式 EVENT 边，不把同批次无关联记录当作父子。溯源最多返回 500 条事件；达到遍历限制时返回 truncated=true。缺失引用列在 missingReferences 中。结果同时保留历史版本，用于查看更正过程，不能把多个版本的数量直接累加为当前库存。

## 存证内容

SHA-256 根据固定 JSON 格式的 UTF-8 实际归档字节计算；对象键稳定排序、十进制数字避免二进制浮点损失。IPFS 清单关联事件原文和每个证据文件的 id、CID、摘要、文件名、类型及长度。

链码提供 RegisterEvent、AppendCorrection、GetEvent、GetEventHistory。托管签名以 SetSigner/GetSigner 策略授权，限制实际 MSP、证书指纹、来源系统与 eventTypes 明确白名单。2.1 合约对所有托管来源检查类型；旧策略缺省仅允许 WAREHOUSE_IN/DISPATCH，显式空数组不放行；旧 B-PROJECT 开发签名保留兼容。两组织 Peer 共同背书。链码不访问外部 IPFS。链上服务身份与 submittedBy 业务操作身份分别保存。

运行状态中的节点名称为职责说明（例如“IPFS 独立节点”），不暴露内部主机名；字段结构保持不变。服务器地址由私有部署配置管理。

## 2026-09-28 IAM联合身份整改

公用身份 API 与生命周期见 [身份集成说明](identity-delivery.md) 和 [identity-v1 OpenAPI](identity-openapi.json)。部署状态、验收报告和运行证据仅在本地保留；源码接口说明不代表实际环境已完成接入。
