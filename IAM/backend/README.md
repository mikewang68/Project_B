# IAM 后端（iam-backend）

本说明默认面向 TRUST＋IAM **独立联调实例**。部署入口是 [联合部署、权限与回退说明](../../TRUST/docs/identity-delivery.md)，模块概览见 [IAM README](../README.md)。旧共享库/SYS 联合部署流程不适用于本实例，历史适用范围见文末。

当前制品使用 Spring Boot 3.5、Java 17、MyBatis、Spring Security 和 openGauss（PostgreSQL JDBC 协议）。API 前缀为 `/api/v1/iam`，统一响应为 `{ "code": 0, "message": "success", "data": ... }`。服务默认绑定回环地址；独立开发实例使用28184，测试实例28186。部署不会自动切换既有入口。

## 独立部署步骤

1. 由已获准的数据库维护操作创建独立 `iam_identity_dev` / `iam_identity_dev_test` 库，以及各自分离的 app/migrate 角色；不使用旧 `project_b` 库，不创建或授权 SYS 对象。资源允许名单见 [instances.example.json](../deploy/instances.example.json)。
2. 按 [isolated.env.example](../deploy/isolated.env.example)、[identity.example.json](../deploy/identity.example.json) 和允许名单生成私有配置，放在实例 `runtime/secrets/`，不得提交真实文件。TRUST 地址指向独立真实身份 API，组织映射由服务端控制。
3. 在当前后端目录执行 `mvn test package`，按联合交付说明验证发布包摘要并安装 `artifacts/iam-backend.jar`。应用运行角色不持有迁移密码。
4. 在获准的新实例目录，操作员执行 `bash deploy/isolated-app.sh migrate`。`IamMigration` 只运行 `backend/db/isolated/002-schema.sql`、`004-identity.sql`、`005-permissions.sql`，校验已执行脚本摘要，再赋予本实例受限角色权限；应用启动不自动执行DDL。
5. 首次初始化使用 `bash deploy/isolated-app.sh bootstrap`，管理员材料由 `IAM_BOOTSTRAP_FILE` 指向本机私有 `runtime/secrets/bootstrap.json`；运行bootstrap前显式导出该变量。没有本轮通用演示密码或业务账号种子；通过真实API创建业务用户。
6. 开发应用由已审核的独立systemd服务托管，使用本实例脚本的 `debug` 入口；健康检查为 `GET /api/v1/iam/health`。不得用旧共享库参数直接执行默认 `java -jar` 命令。

测试应用默认必须 `disabled` 且保持停止。示例中的 `identity-iam-test.service` 和 `identity-trust-test.service` 另有持久启动条件：只有操作员审核待办、明确建立对应的 `operator/activation/iam.enabled` 或 `trust.enabled` 标记后，systemd才允许启动。示例见 [IAM测试启动门禁](../deploy/test-activation.conf.example)。不能仅停止已enabled的测试服务，也不能随部署包安装自动创建标记。正常测试CA、链码继续运行。隔离中的fixture必须单独复核，保留原状态与审计；不得批量改为PENDING来启动测试。

## 与实际制品一致的配置

以下由私有环境注入，对应 [application.yml](src/main/resources/application.yml) 及启动脚本：

| 变量 | 当前含义 |
|---|---|
| `IAM_ROOT` | 当前实例IAM根目录；开发为 `/srv/b-project-identity/IAM` |
| `IAM_INSTANCE` / `IAM_INSTANCE_ALLOWLIST` | 显式实例名及私有允许名单文件 |
| `IAM_ISOLATION_ENABLED` | 默认true，独立部署保持启用 |
| `IAM_DB_URL` | 开发库 `jdbc:postgresql://127.0.0.1:25432/iam_identity_dev?currentSchema=iam`；测试使用独立测试库 |
| `IAM_DB_USER` / `IAM_DB_PASSWORD` | 允许名单中的受限应用角色及私有密码，不是迁移角色 |
| `IAM_SERVER_PORT` | 配置缺省8081；独立开发28184、测试28186。`SYS_SERVER_PORT`不控制当前制品 |
| `JWT_SECRET` | IAM独立随机密钥，至少32字节，不与SYS、TRUST或其他模块共享；没有可用默认值 |
| `JWT_EXPIRE_HOURS` | 缺省12；会话仍受用户、角色及退出撤销约束 |
| `IAM_IDENTITY_CONFIG` | 私有身份服务配置，含TRUST URL、token文件和组织映射 |
| `IAM_IDEMPOTENCY_SECRET` | 独立的幂等HMAC密钥，不复用JWT或服务token |
| `CORS_ALLOWED_ORIGINS` | 明确列出允许前端来源，实际部署不照搬开发端口缺省值 |
| `IAM_BOOTSTRAP_FILE` | 仅首次bootstrap命令需要，指向本机私有管理员JSON文件，不含默认密码 |
| `IAM_MIGRATE_PASSWORD` | 仅操作员迁移命令从私有migration.env读取，不注入应用服务 |

