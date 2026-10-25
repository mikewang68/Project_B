# SYS 后端 · 1.1.0

Spring Boot 3.5、Java 17、MyBatis、openGauss。业务 API 前缀为 `/api/v1/sys`，响应格式为 `{ "code": 0, "message": "success", "data": ... }`。本服务管理字典、配置和日志，不创建平台账号或签发平台登录凭据。

## 统一会话

| 方法与路径（相对 API 前缀） | 用途 |
|---|---|
| POST `/auth/sso/exchange` | 使用受服务身份保护的 IAM 短票建立本地会话 |
| GET `/auth/me` | 恢复当前本地身份、当前权限及只读状态 |
| GET `/auth/sso/csrf` | 获取 Cookie 写操作使用的 CSRF 令牌 |
| POST `/auth/sso/logout` | 撤销平台会话并清理本地会话 |
| POST `/auth/sso/revoke` | 由获准服务通知撤销指定会话 |

每次请求重新验会话，并取中央授权与本地权限交集。主体到本地用户 ID 必须显式映射；映射缺失、身份无效或集中服务不可用时拒绝请求，不回退为默认管理员。Cookie 写操作使用 `X-SYS-CSRF`。

## 业务接口

| 方法与路径 | 权限与行为 |
|---|---|
| GET/POST `/dict/types`，PUT/DELETE `/dict/types/{id}` | `sys:dict:type:*`，分类 CRUD |
| GET/POST `/dict/items`，PUT/DELETE `/dict/items/{id}` | `sys:dict:item:*`，字典项 CRUD |
| GET `/logs` | `sys:log:list:view`，过滤与分页 |
| GET `/logs/export` | `sys:log:list:export`，独立授权，生成 UTF-8 BOM CSV |
| GET `/configs` | `sys:config:list:view`，仅返回可见归属对象 |
| PUT `/configs/group/{group}` | `sys:config:list:edit`，检查分组内对象归属及可编辑状态 |

专用管理员只能管理 SYS 归属对象。未知历史对象默认 `GLOBAL`，不能批量转给 SYS。全局只读限制优先；前端按钮隐藏不能代替后端检查。

## 配置

模板见 [unified.env.example](../deploy/unified.env.example) 和 [subject-bindings.example.json](../deploy/subject-bindings.example.json)。服务间凭据、数据库密码、签名密钥与本地主体映射由部署人员私下注入。

`sys.sso.enabled`、`sys.sso.iam-base-url`、`sys.sso.service-token`、`sys.sso.bindings-file` 分别控制启用、集中身份 API、服务身份和显式映射。环境变量使用对应的 `SYS_SSO_*` 名称。`SYS_SERVER_PORT` 控制后端端口，`DB_HOST/PORT/NAME/USER/PASSWORD` 控制 SYS 连接库。

本地用户和角色由 `LoginUserLoader` 从 SYS 连接库中的身份表加载；这不表示可以跨连接访问 IAM 的独立数据库。旧共享库 DDL 的跨 schema 授权不应直接套用到独立数据库。统一会话模式使用独立服务身份，不要求共享 IAM JWT 签名密钥；遗留 Bearer 代码仅供显式配置的兼容环境使用。

## 数据库与升级

新环境需先建立受限应用角色及 SYS 基础表；`db/01-sys-schema.sql` 和 `02-sys-seed.sql` 是历史共享库初始化材料，包含固定角色授权及演示数据，不能对现有共享数据重复执行。

已有环境升级使用 `db/004-resource-ownership.sql` 添加资源归属列和索引。`db/005-native-permission-catalog.sql` 的目标是身份权限表所在库的 `iam` schema，必须在该库由授权人员执行，不能误当作 SYS 业务库迁移。详细顺序见 [升级说明](../docs/upgrade-1.1.0.md)。

## 构建与测试

```bash
mvn test package
java -jar target/sys-backend.jar
```

测试覆盖原生会话、主体映射、归属过滤、业务接口和日志诊断。没有执行真实数据库测试或上线核验时，应如实记录限制；不得以源码说明代替现场验收。
