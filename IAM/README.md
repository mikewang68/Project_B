# IAM 统一身份与权限管理

本轮独立联调版本使用 Spring Boot 3.5、Java 17、MyBatis、openGauss 和真实 REST 认证；Vue 3 页面从 `/api/v1/iam` 获取用户、组织与权限。业务数据不保存到浏览器。服务器旧入口是否接入此版本必须单独验收，不能由源码状态推断。

创建业务用户必须传 `Idempotency-Key`。用户、供给任务和创建请求在本地事务内提交，随后在约 5 秒的同步窗口内向 TRUST 请求 Fabric 身份。HTTP 201 仅表示用户身份已达到 READY；202 表示用户已保存、身份仍待处理。页面显示任务及恢复操作；不再提供手填钱包地址。公用协议见 [身份 OpenAPI](../TRUST/docs/identity-openapi.json)。

JWT 带持久会话标识；退出撤销该用户全部现有会话。每次请求检查会话、用户状态和当前角色权限。用户停用、密码或角色变更撤销会话；停用角色立即不再贡献权限。管理员角色与账户由后端保护，组织必须是启用且连续的真实组织路径。JWT 密钥、服务 token、幂等 HMAC 密钥分别配置，不共享给 SYS 或 TRUST。

本地前端：在 IAM 目录执行 `pnpm --ignore-workspace install --frozen-lockfile` 和 `pnpm --ignore-workspace run build`。开发时使用 `pnpm --ignore-workspace exec vite --host 127.0.0.1`；`VITE_DEV_API_TARGET` 指向获准的独立 IAM 后端。认证接口返回 HTML 会显示明确错误，不回退为模拟登录。

后端：`mvn -f backend/pom.xml test package`。隔离保护默认开启；按 `deploy/instances.example.json`、`deploy/identity.example.json` 和 `deploy/isolated.env.example` 生成私有配置，真实文件只放 `runtime/secrets/`。没有默认密码或业务用户种子。通过 `IamBootstrap` 在独立库创建初始管理员，业务验收用户必须通过 API 创建。

只运行 `backend/db/isolated` 下的迁移，由 `IamMigration` 校验已执行脚本摘要。旧 `backend/db` 初始化脚本包含 SYS 边界内容，不适用于此实例。审计写入 IAM 自有追加表；`SysAuditSink` 是默认未接通的可选接口。任何 SYS 单点登录或日志导出需要维护方确认，不跨库查询或写入 SYS。

完整配置、部署/回退、权限和生命周期见 [身份集成说明](../TRUST/docs/identity-delivery.md)。验收报告、工作汇报与运行证据仅保存在本地 `.local/`，不提交 Git。
