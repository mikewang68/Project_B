# 统一入口与业务会话升级

本说明描述源码构建与升级步骤。实际节点、账号、端口、运行状态和验收记录由部署方保存在私有交接包中。

## 构建

使用模块声明的 Java、Node 与包管理器版本及锁文件。运行检查后构建制品，记录源码提交、构建命令、依赖与制品 SHA-256。

| 模块 | 后端目录与命令 | 前端目录与命令 |
| --- | --- | --- |
| IAM | `IAM/backend`: `mvn test package` | `IAM`: `pnpm install --frozen-lockfile`、`pnpm build` |
| SYS | `SYS/backend`: `mvn test package` | `SYS`: `pnpm install --frozen-lockfile`、`pnpm build` |
| TRUST | `TRUST/backend`: `mvn test package` | `TRUST/frontend`: `pnpm install --frozen-lockfile`、`pnpm build` |
| WMS | `WMS/WMS`: `mvn test package` | `WMS/WMS/frontend`: `pnpm install --frozen-lockfile`、`pnpm typecheck`、`pnpm build` |
| SCS | `SCS/backend`: `mvn test package` | `SCS/frontend`: `pnpm install --frozen-lockfile`、`pnpm build` |
| EHMS | `EHMS/source/backend`: `mvn test package` | `EHMS/web` 是静态资源，完整发布该目录 |
| EMCS | `EMCS/backend-java`: `mvn test package` | `EMCS/web`: `pnpm install --frozen-lockfile`、`pnpm build:prod` |
| PSMS | `PSMS/backend`: `pnpm install --frozen-lockfile`、`pnpm exec tsc -p tsconfig.json` | `PSMS`: `pnpm install --frozen-lockfile`、`pnpm exec tsc --noEmit`、`VITE_PSMS_REAL_API=true pnpm build` |

WMS 前端构建目标是 `WMS/WMS/target/classes/static`，不能假定为 `frontend/dist`；打包后端前应先完成前端构建。PSMS 必须使用业务构建开关，保持原有完整路由框架；未完成业务适配的页面保留目录并禁止启动。TRUST 的 Fabric、IPFS 和身份供给配置仍按本模块文档部署，不由统一登录替代。

网关检查在 `deploy/isolated-gateway` 执行 `python -m unittest test_cross_domain_sso test_gateway_routes`。Python 网关、门户静态资源、各目标桥及原生后端须使用同一版本。

PSMS 后端回归在 `PSMS` 执行 `pnpm exec vitest run --config vitest.backend.config.ts`，覆盖原生会话的当前授权、计划范围、导入及外部投递状态。测试身份服务不能代替部署后的真实旧会话撤权与统一退出核验。

## 兼容迁移

先备份目标数据库，核对现有迁移状态。升级已有系统时不运行初始化种子或覆盖共享数据。

1. IAM 地址列：`IAM/backend/db/isolated/023-navigation-addresses.sql`。
2. IAM 功能状态：`024-feature-catalog.sql`。
3. IAM 项目授权：`026-sso-project-grants.sql`。
4. 独立全局只读角色：`027-platform-readonly-role.sql`。该迁移不为用户分配角色或项目范围。
   WMS 原生读取编码继续执行 `028-wms-native-read-permissions.sql`；本地查看权限仍须显式配置，并与中央当前权限取交集。
   PSMS 已发布读取适配器执行 `029-psms-native-read-permissions.sql`，其他资源读取须另外登记，不能因本地账号有全局数据范围就自动开放。
   完整导航目录导入后执行 `030-core-feature-view-mappings.sql`，补齐五个核心入口的查看映射与独立只读授权。它不批准发布、不替换已有 VIEW 决策；升级为严格只读菜单检查时须先完成此步。
5. EHMS 聚合项目维度：`EHMS/sql/027_project_scope.sql`。历史数据先进入 `UNASSIGNED`，依据明确归属回填；迁移后旧版不带项目条件的后端不能继续写入。
6. EMCS 令牌撤销：`EMCS/sql/026_token_revocations.sql`。撤销记录必须保留到令牌过期。
7. WMS 显式主体映射：`WMS/WMS/db/sso/001-wms-sso-subject-map.sql`；已有映射须核对并保留。

EHMS 完整目录脚本 `deploy/isolated-gateway/sql/025-ehms-feature-catalog.sql` 依赖既有导航表。它添加目录与候选功能，不授予发布资格。`022-psms-read-permissions.sql` 同样只登记查看映射候选，不代替审核。

`IAM/backend/db/isolated` 与 `deploy/isolated-gateway/sql` 中重名迁移是同一能力的两种分发位置，应按版本清单执行一次。不能因为目录含 isolated 就将测试数据库替换为共享业务数据库。

迁移使用单独的数据库维护身份；运行身份不应为方便升级而取得 schema 创建权限。EHMS 使用非默认 schema 时，先依据已校验的配置渲染项目迁移中的 schema 标识及目录条件，再验证渲染结果。备份须在副本恢复后核对每个业务 schema、表、函数所有者和安全模式，不能只看导出命令退出码。

## 配置与灰度切换

接口、Cookie 与私有绑定格式见 [SSO 协议](SSO_PROTOCOL.md)。中心注册表、八个目标桥、服务身份、本地业务用户、IAM 主体绑定和数据范围必须逐项对应。不能用同名 `admin` 推导业务管理员权限。

门户 HTTPS origin 对应登录网关及其 API；各业务域名的 `/sso/` 对应本系统目标桥，其余业务 API 对应同批后端。回调、原生接口上下文和 SPA 基础路径必须分别核对。EHMS 根路径进入 `/ehm/`，PSMS 与 EMCS 使用相对重定向，保留外部 HTTPS 与域名。

先在独立端口和专用数据库验证，再备份目标 Nginx、服务配置、制品及迁移前数据。制品保存到按摘要命名的不可变路径；启动新进程并检查健康、登录、业务读取、写入与退出后，逐系统切换域名。不要覆盖运行中进程使用的 JAR 或混用旧前端与新后端。

公开切换时关闭 `iam.navigation.isolated-test-candidates`。对已验证功能补齐查看权限映射、发布批准与实际地址，再设置 `sso_enabled`；未验证或设计功能保持不可启动。菜单能显示不等于页面与业务接口已经通过验收。

## 回滚与交接

失败时将对应反向代理切回原制品和原配置。兼容新增表、列与撤销记录继续保留；EHMS 项目维度变更须停止旧写入端后，由经过验证的兼容版本接管，不通过删表、删项目列或恢复共享旧快照回滚。

向模块维护者交付提交或累计补丁、构建步骤、SSO 接口、菜单与权限映射、迁移及配置模板。实际环境清单、配置差异、账号、部署摘要和验收结果单独存放于私有交接包。源码中的设备桥示例不能替代目标消息协议的发送、接收、入库与页面刷新验证。
