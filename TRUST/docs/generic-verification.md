# 通用原文只读核验工具

`com.bproject.trust.ingress.GenericEvidenceVerify` 是操作员在获准独立测试资源中使用的命令入口。它按组织、来源命名、稳定来源主体及事件 ID 精确读取一个 `GENERIC_V1` 记录，取回 IPFS 原文与清单，复算原文、canonical、manifest 三种 SHA256，再查询 Fabric `GetEvent` 并逐字段比对登记。只有任务 DONE、文件 STORED、链状态 COMMITTED 且全部核对成功时才返回 VERIFIED。

这是独立命令，不启动 Spring 应用、后台任务或 HTTP 服务，不迁移数据库、不上传或 pin IPFS 对象、不签署提交链交易。它使用操作员已有的数据库及链查询身份，不接受来源 Bearer token，也不扩大来源 `read` 权限。网页和机器回执接口仍不能下载通用原文。

## 准备目标及私有材料

1. 维护方基于 [允许名单模板](../deploy/generic-verification-allowlist.example.json) 确认专用数据库、角色、实例根目录、端口、IPFS 和 Gateway 回环映射、通道、合约、TLS 名称、查询 MSP 和证书 SHA256 指纹，再明确启用目标。模板默认关闭，示例不代表在线资源。
2. 操作员另存 [单次计划](../deploy/generic-verification-plan.example.json)，其 `instanceId` 选择允许名单条目，`target` 必须与该条目逐项一致，并填写准确的 `orgId/sourceSystem/sourceId/eventId`。计划不能自行授权新的端点、通道或查询身份。两个配置必须是不同的文件。
3. 数据库凭据文件仅含 `databasePassword` 字段，账号由允许名单固定。凭据文件放在目标 TRUST 的 `.local/` 或 `runtime/secrets/` 下。查询材料复用 `runtime/secrets/fabric/tls-ca.crt`、`runtime/secrets/wallets/<queryKeyRef>/certificate.pem`、`private-key.enc` 和 wallets 下的 `master.key`；工具核对证书有效期及允许名单中的 DER SHA256 指纹。不要为工具重新授权钱包、修改链策略或复制管理员私钥。
4. 确认回环端口或隧道实际通向获准测试资源。工具保留既有 `IdentityInstance` 隔离限制，拒绝受保护实例、远端 IPFS/Gateway、代理和 HTTP 重定向。数据库继续使用生产 openGauss 驱动，不开放 PostgreSQL 运行开关。
5. 在目标 TRUST 下准备 `.local/` 父目录；每次输出使用尚不存在的子目录。所有路径使用规范绝对路径，不含符号链接、目录联接或 `..`。实际计划、配置、凭据、原文和报告均不提交 Git 或放入 PR。

## 运行

先在 TRUST/backend 构建发布 JAR，再用其内置 PropertiesLauncher 选择命令入口。以下路径均须替换为已核实的私有路径；没有 `--execute` 时只检查计划，不读取凭据、不连接资源、不创建输出。

```sh
java -Dloader.main=com.bproject.trust.ingress.GenericEvidenceVerify \
  -cp /path/to/trust-platform-0.1.0.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --allowlist /path/to/isolated/TRUST/.local/verification-allowlist.json \
  --plan /path/to/isolated/TRUST/.local/verification-plan.json
```

实际核验在上述命令末尾显式增加：

```sh
--execute \
--credentials /path/to/isolated/TRUST/.local/verification-db.json \
--output /path/to/isolated/TRUST/.local/verification-run-unique
```

默认只保存 `report.json`。需要取回已核验原文时另加 `--save-original`：全部核对和资源关闭成功后，才写 `source-request.json`（首次 HTTP 请求的精确 UTF-8 字节）及 `evidence-manifest.json`。不覆盖已有目录或文件，不生成 ZIP；payload 中的附件 URL/CID 不会被下载。

标准输出只含状态、摘要或稳定错误码，不输出凭据、业务原文及底层异常。DRY_RUN 和 VERIFIED 退出码为 0，FAILED 为 1。数据库使用只读、可重复读事务并以回滚关闭；IPFS 只调用 cat，链客户端仅调用 GetEvent。归档响应有大小和时间上限。

## 如何解释结果

- `hashVerification=MATCH`：首次原文字节、长度、规范记录投影、清单和各自摘要一致。
- `ledgerRegistration=MATCH`：查询所得的当前链登记与本地预期登记字段一致；不是独立离线密码学账本证明。
- `VERIFIED`：以上两项均完成。记录不存在、范围不符、任务未完成、链上查无记录或依赖不可用都不能获得此状态。
- 报告的 `notProven` 明确列出 CA/CRL 当前状态、业务事实真实性及离线密码学账本证明，工具不对这些作保证。

`TARGET_NOT_APPROVED` 表示计划与允许名单不符；`TARGET_NOT_FOUND` 不区分不存在和范围不符；`TARGET_NOT_COMPLETE` 表示本地尚未达到可核验状态；`INTEGRITY_MISMATCH`、`LEDGER_RECORD_MISSING`、`LEDGER_MISMATCH` 分别表示材料不一致、未查到登记或登记不匹配。配置、路径、输出和查询身份也有独立拒绝码。失败时可在已经安全创建的输出目录保留摘要报告，但不会因哈希校验单独通过就保存原文或报告 VERIFIED。

核验命令不会重试处理任务，也不会修正异常数据。发现问题后按[通用接入说明](generic-evidence.md)保留记录、查询回执并走已授权的显式 retry；真实资源未获准时只进行离线验证，不改外部授权来使工具通过。
