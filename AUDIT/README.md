# 公共操作日志服务 WriteLog v1

当前公共服务1.1.0、SDK 1.0.1。新TRUST通用OPERATION_LOG适配见docs/trust-generic-v1.md，统一模块接入见docs/client-v1.0.1.md。上链默认关闭，来源、正文非加密归档许可及真实联调需另行签认；已有1.0.0库须先审阅sql/002_generic_evidence.sql，不能仅换jar。

EHM现为1.7.3-server，新增实际任务日志及后端有界证据导出；需审阅EHMS/sql/003_audit_tasks.sql。等待阶段部署、静态配置预检和本地双进程HTTP验证见docs/waiting-stage-deployment.md。新功能不表示真实链上或全面RBAC验收完成。

技术：Java 17、Spring Boot 3.3.5、JDBC、openGauss兼容协议。H2仅用于本地测试。服务独立部署，不接管六个模块的业务数据库。

## 已提供的功能

- `POST /api/logs/v1/events`：按模块和domain/zone鉴权；日志与待上链任务同事务写入；相同事件重复投递返回原回执，同号不同内容返回409。
- `GET /api/logs/v1/events`：作用域隔离、分页、模块/用户/动作/时间筛选。
- `GET /api/logs/v1/events/{id}/receipt?appCode=...`：可靠入库与上链状态、交易编号、错误码。
- `GET /api/logs/v1/export`：导出授权范围最近100条；这是有界导出，不是全量归档。
- `POST /api/logs/v1/events/{id}/retry?appCode=...`：仅有RETRY权限的服务可重试失败上链任务，COMMITTED记录不能重试或修改。
- `GET /api/logs/v1/whoami`：当前服务权限，不返回凭据。
- 根路径 `/`：日志查询、详情、导出、回执刷新和受权限控制的重试页面。
- `client`：通用事件格式、JDBC事务发件箱、可靠HTTP投递、IAM/SYS日志转换器及备份/恢复/训练任务包装器。

日志原文没有修改/删除API。角色授权不通过浏览器的用户名或app字段决定。按每个模块、每个作用域生成不同凭据；只读账号没有WRITE/RETRY权限。

## 构建

```sh
cd AUDIT
mvn install
cd ../EHMS/source/backend
mvn package
```

需Java17。公共服务jar在 `service/target/audit-service-1.1.0.jar`，SDK在 `client/target/audit-client-1.0.1.jar`。先install公共SDK再构建EHM，避免Maven找不到本地公共依赖。不要把本机私有配置、数据库文件或凭据一起上传GitHub。

## 本地演示

```sh
cd AUDIT
python scripts/init-clients.py --domain demo --zone demo
```

生成私有 `runtime/secrets/clients.json`、各模块token及只读 `reader.token`；不会打印密钥，也不会覆盖已有配置。

Windows：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -Docker
```

或在Java17环境下执行 `java -jar service/target/audit-service-1.1.0.jar --spring.profiles.active=local`。打开 http://127.0.0.1:18110/ ，使用本机私有 reader.token 内容连接。此控制台的凭据输入只用于本地联调；正式用户仍须由IAM认证、SYS后端按用户权限代理。

## 服务器部署

1. 为公共日志分配openGauss数据库或独立schema `audit`；使用迁移账号执行 `service/src/main/resources/schema.sql`，执行前把search_path设为audit。
2. 运行账号需要schema USAGE、log_event的SELECT/INSERT、log_chain的SELECT/INSERT/UPDATE；1.1.0还需log_trust_delivery的SELECT/INSERT及必要状态列UPDATE、log_trust_receipt的SELECT/INSERT。不得更新快照来源、请求正文及摘要列，不授予日志正文UPDATE/DELETE。列级权限由DBA按docs/trust-generic-v1.md审阅。
3. 私有环境文件按 `config/audit.env.example`填写。JDBC URL使用currentSchema=audit，真实密码和地址不写进模板。
4. 以实际约定的domain/zone运行凭据生成脚本。各模块仅领取自己的token；不要给浏览器或其他模块使用ehealth/iam写入凭据。
5. 启动jar。默认只监听127.0.0.1；通过授权内网反向代理或限定的内部监听地址访问，不直接开放公网。
6. EHM先执行 `EHMS/sql/002_audit_outbox.sql`，替换重新编译的jar和web文件，再配置EHM_AUDIT_DOMAIN、EHM_AUDIT_ZONE、EHM_AUDIT_ENDPOINT、EHM_AUDIT_TOKEN_FILE。
   若需要记录真实登录用户，设置EHM_AUDIT_IAM_ME_URL为IAM实际 `/auth/me` 完整地址。EHM会把Bearer会话交给IAM验证后取user.id；不采信浏览器用户名请求头。设置EHM_AUDIT_IAM_REQUIRED=true可拒绝未登录操作。前端读取同源的b-iam-token，跨域会话仍需雷文确认SSO接入；此身份校验不替代完整业务RBAC。
7. 区块链负责方接口未确认时保持AUDIT_CHAIN_ENABLED=false。日志持续入库，链状态为NOT_CONFIGURED。经内容批准后配置精确来源矩阵，仅自动处理该范围eligibleFrom之后、无旧提交历史的获准记录；历史迁移另行核查，不默认全量补发。

EHM的端点示例为 `http://log-service.example.internal:18110/api/logs/v1/events`。这是示例，不是实际服务器地址。server模式仍使用openGauss/openGemini，不切回Mongo。

