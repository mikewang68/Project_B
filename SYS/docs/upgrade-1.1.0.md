# SYS 1.1.0 升级与对接

先备份源码工作、数据库、制品和配置，在独立数据库副本验证后再切换。该说明不代表任何现场实例已升级。

## 迁移与归属

已有 SYS 基础表的环境执行 `backend/db/004-resource-ownership.sql`，为字典分类、字典项、配置和日志增加 `owner_app_code`。历史数据默认 `GLOBAL`；只能由维护者核实后将明确属于 SYS 的对象登记为 `SYS`，不能把全部共享配置默认交给专用管理员。

`backend/db/005-native-permission-catalog.sql` 在本地身份权限表所在库执行，目标 schema 为 `iam`，补齐分类修改、删除及日志导出目录。中央 IAM 独立库的权限目录也需按 IAM 版本说明配置；补目录不等于自动授予角色。迁移账号与应用账号应分离，应用启动不自动迁移。

如果是全新独立环境，需要由授权迁移人员先建立业务表及本地身份表。旧的 `01-sys-schema.sql` 含固定角色跨 schema 授权，`02-sys-seed.sql` 含演示数据；先核对目标数据库布局与角色，不能照搬到其他环境或覆盖已有数据。

## 主体与会话配置

配置项参考 `deploy/unified.env.example`，映射格式参考 `deploy/subject-bindings.example.json`。每个键是中央稳定主体 ID，`localUserId` 指向 SYS 连接库中的真实本地用户 ID。该用户的角色、状态和权限必须先核对；不能按同名 admin 自动建立映射。

每次业务请求通过 IAM 服务身份查询当前会话，再与本地权限、资源归属取交集。查询接口、对象详情、写入、批量操作和导出均执行归属限制。只读标志优先，查询权限不自动包含导出；专用管理员不能修改共享安全策略或其他系统配置。

门户与跨域边缘网关另外登记应用代码、页面代码和回调。前端刷新首先恢复 Cookie 会话；Cookie 写请求需有效 `X-SYS-CSRF`；统一退出通知失败时，中央会话检查仍应拒绝旧会话。

## 构建、切换及回退

在 SYS 目录执行：

```bash
pnpm --ignore-workspace install --frozen-lockfile
pnpm --ignore-workspace run build
mvn -f backend/pom.xml test package
```

前端 `dist/` 与后端 `backend/target/sys-backend.jar` 应作为同一版本发布。先在独立端口检查管理员、只读与业务查询角色；验证本系统允许操作成功、跨系统对象与共享配置被拒绝、有效 CSRF 的越权写仍被拒绝、退出与禁用后旧会话无效，再切换入口。

回退恢复制品和配置，保留兼容新增列和索引。若旧制品缺少资源归属控制，先暂停相关专用账号或入口，不能以放宽权限的旧版本继续开放。真实映射、凭据与部署证据不提交 GitHub。
