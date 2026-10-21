# 日志存证适配协议 v1

> 本文件是旧日志专用兼容协议；新接入默认generic模式，见docs/trust-generic-v1.md。禁止混用凭据、请求或回执，禁止同一日志双路投递。旧模式须显式AUDIT_CHAIN_MODE=legacy。

这是公共日志服务向区块链模块提出的接口契约，不是现有 TRUST 已实现接口的声明。现有仓储事件有批次、数量等业务字段，不应硬塞日志或复用 WMS 服务身份。

配置 AUDIT_CHAIN_BASE_URL 为区块链负责方确认的日志存证接收地址。后台先查询 BASE_URL/{eventKey}，404 时 POST BASE_URL 接收日志；采用 Bearer 受限服务凭据，不跟随 HTTP 重定向。超时后先查回执，避免重复交易。

请求：

```json
{
  "eventKey": "作用域、app和eventId组合生成的SHA-256键",
  "eventId": "来源系统的稳定日志事件编号",
  "payloadHash": "下面event的固定JSON字节摘要",
  "event": {"schemaVersion": 1, "eventId": "example"}
}
```

event 的完整字段见公共日志 OpenAPI。event 原文不含请求正文、密码、Token或API Key。发送方按照键排序、UTF-8、ISO 8601时间序列化事件并计算SHA-256。接收方重新计算摘要，幂等键为 eventKey；同键不同摘要返回409。附带日志全文还是只存摘要，应按项目保密要求确认；链外日志仍在授权数据库保存。

接收和查询回执均返回：

```json
{
  "eventKey": "与请求相同",
  "payloadHash": "与请求相同",
  "state": "ACCEPTED",
  "transactionId": ""
}
```

state 允许 ACCEPTED、PENDING、COMMITTED、FAILED。HTTP 202或ACCEPTED不代表上链成功。只有核实 Fabric 有效提交后，区块链服务才能返回 COMMITTED，并提供真实 transactionId。公共服务核对 eventKey、payloadHash 和交易编号后才标记 COMMITTED；不会自行伪造区块高度、交易编号或存证状态。

404表示该幂等键尚未接收，401/403表示鉴权或授权错误，409表示冲突，429表示限流，5xx表示暂不可用。网络错误指数退避，上限5分钟，连续30次失败进入FAILED。已接收但尚未提交的交易继续轮询，不按网络失败次数误判完成。失败重试只改变投递状态，不修改日志原文。查询必须限制该服务身份对应的来源及作用域。

还需区块链负责方确认：最终路径、鉴权与轮换方式、日志事件类型白名单、单条/批量支持、吞吐限制、保存全文或摘要、有效交易回执字段及核验方法。