## 雷文的接入步骤

`integrations/iam`提供可直接加入IAM源码的配置类，实现现有SysAuditSink；IAM原有SysAuditExport以持久化iam_audit及sys_audit_receipt执行重试。加入audit-client依赖，合并配置片段，设置私有IAM凭据后启用。原日志不删除。

`integrations/sys`提供SYS历史/新增日志向公共队列汇聚的类、SQL与配置。加入audit-client依赖、启用@EnableScheduling并执行队列迁移后启用。既有日志行是持久化来源，重复扫描不会重复创建事件。

接入类已交付，但不会自动改写其他维护者的运行数据库或部署。雷文应审阅后合并到自己的实际分支。SYS提供TransactionalAuditBoundary和LogService-atomic.patch：去掉日志写入吞异常，让已有变更和审计一起提交，补充GET查询记录。需增加spring-boot-starter-aop依赖；在仓库根目录先执行 `git apply --check AUDIT/integrations/sys/LogService-atomic.patch`，确认分支匹配后再应用。不能仅靠历史日志扫描保证原本没有写出的日志。异常、安全拒绝和异步任务仍应在相应入口独立记录。当前转换器只适用于部署绑定的单一domain/zone，不能把多租户历史日志全部强行归入同一作用域。

## 各模块接入标准

1. 将SDK加入后端。同一DataSource下，在业务事务中调用JdbcLogOutbox.enqueue(event)，后台定期调用LogDispatcher.dispatch()。
2. 查询/失败/无权操作独立入队。不要只靠前端点击或HTTP方法推断成功。
3. 用户身份来自已验证会话。未接入登录的请求记录ANONYMOUS，不伪装为管理员。
4. 备份、恢复、训练等后台任务使用JobAudit，记录开始与结束。任务执行和结束日志的跨系统原子性不是数据库事务能保证的，异常退出后需按jobId核对。
5. 六个模块仍需各负责人补齐业务动作、菜单映射及任务入口。公共API不会凭空捕获没有接入的操作。

公共接收端信任已认证后端对业务用户身份的声明，这是服务间委托，不替代IAM认证。READ与EXPORT服务凭据不是用户JWT；正式SYS查询代理必须根据已验证用户的domain/zone/app选择相应受限凭据，不能给所有人复用全范围凭据。

## 数据边界

不记录请求正文、查询参数、密码、Token、模型Key或原始导出内容。记录事件ID、时间、app、domain/zone、可信用户标记、菜单、动作、对象编号、结果、追踪号、耗时及有限摘要。数据库审计元数据和业务日志均可能涉密，需同等级保护。

SDK待发送状态ACCEPTED只表示公共日志已入库；链服务ACCEPTED仍不是上链成功。新主路径协议和回执含义见 `docs/trust-generic-v1.md`；`docs/chain-contract.md`仅为旧协议兼容说明。自动测试使用本地契约桩或官方离线模拟器，不是实际Fabric。
