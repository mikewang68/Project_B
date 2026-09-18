# IAM 后端（iam-backend）

统一身份与权限管理系统后端：Spring Boot 3.5 / Java 17 / MyBatis（注解 Mapper）/
Spring Security + JWT(HS256) / openGauss 6.0.5（PostgreSQL 协议，opengauss-jdbc 6.0.0）。

- 端口：`8081`
- context-path：`/api/v1/iam`
- 数据库：openGauss 库 `project_b`，schema `iam`（账号 `iam_app`）
- 统一响应：`{ "code": 0, "message": "success", "data": ... }`，非 0 为业务错误，HTTP 状态码同步给出 400/401/403/404/409
- 鉴权：除 `/auth/login` 外均需 `Authorization: Bearer <JWT>`；接口级权限由 `@RequirePerm("iam:xxx")` 强制
- 操作日志：登录/用户/角色操作写入统一日志表 `sys.sys_operation_log`（表归 SYS，iam_app 被授予 SELECT/INSERT）

## 1. 数据库初始化（按顺序执行）

以 openGauss 管理员执行（脚本在 `IAM/backend/db/` 与 `SYS/backend/db/`）：

```bash
# 1) 建库 project_b、schema iam/sys、账号 iam_app/sys_app（请先修改其中的占位密码）
gsql -d postgres -h <host> -p 5432 -U gaussdb -f IAM/backend/db/01-create-database.sql
# 2) IAM 表结构 + 对 sys_app 的只读授权
gsql -d project_b  -h <host> -p 5432 -U gaussdb -f IAM/backend/db/02-iam-schema.sql
# 3) IAM 种子数据（权限目录 799 节点、组织树 45 节点、5 角色、5 用户等）
gsql -d project_b  -h <host> -p 5432 -U gaussdb -f IAM/backend/db/03-iam-seed.sql
# 4) SYS 表结构 + 对 iam_app 的日志写入授权
gsql -d project_b  -h <host> -p 5432 -U gaussdb -f SYS/backend/db/01-sys-schema.sql
# 5) SYS 种子数据（字典 5 分类/15 项、配置 10 项、初始日志）
gsql -d project_b  -h <host> -p 5432 -U gaussdb -f SYS/backend/db/02-sys-seed.sql
```

> 种子 SQL 由 `D:\智慧仓储项目\tmp\gen_seed.mjs` 从前端权威 TS 源
> （`IAM/src/iam/menu-tree.ts`、`org-tree.ts`、`mock-data.ts`、`SYS/src/sys/mock-data.ts`）
> 自动生成；密码列为 BCrypt 哈希，仓库中不出现明文密码。

## 2. 配置（环境变量注入，不提交真实密钥）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` / `SYS_SERVER_PORT` | 8081 | 监听端口 |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | 127.0.0.1 / 5432 / project_b | openGauss 连接 |
| `DB_USER` / `DB_PASSWORD` | iam_app / change-me | 应用账号 |
| `JWT_SECRET` | 仅开发占位 | HS256 密钥，**必须与 SYS 后端一致**，至少 32 字节 |
| `JWT_EXPIRE_HOURS` | 12 | token 有效期 |
| `CORS_ALLOWED_ORIGINS` | 5174/4174/5175 | 允许的前端来源 |

可复制 `src/main/resources/application-example.yml` 为 `application-local.yml`（已被 .gitignore 忽略）。

## 3. 运行

```bash
cd IAM/backend
mvn spring-boot:run
# 或打包后运行
mvn -DskipTests package
java -jar target/iam-backend.jar
```

本机无 Maven 时使用临时 Maven（不入库）：
`D:\智慧仓储项目\tmp\tools\apache-maven-3.9.16\bin\mvn.cmd`（需 JDK 17+）。

## 4. 主要接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/auth/login` | 公开 | 用户名密码登录，返回 token/user/roles/permissions |
| POST | `/auth/logout` | 登录 | 登出并记日志 |
| GET | `/auth/me` | 登录 | 当前用户、角色、权限（刷新页面恢复会话） |
| GET/POST | `/users`、`/users/{id}`(PUT/DELETE) | iam:user:* | 用户 CRUD |
| PUT | `/users/{id}/status` `/users/{id}/password` `/users/{id}/roles` | iam:user:* | 启停用/重置密码/分配角色 |
| GET/POST | `/roles`、`/roles/{id}`(PUT/DELETE) | iam:role:* | 角色 CRUD |
| PUT | `/roles/{id}/permissions` | iam:role:perm:* | 角色分配权限 |
| GET | `/permissions/tree` | iam:menu:tree:view | 权限目录树（与前端 menu-tree 对齐） |
| GET | `/orgs/tree` | 登录 | 四级组织树 |

## 5. 演示账号（密码为 BCrypt 存储，仅开发环境）

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| admin | Admin@123 | 超级管理员（全部权限） |
| dispatcher01 | Dispatch@123 | 调度员 |
| operator01 | Operate@123 | 运维员 |
| field01 | Field@123 | 现场作业员 |
| viewer | Viewer@123 | 观摩用户（只读，含 SYS 日志导出） |

## 6. 测试

- `mvn test`：MockMvc Web 层测试（Mapper 全部 Mock，**不需要数据库**），覆盖登录成功/错密/停用、
  401/403、用户名冲突 409、权限树装配。
- 真实库集成测试在数据库可达后手工执行（见项目交接文档联调章节）。
