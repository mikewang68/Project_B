# TRUST＋IAM 身份集成说明

配置与协议版本为 identity-v1。本文说明接口、配置和运维约束；实际环境清单、授权、验收结论、制品回执和现场脚本仅存于本地私有目录。

## 边界和可靠流程

IAM 持有业务用户、组织、权限、会话及持久任务；TRUST 持有稳定身份映射、证书版本、加密托管、CA/Gateway 适配及审计。唯一主体为 issuer＋tenant＋稳定 userId；用户名改变不创建新主体。服务端将公司代码映射为组织，再将组织映射到 MSP、CA、affiliation、允许的 app.* 属性。业务用户证书没有 registrar、revoker 或网络管理员属性。

每个 issuer/tenant 只配置一个服务调用方，作为 revision 的唯一所有者。IAM 管理的主体只能通过 IAM 生命周期接口修改；不得另外用 TRUST rotate/disable/revoke 接口绕过 IAM 的 revision。独立调用方可以使用完整公用 API，但必须自行持久化 revision 和幂等键。读、创建、重试、轮换、停用、撤销由凭据的 operations 控制，且 orgs 限制原组织和目标组织。服务凭据独立于 WMS，浏览器会话不能替代它。所有会触发CA吊销的命令都要求 `revoke`，包括 `SYNC + desiredState=DELETED`；`disable` 仅允许平台停用，不能借逻辑删除触发吊销。

IAM 创建事务提交后才进行远程调用，等待窗口约 5 秒（不含本地数据库及密码计算耗时）。正常 201 带 READY 身份；202 带持久 taskId 和状态。客户端重试必须保留原幂等键和请求体；改变请求内容使用新键。同键异文返回409。任务使用持久租约和 revision/lease 比较更新，进程重启或迟到响应不能覆盖较新的停用/删除状态。服务通信失败会保留任务自动重试；不得直接删除任务或调大权限解决失败。

TRUST 给每个证书版本分配独立 enrollmentId；注册秘密先持久化，官方 CA `maxenrollments=1` 防止同版本二次有效签发。响应丢失时从官方 certificate list 回查，匹配本地 BCCSP 私钥后导入已有 AES-GCM 托管。未完成 job 的私钥、secret、CLI config 保留在0700目录用于恢复，成功后删除；加密导入后崩溃也会再次清理。需要备份数据库、wallet master.key、加密版本和尚未完成的 ca-jobs，不能只备份数据库。

## 状态和生命周期

| 状态 | 含义 |
|---|---|
| PENDING / PREPARING | 已分配引用或证书版本，尚未证明可用 |
| ISSUED / RETRY | 已签发或组件待恢复；不能报告网络认可 |
| READY | 证书链、属性通过校验，并用该用户证书经官方 Gateway 调用 IdentityProbe 成功；证书尚未过期 |
| DISABLED | 平台禁止继续使用；不等于 Fabric 全网撤销 |
| REVOCATION_PENDING | 待 CA 撤销，任务可恢复 |
| CRL_PENDING | CA 已确认撤销，通道实际传播尚未核验 |
| REVOKED | 指定全部 Peer 的配置及受控探针通过撤销核验，有审计回执 |
| EXPIRED | 当前证书已过期，不显示可用 |

轮换及到期前7日续期使用新版本的新密钥和注册标识，不调用无限次 reenroll；稳定 identityId 不变。旧证书和交易核验记录保留。旧版本 RETIRED 表示平台不再选择它，不等于网络级吊销。需要网络级撤销时，先停用用户，再撤销全部版本并执行 CRL 管理步骤。恢复停用用户使用较新 revision；逻辑删除不可恢复。换公司后重新核验新的组织证书，原版本保留历史。任何自动任务不得把停用或删除用户改为 ACTIVE。

用户证书得到网络认可，不自动取得合约业务权限。IdentityProbe 只读、不修改账本；现有组织级存证服务签名身份继续保留，业务操作人与实际签名身份分别审计。没有 MetaMask、空投、代币，也没有自研 CA/签名协议。

