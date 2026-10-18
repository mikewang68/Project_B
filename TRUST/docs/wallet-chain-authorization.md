# 钱包链授权状态

钱包页的“链授权状态”按当前来源绑定、证书指纹和指定事件类型查询。需要 IAM 实名会话及 `trust:wallet:read`。组织来自可信登录身份；来源只能选择当前组织已经绑定的钱包，历史绑定和其他组织钱包统一返回 404。

`GET /api/v1/wallets/{id}/chain-authorization?sourceSystem=SOURCE&eventType=EVENT_TYPE` 返回 `status`、UTC `checkedAt`、证书指纹、来源、类型、钱包状态及各组织查询可用性；不返回私钥引用、服务地址或底层错误。查询每次重新读取，不持久缓存授权结论。

| 状态 | 含义 |
|---|---|
| AUTHORIZED | 配置组织观察一致，策略开启、MSP 匹配且类型获准 |
| UNAUTHORIZED | 一致观察显示策略不存在、停用、MSP 不匹配或类型未获准 |
| UNKNOWN | 覆盖不足、某组织查询失败、配置或策略格式无效 |
| INCONSISTENT | 组织观察的策略内容、版本或存在性不同，需要维护方核对 |

授权状态与钱包状态分开：链授权不保证证书仍有效、应用钱包仍启用、链码背书策略满足或之后的交易成功。结果只是查询时点的组织观察，不能冒充全网最终一致性证明。到货事件以实际 API 的 `WAREHOUSE_IN` 查询，不把 GUI 名称当事件类型。

## 运维配置

配置 Spring 属性 `trust.signer-query-organizations=Org1MSP,Org2MSP`，必须由维护者明确指定本通道要求观察的全部组织；默认空列表，显示 UNKNOWN。最少 2 个、最多 8 个不同组织。沿用实例的 Fabric endpoint、TLS CA、channel、contract 与 `trust.fabric-query-key-ref`；查询证书 MSP 用 `trust.signer-query-msp`（默认 Org1MSP）。缺少查询密钥引用不会回退旧明文身份。

查询身份必须是普通已登记的只读查询身份，禁止管理员证书。程序拒绝 CN 以 admin 开头的证书，且仅调用 `GetSigner`，不会执行 `SetSigner`、endorse 或 submit。部署审核还须检查证书属性与链上策略，CN 检查不能替代权限审查。

SDK 使用独立的 `EvaluateRequest.targetOrganizations` 依次指定各组织，由 Gateway 在指定组织选择 Peer；不是将同一默认 Peer 读两次，也不保证逐一读取组织内每个 Peer。每组织上限 5 秒。不提供由浏览器指定的 Peer、通道或组织参数。

历史策略省略/null `eventTypes` 只兼容 `WAREHOUSE_IN`、`DISPATCH`；空数组不授权。节点观察不同版本也显示不一致，不能只看其中一个返回成功。

## 维护交接

未授权时交付来源、指纹、事件类型、查询时间及当前批准绑定申请给链维护负责人。由负责人核实专用通道策略并按网络流程操作；业务应用不持管理员私钥。负责人完成后重新查询，并通过实际有效交易验证。若查询失败，检查只读身份、TLS、组织覆盖及 GetSigner 兼容性，禁止通过扩大签名权限让查询通过。

验收包括未授权、合法授权、缺少一个组织、组织结果不同、历史缺省类型、当前绑定更换、跨组织访问和已停用钱包。真实网络测试只在批准的独立资源执行。
