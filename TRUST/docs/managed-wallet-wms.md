# 托管钱包与 WMS 接收契约

TRUST 提供托管签名、组织/来源绑定、双人复核和 WMS 接收端。IAM 身份供给见 [身份集成说明](identity-delivery.md)；WMS 业务事务快照、outbox 和正式来源部署由 WMS 维护方负责。模拟样例与真实业务接入必须分别验证。

## 身份与托管钱包

TRUST 通过 IAM `/auth/login`、`/auth/me` 适配登录和持续授权检查；不复制人员密码库，不共享 JWT 密钥，不跨库读取 IAM。令牌仅保存在服务端会话，退出销毁 TRUST 会话。每次请求重新核对稳定用户 ID、组织与权限，失联、撤权、组织变化均拒绝访问，不使用开发管理员降级。

`runtime/secrets/integration.json` 明确 IAM 组织编码到 TRUST 组织的映射。默认真实模式；模拟模式必须同时明确启用隔离配置，限定预置测试用户，页面持续展示“隔离测试/模拟 IAM 身份”。开发账号仅用于原通用功能兼容，不能管理钱包。

钱包管理至少分配 `trust:wallet:read`、申请权限 `trust:wallet:manage`、复核权限 `trust:wallet:review`。申请人与复核人按稳定身份 ID 区分，同一身份即使同时拥有两种权限也不能自审；另一组织即使具有复核权限也不能操作本组织申请。权限目录与人员授权由 IAM 管理。

运维在隔离应用的唯一 TRUST_ROOT 内预置证书和加密私钥，通过 `deploy/ProvisionWallet.java` 生成不能覆盖的密钥引用。私钥使用 AES-256-GCM、随机 nonce 和引用作为附加认证数据；主密钥、加密材料、证书私有配置不进入 Git。应用检查有效期、证书与私钥匹配、证书指纹，再进行登记申请、另一人复核、来源绑定申请与复核。新增证书版本保留旧历史，不覆盖历史材料。

平台登记不等于链上授权。`GET /api/v1/wallets/requests/{id}/policy?revision=1` 导出当前已批准绑定的授权策略，由网络管理员核查后只在专用隔离通道登记。策略连续版本、审批标识、实际 MSP、证书指纹、来源系统和事件类型应一致。普通应用不持有网络管理员密钥。

每次签名应保留实际证书、钱包版本、当时绑定与审批、业务操作人与服务提交身份、交易号及结果。平台停用阻止新的签名，不撤销已经发出的交易，也不等于联盟 CA 吊销。完整 CA 生命周期、多组织生产治理和 HSM 不在本次范围。

## 隔离网络与升级兼容

通道升级验证应使用独立测试通道和制品：先写入旧版样例，再按实际 sequence 升级，逐字段核对原文、摘要、CID、交易关联和历史。进程、端口和制品均应隔离；本说明不授权升级既有业务通道。

新版合约提供 RegisterEvent、AppendCorrection、GetEvent、GetEventHistory、SetSigner、GetSigner。托管记录保留 walletId、signerFingerprint、sourceSystem、eventType、contractVersion；旧记录不补写字段、不重算摘要。2.1 合约在 SignerPolicy 中使用明确的 `eventTypes` 白名单，对所有托管来源名称都生效，不能通过将来源改成 WMS-SIM 绕过。TRUST 当前策略导出只含 WAREHOUSE_IN 与 DISPATCH。SetSigner 检查非空数组、最多80项、大写字母/下划线格式，去重并排序；历史策略省略或 null 时仅兼容这两类，显式空数组拒绝。授权读取不回写历史策略。有效背书不等于有效提交，应用须确认提交状态；提交确认不确定时先查询并对账，不能直接重复生成业务记录。

## TRUST 接收端边界

`POST /api/v1/integrations/wms/events` 使用独立来源 Bearer 凭据；TRUST 私有配置只保存凭据 SHA-256，并固定组织、sourceSystem、公司/仓库/货主范围。该身份只能接收事件和查询本服务回执，不能管理钱包或查询其他来源事件。

| 字段 | 契约 |
|---|---|
| sourceSystem/sourceEventId | 来源命名空间和稳定事件号；重试原事件时保持不变。 |
| eventType | 首批仅 WAREHOUSE_IN（确认收货）和 DISPATCH（实际发运）。 |
| batchId/quantity/unit | 真实批次、严格正数量、实际单位；分运编号不能替代原批次。 |
| details | 必须含 companyCode、warehouseCode、ownerCode、稳定 operatorId；建议保留事件发生时 operatorName。发运另需 allocationId。 |
| relatedEventRefs | 发运至少一项，格式 `sourceSystem:sourceEventId`，同来源其他收货记录。收货事件不填。 |
| evidenceIds | 仅引用真实上传附件。没有质检或交接文件时留空，并明确 NOT_PROVIDED，不能生成虚假附件。 |

已到达的来源记录必须是同组织、同来源、同批次、同单位、同公司/仓库/货主，且收货业务时间不晚于发运。允许先子后父：发运受理后 `missingReferences` 显示未到达的来源；收货到达时再次检查已经保存的发运快照，一致才受理并动态消除缺失。错误的父编号不能靠把同批次集合当父子来补齐。关联校验与事件、任务、审计在同一事务内，来源接收事务串行校验到达顺序，避免并发双方都绕过检查。

同组织＋来源系统＋来源事件号构成幂等键。归一化内容相同返回原事件，不同内容返回 409；不得重试时重新读取当前主数据改变旧快照。202 仅表示本地事务已可靠接收。使用回执内部 id 查询 `GET /api/v1/integrations/wms/events/{id}`，只有 fileState=STORED 且 chainState=COMMITTED 才是存证完成。

EVENT 溯源输入为 `sourceSystem:sourceEventId`，不能填回执内部 UUID。BATCH/BUNDLE/HANDOVER 只决定检索起点，后续遍历显式 EVENT 关联。事件详情、溯源和导出应使用同一原始 relatedEventRefs；同批次未关联收货不会自动成为发运来源。数量用于记录业务事实；多来源引用本身不表示各次收货消耗比例，也不构成库存余额核算。

## 模拟业务和维护方交接

[模拟样例](../examples/wms-100-60-40.json) 明确模拟 100 吨收货和 60/40 吨发运，三条记录保留同一原批次，两条发运显式引用收货事件，附件为空。`tests/send-wms-example.py` 仅向指定隔离入口发送固定样例并输出回执（执行者应重定向至私有证据目录），不是可靠事务推送或生产 outbox。

WMS 维护方需要在自身业务库存事务中固化不可变快照和可靠待发送记录，事务回滚不遗留快照；发送器持久记录回执和后续状态，超时重传原快照。实际 SHIP 必须使用 allocation/库存批次/收货的真实关联。真实业务触发、outbox 迁移、重试运维、附件来源和部署由 WMS 团队负责。本轮不发布 WMS、不执行 WMS 迁移、不修改其业务流程。

IAM 维护方需要提供现有服务入口、组织映射和实名测试人员，完成权限目录与人员授权；WMS 维护方需要确认业务字段、发生时间、单位与关联规则，并提供真实操作生成快照的证据。真实 IAM/WMS 未接通时，验收只能声明模拟身份和模拟业务驱动真实 TRUST 后端、数据库、IPFS/Fabric 的结果。

测试须分别记录离线单测、真实专用库事务、真实 IPFS/Fabric、页面桩和真实后端页面。数据库只使用显式专用 `trust_wallet_dev_test`，禁止复用 `trust`、既有 `trust_test` 或恢复库。报告、原始证据和脱敏摘要全部放本地 `.local/test-results`，不提交 Git。