## 权限与组织映射

迁移 `IAM/backend/db/isolated/005-permissions.sql` 是实际目录，页面从真实后端读取并按功能分组，避免同名操作覆盖。`trust:event:*`、`trust:evidence:*`、`trust:task:*`、`trust:audit:read`、`trust:operations:read`、`trust:wallet:read/manage/review` 和 `trust:identity:read` 控制 TRUST。IAM 的 `iam:identity:retry/rotate/revoke:execute` 分别控制身份操作；创建仍需 `iam:user:add:add`。

种子只提供角色和组织，不含业务账户或密码。申请员 `role-trust-requester` 可申请托管签名变更；复核员 `role-trust-reviewer` 可复核；`role-trust-denied` 无 TRUST 权限；`role-trust-org2` 在第二组织做跨组织拒绝测试。通过真实 IAM API 创建至少两位不同实名用户、不同稳定 ID，分别分配申请/复核角色。公司 `c-dl-port` 和 `c-my-factory` 的具体映射以私有 identity/integration/provider JSON 为准，三个映射必须一致；不能把组织不合法降级为默认公司。

## 官方组件与兼容性

锁定 Fabric CA client/server 1.5.22，配置必须填写官方二进制 SHA256，调用前再验摘要。Gateway Java 1.12.1，适配 Fabric 3.1.5。官方命令和证书列表恢复能力已按 [CLI 文档](https://hyperledger-fabric-ca.readthedocs.io/en/latest/clientcli.html) 核对；[CA 用户指南](https://hyperledger-fabric-ca.readthedocs.io/en/latest/users-guide.html) 说明注册、重注册、撤销和 CRL 发布边界。部署时需验证签发、certificate list 恢复、Gateway 认可及各 Peer 的 CRL；多值 OU 和 proposal 拒绝分类由适配器处理。

每个组织使用独立受限 registrar（只允许 client、所属 affiliation、app.* 注册属性及必要 revoker）；gencrl 与网络通道管理员材料放独立操作员目录。应用不持有管理员根私钥。TLS CA 与 Enrollment CA 配置分开，不能混作信任链。

## 独立部署准备

从 IAM/TRUST 的实例、身份提供方和集成配置模板生成私有允许名单。开发与测试的数据库、角色、CA、通道、应用目录和端口分别隔离；模板不是现场资源授权。数据库管理员可参考 `deploy/identity-databases.sql.example` 审核建库与最小授权。现场专用的数据库和 SSH 管理脚本仅由操作员在本地保管，不随源码分发。

网络连接保持回环监听和精确的来源、账号及目标端口限制。SSH 管理方案必须保留主机核验、原规则基线、候选配置检查与摘要匹配回退；不能为了参数化或脱敏删除这些保护。不要在公共文档记录具体服务器、账号或 sudo 授权。

1. 获批后，先对保护对象获取只读基线：现有服务JAR/config摘要、健康、重要记录ID/内容摘要、通道配置块/链码定义/高度、IPFS已知CID可取回性。保留原始证据在私有目录，不覆盖旧manifest。
2. 按允许名单创建专用库和分离账号。迁移角色拥有专用schema，app仅USAGE及业务DML；无跨库、创建库、角色管理或SYS权限。`deploy/identity-databases.sql.example` 供DB维护方审查，不自动执行。
3. 由网络管理员创建新通道并审核MSP：保留实际Peer/管理员信任链，追加新客户端CA信任链；逐项核验root/intermediate、NodeOU及Readers/Writers/Admins策略。不能覆盖旧通道或复制旧根私钥。仅在新通道部署含IdentityProbe的链码并校验package ID、sequence。
4. 在新目录安装发布包；由受限服务账号准备0700的runtime/secrets/wallets、32字节master.key、独立query/control身份及CA job目录。私有配置由模板产生。TRUST integration必须 `iamMode=real`、iamUrl指向真实后端、dev quick login=false；IAM与TRUST token不得与WMS重用。
5. 先 `identity-release.py` dry run并校验manifest，停新实例后才 `--apply` 安装JAR。前端独立静态包与后端同一manifest。运行显式迁移命令、bootstrap管理员，再以isolated-app.sh启动；应用启动不执行DDL。迁移/Bootstrap通过发布JAR内的Spring Boot PropertiesLauncher运行，不依赖开发机classpath。
6. 完成真实验收矩阵后复查保护对象基线。任何失败保留证据并停止新实例，不能修旧服务掩盖问题。

## CRL受控管理与回执

应用 revoke仅到CRL_PENDING。获准网络管理员使用独立身份执行官方 gencrl，核对issuer、serial、AKI和有效期，将CRL加入**新通道**相关MSP的revocation_list，以实际mod_policy取得足够签名并提交配置更新。保存前后配置块、更新包/交易ID及摘要。不要向旧通道推送新CA的CRL。

配置更新提交后，以 `deploy/crl-verification.example.json` 配置所有目标Peer（至少两个不同端点），固定官方peer/configtxlator二进制SHA。在获准独立实例执行 `com.bproject.trust.provisioning.CrlVerification <identityId> <private-plan.json>`。该命令只从Peer读取实际配置块，校验正确channel/MSP、签名CRL包含目标serial且未过期；同一端点的有效control身份必须调用成功，被撤销且未过期的版本必须被明确认证/权限拒绝。超时、网络错误和含糊拒绝均不能算成功。已过期版本仍检查CRL配置，保留历史证据。

成功以revision CAS提交REVOKED和追加审计，原始块及receipt在runtime/crl-evidence；失败追加失败审计并保持待核验。运维环境中的CORE_PEER_MSPCONFIGPATH指向操作员专用身份，不复制给Web服务。Fabric3.1.5的FAILED_PRECONDITION仅在精确匹配目标channel/MSP的proposal身份验证拒绝时接受，不能将通用超时或背书失败计入。

## 回退

测试应用使用 [IAM](../../IAM/deploy/test-activation.conf.example) / [TRUST](../deploy/test-activation.conf.example) 的持久启动门禁；两个服务必须同时 `disable --now`，部署不创建启动标记。`retry` 对保存的 `action=REVOKE` 或 `desiredState=DELETED` 任务，除 `retry` 权限外还必须持有 `revoke`；鉴权失败不更新调度时间、请求回执或审计。

只停新实例，isolated-app.sh核对PID、cwd和JAR归属再发TERM。`identity-release.py rollback` 只恢复该模块上一次安装的JAR；不删除数据库、CA、密钥、审计、历史证书或IPFS内容。需要数据库回退时，用本次新库的已核验备份恢复到另一个新恢复库，再重建显式允许名单；禁止直接down migration或覆盖旧库。通道/CA一旦签发或提交记录不做删除式回退；隔离停用并保留证据，CRL不能倒退以重新启用已吊销证书。

## 外部配合及验收门槛

SYS：确认独立令牌校验/会话撤销接口或标准SSO方案、日志传输格式/去重键/鉴权和展示范围后再实现SysAuditSink。当前没有共享JWT、跨库写表或SYS改动。
WMS：仅TRUST接收端模拟请求及100/60/40追溯回归，真实业务outbox、事务触发及权限由WMS维护方实现。模拟通过不表示真实WMS已接通。
基础设施：数据库、CA、通道/MSP/CRL、链码、应用服务和精确网络通信由对应维护方审核。发布包安装不自动修改共享入口或授予网络管理权限。

验收应覆盖迁移事务、不同用户的独立证书、公用 API 鉴权、跨组织与属性越权、幂等与并发、CA 故障及丢响应恢复、Gateway 与 CRL、身份生命周期、双人审批、中文页面、存证核验及既有数据完整性。离线测试、模拟页面和真实组件验证分别记录；未执行的条件测试不得计为通过。全部报告和运行证据仅保存在本地 `.local/test-results`，不上传 GitHub。
