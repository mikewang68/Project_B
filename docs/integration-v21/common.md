# IAM / SYS 与六系统公共接入

本次更新增加 L1/L2/L3 管理页面、L4 归属继承、用户名防重、项目/区域/子系统权限、会话范围选择、动态菜单及系统注册，并提供 Java / TypeScript 上下文客户端。SYS 使用同一授权规则；原生供给 worker 负责明确映射下的新账号接入。

SSO 外层协议仍为 1.3，`userContext.contextVersion=2`。`my_info`、`my_project_zone_info` 纯 SQL 字符串及参数化模板同时保留。新增操作码仍须明确授权；只读账号仅可使用获授的 `VIEW / DOWNLOAD / EXPORT / AUDIT_VERIFY`。

## 源码与构建

请在完整仓库上合并更新，保留各模块已有业务修改。构建需要 JDK 17、Maven、Node.js 24.x；本节使用 pnpm 10.34.5，PSMS 按模块声明使用 pnpm 11.10.0。数据库采用 openGauss PG 兼容模式。所有命令从仓库根目录执行。

```bash
# 两项公共 Java 依赖必须先安装
mvn -f AUDIT/pom.xml -pl client -am install
mvn -f IAM/context-client/pom.xml install
mvn -f IAM/backend/pom.xml verify
mvn -f SYS/backend/pom.xml verify

# TypeScript SDK 必须先生成 dist，PSMS 的 file: 依赖才能使用
pnpm --dir IAM/context-client-ts install --ignore-workspace --frozen-lockfile
pnpm --dir IAM/context-client-ts run build
pnpm --dir IAM/context-client-ts test

pnpm --dir IAM --ignore-workspace install --frozen-lockfile
pnpm --dir IAM --ignore-workspace run build
node --test IAM/tests/*.test.mjs
pnpm --dir SYS --ignore-workspace install --frozen-lockfile
pnpm --dir SYS --ignore-workspace run build
```

产物为 `IAM/backend/target/iam-backend.jar`、`SYS/backend/target/sys-backend.jar`、两个前端 `dist/` 和 SDK `IAM/context-client-ts/dist/`。SDK 的 TypeScript 版本与完整性由独立 `pnpm-lock.yaml` 固定；安装必须匹配该锁文件。数据库条件测试须单独配置专用测试环境；未执行的测试不能计为通过。

仓库必须保留 `AUDIT/pom.xml`、`AUDIT/client/`、`IAM/context-client/`、`IAM/context-client-ts/`、IAM/SYS 前后端、迁移和测试源码。worker 在 `IAM/backend/src/main/java/com/bdemo/iam/provisioning/`，不是独立的 `deploy/native-onboarding` 工程。不要仅复制少数修改过的 Java 文件。

## 数据库顺序

先核对真实连接、现有迁移记录及备份。应用启动不执行 DDL；已有环境不重跑历史用户、角色和菜单种子。

| 目标库 | 前置条件与本轮顺序 |
|---|---|
| IAM 身份库 | 已有身份、会话、导航、SSO、系统管理归属表；核对 `IAM/docs/upgrade-1.1.0.md` 中的历史前置迁移后，按 `IAM/backend/db/v21/001-region-scope.sql` → `002-session-scope.sql` → `003-native-scope-readiness.sql` 执行 |
| SYS 业务库 | 已有基础表、审计 outbox 和 `004-resource-ownership.sql`；执行 `SYS/backend/db/v21/001-region-scope.sql` |
| SYS 本地身份表所在库 | `SYS/backend/db/005-native-permission-catalog.sql` 作用于该库的 `iam` schema，不要误投 SYS 业务 schema |

`ScopeMigration.java` 只处理固定版本 001；002/003 采用独立受控增量，不复用 001 的迁移编号或校验和。全新环境也要核对基础脚本：当前 `isolated/004-identity.sql` 已含会话选择列，不能再次直接执行 002。细节见 `IAM/backend/db/v21/README.md`。

003 增加 `sso_subject_binding.native_scope_json`。发布强制就绪检查前，逐项核实旧账号的原生资源映射并记录精确就绪范围；NULL 不允许 L2/L3/L4 进入六个业务系统。未知归属继续拒绝，不能统一回填为某个正常区域。回退保留新增列、业务数据和审计，不能用旧库覆盖新数据。

## 必需配置

以下都是占位符，由部署环境私下注入。前端环境变量会进入浏览器，不能用于保存服务凭据。

| 服务 | 配置项 |
|---|---|
| IAM | `IAM_DB_URL=<approved-jdbc-url>`、`IAM_DB_USER=<restricted-role>`、`IAM_DB_PASSWORD=<secret>`、`JWT_SECRET=<independent-secret>`、`IAM_IDEMPOTENCY_SECRET=<different-secret>` |
| IAM 实例 | `IAM_ROOT=<instance-root>`、`IAM_INSTANCE=<instance-name>`、`IAM_INSTANCE_ALLOWLIST=<private-file>`、`IAM_IDENTITY_CONFIG=<private-file>`；实例白名单须与隔离守卫、实际数据库和端口一致 |
| IAM V2/SSO | Spring 属性 `iam.scope-v2.enabled=true`、`app.sso.service-tokens=<audience=credential,...>`、`app.sso.logout-endpoints=<registered-callbacks>`；通过受保护的 Spring 配置注入 |
| SYS | `DB_HOST=<host>`、`DB_PORT=<port>`、`DB_NAME=<database>`、`DB_USER=<restricted-role>`、`DB_PASSWORD=<secret>`；显式覆盖历史开发默认配置 |
| SYS V2/SSO | `sys.scope-v2.enabled=true`、`SYS_SSO_ENABLED=true`、`SYS_SSO_IAM_BASE_URL=<iam-api-base>`、`SYS_SSO_SERVICE_TOKEN=<sys-only-secret>`、`SYS_SSO_BINDINGS_FILE=<private-file>` |
| 前端 | `VITE_API_BASE_URL=<same-origin-api-prefix>`、`VITE_BASE=<application-base-path>`；开发代理按各模块 `.env.example` 配置 |