当前制品读取 `IAM_DB_*`，不是旧流程的 `DB_HOST/DB_PORT/DB_NAME/DB_USER`。`application-example.yml`仅作为配置示例；本轮以实例允许名单及上述私有环境为运行依据。

审计写入IAM自有 `iam.iam_audit`，供给任务位于 `iam.identity_task`，会话位于 `iam.iam_session`。运行角色不能更新或删除审计。`SysAuditSink`/`SysAuditExport`是待对接的可选边界，默认不跨库写 `sys.sys_operation_log`、不执行SYS脚本。SSO或日志对接需要SYS维护方另行确认接口与权限。

## 主要接口与身份状态

除登录及健康检查外，受保护接口使用 `Authorization: Bearer <JWT>`，并执行当前会话及接口权限校验。TRUST通过IAM API持续鉴权，不获得IAM JWT签名密钥。

| 接口 | 行为 |
|---|---|
| `POST /auth/login`、`GET /auth/me` | 真实用户名密码登录、读取当前身份和权限 |
| `POST /auth/logout` | 撤销用户现有持久会话；旧token后续拒绝 |
| `POST /users` | 必须传Idempotency-Key；用户/任务/审计先在本地提交，随后有界等待TRUST。201带READY身份，202表示持久待办，不能当作身份成功 |
| `GET /users/{id}/identity` | 读取任务、公开证书及生命周期状态 |
| `POST /users/{id}/identity/{action}` | retry/rotate/revoke由各自权限控制并使用幂等键；撤销需先停用 |
| 用户、角色与组织接口 | 停用、撤权、退出、管理员保护和合法组织范围均由后端检查 |

完整接口、发行方/租户、幂等键与证书生命周期见 [身份OpenAPI](../../TRUST/docs/identity-openapi.json)。用户得到证书不自动得到所有合约权限。

## 测试与历史流程边界

`mvn test`包含离线接口测试；真实数据库条件测试必须显式设置 `IAM_IDENTITY_DB_TEST=1` 及对应 `IAM_IDENTITY_DB_URL/USER/PASSWORD`，仅允许新测试库。未配置的条件测试会跳过，不能计为通过。每次执行后检查持久任务并保留审计，测试应用仍维持默认关闭。

历史 `db/01-create-database.sql`、`02-iam-schema.sql`、`03-iam-seed.sql` 及旧说明中的SYS建表、`project_b`共享库、演示账号、共享JWT建议，属于早期跨模块联合原型。它们不是本轮迁移入口。只有相关模块维护方另行确认共享数据库、权限和SSO契约后，才可重新审查历史流程；不要按该历史流程修改当前独立实例或SYS。本README不再提供跨模块默认执行步骤或演示密码。

## 权限诊断

有效权限解释与运行时鉴权保持一致：停用用户无有效权限，停用或删除角色不生效，超级管理员显式标明隐式全权。`GET /health/permissions`不是公开健康探针，必须登录并具有`iam:role:list:view`。用户创建仍需要Idempotency-Key，并按身份READY/PENDING返回201/202；身份、会话及吊销边界保留。

真实数据库回归`PermissionAuthorizationDatabaseTest`使用专用测试库和实际Mapper，fixture事务整体回滚；不改已有管理员角色。测试证据与验收报告仅保存在本地，不提交 Git。
