# SYS 后端（sys-backend）

系统设置与维护后端：数据字典、操作日志、系统配置。Spring Boot 3.5 / Java 17 /
MyBatis（注解 Mapper）/ Spring Security + JWT(HS256) / openGauss 6.0.5。

- 端口：`8082`
- context-path：`/api/v1/sys`
- 数据库：openGauss 库 `project_b`，schema `sys`（账号 `sys_app`）
- **不建用户表、不签发登录 token**：登录由前端转发 IAM `/api/v1/iam/auth/login`；
  本服务只验签 JWT，并跨 schema 只读 `iam.iam_user / iam_role / iam_user_role /
  iam_role_permission`（sys_app 已被授予这 4 张表 + iam_permission 的 SELECT）完成身份与权限加载
- 统一操作日志表 `sys.sys_operation_log` 归本服务所有，IAM 通过被授予的 SELECT/INSERT 跨库写入
- 统一响应：`{ "code": 0, "message": "success", "data": ... }`

## 1. 数据库初始化

顺序见 IAM 后端 README（`01-create-database.sql` → IAM schema/seed → SYS schema/seed）。
SYS 脚本：

- `SYS/backend/db/01-sys-schema.sql`：sys_dict_type / sys_dict_item（逻辑删除 + 部分唯一索引）/
  sys_operation_log / sys_config，并 `GRANT SELECT,INSERT ON sys_operation_log TO iam_app`
- `SYS/backend/db/02-sys-seed.sql`：字典 5 分类 15 项、配置 3 组 10 项、初始日志 8 条

## 2. 配置（环境变量注入，不提交真实密钥）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SYS_SERVER_PORT` | 8082 | 监听端口 |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | 127.0.0.1 / 5432 / project_b | openGauss 连接 |
| `DB_USER` / `DB_PASSWORD` | sys_app / change-me | 应用账号 |
| `JWT_SECRET` | 仅开发占位 | HS256 密钥，**必须与 IAM 后端完全一致** |
| `CORS_ALLOWED_ORIGINS` | 5175/4176/5174 | 允许的前端来源 |

可复制 `src/main/resources/application-example.yml` 为 `application-local.yml`（已被 .gitignore 忽略）。

## 3. 运行

```bash
cd SYS/backend
mvn spring-boot:run
# 或
mvn -DskipTests package
java -jar target/sys-backend.jar
```

## 4. 主要接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/auth/me` | 登录 | 跨 schema 加载当前用户/角色/权限 |
| GET/POST | `/dict/types`、`/dict/types/{code}`(PUT/DELETE) | sys:dict:type:* | 字典分类 CRUD（删分类事务内逻辑删其下字典项） |
| GET/POST | `/dict/items?typeCode=`、`/dict/items/{id}`(PUT/DELETE) | sys:dict:item:* | 字典项 CRUD |
| GET | `/logs` | sys:log:list:view | 日志过滤（kind/module/result/keyword/时间区间）+ 分页；不传分页返回全量 |
| GET | `/logs/export` | sys:log:list:export | 导出 UTF-8 BOM CSV，后端自记一条 export 日志 |
| GET | `/configs` | sys:config:list:view | 全部配置（按组） |
| PUT | `/configs/group/{group}` | sys:config:list:edit | 按组保存，仅更新 editable=1 且发生变化的键，返回 `{updated:n}` |

冲突处理：唯一键冲突由 Service 预检 + 数据库部分唯一索引双保险，
`DuplicateKeyException` 经全局异常处理转为 HTTP 409。

## 5. 演示账号

与 IAM 共用（登录走 IAM）：admin/Admin@123（全部权限）、viewer/Viewer@123
（5 个只读权限码，含 `sys:log:list:export`，无任何增删改权限）。

## 6. 测试

`mvn test`：MockMvc Web 层测试（Mapper 与跨 schema 的 IamIdentityMapper 全部 Mock，
**不需要数据库**），覆盖 401、字典列表、viewer 越权 403、分类重码 409、建分类成功、
viewer 导出 CSV、viewer 改配置 403、admin 按组保存配置。