每个消费者使用独立服务凭据，不共享 IAM 的 JWT 签名密钥。IAM 的账号身份配置、SYS 本地主体绑定和各业务系统的原生资源映射分别维护。新环境应先适配并审核实例允许规则，不通过关闭隔离守卫启动。

原生 worker 使用单独的私有 JSON：`createdAfter`、`stateDirectory`、`databases`、固定 L3/L4 的 `scopes`；需要新 L2 自动供给时另外配置 `projectReaderCreatedAfter` 和 `projectReaderScopes`。字段及最小列权限见 `IAM/native-onboarding/README.md`。L2 只有管理身份时不供给业务权限；明确读取岗位和原生映射均满足后才供给。不要将真实 JSON、原生注册表或运行 journal 放进 Git。

独立启动 worker 时，先准备运行依赖；不要直接用 Spring Boot fat JAR 当普通 classpath：

```bash
mvn -f IAM/backend/pom.xml dependency:copy-dependencies \
  -DincludeScope=runtime -DoutputDirectory="${PWD}/IAM/backend/target/native-lib"
java -cp 'IAM/backend/target/classes:IAM/backend/target/native-lib/*' \
  com.bdemo.iam.provisioning.NativeOnboardingWorker '<private-config.json>'
```

## 六系统接入

| 系统 | 当前代码接入位置 |
|---|---|
| PSMS | `PSMS/backend/`，依赖 `file:../../IAM/context-client-ts`，先构建 SDK |
| WMS | `WMS/`，同版本 Java 工具源码已放入 `com/bproject/iam/context`，保持与公共 SDK 同源 |
| SCS | `SCS/backend/`，Maven 依赖 `context-client:2.1.0` |
| EHMS | `EHMS/source/backend/`，Maven 依赖 `context-client:2.1.0` |
| EMCS | `EMCS/backend-java/`，Maven 依赖 `context-client:2.1.0` |
| TRUST | `TRUST/backend/`，Maven 依赖 `context-client:2.1.0` |

1. 合并本模块源码和必要迁移；登记真实页面、资源、动作及上级授权，配置独立受众服务凭据与原生映射。
2. 后端消费一次性短票，将主体、中央 sessionId、受众、本地身份绑定到本地会话。每次受保护请求重新 introspect；失效、错受众、缺映射或中央不可用时拒绝，不能回退为旧权限快照。
3. 以当前 `menuOperations` 检查动作，并将同一范围条件用于列表、详情、统计、导出、修改和删除。创建归属来自当前可信具体范围；同时保留模块原有公司、仓库、组织等约束。
4. 使用纯 SQL 串时固定字段且括起原 `OR` 条件；优先绑定参数模板。跨系统共享查询调用 IAM `resource-filter`，不能自行删掉 `subsystem_code` 条件扩大范围。
5. 验证通过后才登记该应用支持 V2，再升级相应账号。新 L2 多区域要求真实且兼容的逐项原生映射；未映射区域不得借旧绑定通行。

至少检查：本域正常访问；同项目跨区域、跨项目及非共享跨系统拒绝；有效 CSRF 下只读写入仍拒绝；角色/范围撤权与账号停用后旧会话拒绝；L1/L2 会话选区传至 SSO，已失效选择进入 DENY；原账号 ID/绑定保持。L3 创建 L4 应锁定三属性并处理并发重名。后台任务、机器来源和 WebSocket 分别使用自身可信主体及范围，不能复用网页默认全局权限。

Git 只保留源码、测试、迁移、锁文件、通用协议和空值模板。运行配置、数据库备份、账号材料、部署回执、验收截图和工作报告保留在私有目录；`node_modules/`、`target/`、`dist/` 由构建生成。

实例允许名单须显式填写 `deploymentProfile=STANDARD|ISOLATED|RESTORE`、`databaseHost`、`databasePort`、`databaseName`、`databaseUrl`、`appRole`、`root`、`port`，根节点包含 `blockedPorts`；隔离／恢复模式另需核实 `databaseOid`。旧实例将现有核实值补入私有配置，缺项拒绝启动。激活清单新增 `approvedDeploymentTier`，必须与所批准的 `deploymentTier` 及数据库指纹一致。范围数据库测试还需显式 `IAM_SCOPE_TEST_DATABASE`，仅可指向专用测试库，不能用作正式健康检查。

历史 `IAM/deploy/isolated-app.sh`、`IamMigration` 和 `IamBootstrap` 保留旧实例约束，不作为 V2 通用安装入口。新部署使用上述受控 DDL、新实例白名单和构建后的应用 JAR；不得借旧 bootstrap 重建已有组织、账号或身份绑定。
