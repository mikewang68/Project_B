# SYS 系统设置与维护 · 1.1.0

SYS 提供数据字典、操作日志、系统配置与日志诊断，使用 Vue 3、TypeScript、Spring Boot、Java 17 和 openGauss。前端通过真实 REST 接口读取业务数据，统一入口通过原生服务端 Cookie 恢复登录身份。

## 功能与权限

- 字典分类与字典项支持查询、创建、修改、删除和状态维护。
- 日志支持过滤、统计、诊断和单独授权的 CSV 导出。
- 系统配置按分组保存，并执行服务端归属和操作权限检查。
- SYS 专用管理员只管理 SYS 归属对象。共享、历史归属不明和其他系统对象保持平台管理边界；列表、详情、批量修改和导出采用一致限制。
- 统一权限、本地权限和资源归属共同决定是否允许操作；全局只读限制优先于管理员身份，导出不随查询权限自动授予。

账号与角色维护由 IAM 负责。SYS 不签发平台登录令牌，不提供通用演示账号密码。

## 构建

Node.js >= 20.19、pnpm >= 9，后端使用 Java 17 和 Maven。

```bash
cd SYS
pnpm --ignore-workspace install --frozen-lockfile
pnpm --ignore-workspace run build
mvn -f backend/pom.xml test package
```

前端产物为 `dist/`，后端产物为 `backend/target/sys-backend.jar`。数据库相关测试与真实部署检查应使用经批准的独立数据库。

## 统一身份对接

正式集成使用目标系统换票建立原生 Cookie，刷新时先请求 `/api/v1/sys/auth/me`。每次受保护请求向 IAM 查询当前会话和权限；退出、停用或撤权后拒绝旧会话。不能通过本地管理员名称推断平台超级权限。

Cookie 写操作需要 `X-SYS-CSRF`，令牌从 `/api/v1/sys/auth/sso/csrf` 获取。`/api/v1/sys/auth/sso/logout` 发起平台退出。后端服务凭据仅供服务间换票、验会话和撤销使用，不进入前端构建产物。

旧开发流程仍保留 Bearer 客户端兼容代码；统一集成不依赖将 IAM 签名密钥共享给 SYS。SYS 本地身份由显式主体映射确定，并保留本地权限约束。

部署配置、数据库归属迁移和回退步骤见 [1.1.0 升级说明](docs/upgrade-1.1.0.md)，接口与配置细节见 [后端说明](backend/README.md)。`.env.example` 仅包含前端代理参数；真实账号、密钥和映射文件应通过私有部署配置注入。

## 权限编码

| 功能 | 权限 |
|---|---|
| 字典分类 | `sys:dict:type:view/add/edit/delete` |
| 字典项 | `sys:dict:item:view/add/edit/delete` |
| 日志 | `sys:log:list:view/export` |
| 配置 | `sys:config:list:view/edit` |

版本变化见 [CHANGELOG](CHANGELOG.md)。现场验收结果、截图、部署清单和账号文件保存在项目私有 `.local/`，不由本 README 声明任何服务器运行状态。
