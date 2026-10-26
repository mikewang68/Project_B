# 跨域统一登录接入

数据库地址发布与事务版本规则见 `IAM/docs/navigation-address-publication.md`。启用数据库地址源后，门户和跨域授权读取同一已发布地址；新事务携带地址版本，进行中的事务保留旧版本，不依赖网关重启刷新静态域名。

## 浏览器与服务端协议

门户的启动参数为登记的 `appCode + stableMenuKey`。中心端每次读取当前 IAM 会话与菜单授权，计算目标 URL。目标 URL、回调地址、服务身份由服务端配置确定，浏览器不能指定回调地址或业务主机。

1. 功能入口跳转目标系统 `/sso/start?page=<stableMenuKey>`。
2. 目标桥建立服务端登录事务，用独立 HttpOnly Cookie 绑定浏览器，生成随机 `state`、PKCE verifier 及 S256 challenge。
3. 浏览器跳转门户 `/sso/authorize`。未登录时返回统一登录页，登录成功后继续原事务。
4. 中心端签发 45 秒有效的不透明、一次性授权码，绑定当前用户会话、目标受众、功能和 PKCE challenge，跳转目标系统固定 `/sso/callback`。
5. 目标桥校验 Cookie、state，并通过受服务身份保护的 `/sso/code/exchange` 兑换授权码。IAM 短票仅在服务端兑换与消费，不进入浏览器 URL。
6. 目标原生后端消费短票，检查显式本地用户映射和授权，生成自己的业务会话 Cookie。目标桥转交 Cookie，再跳转登记功能页面。

所有回调响应均禁止缓存，并设置 `Referrer-Policy: no-referrer`。代理访问日志不得记录 `/sso/` 查询参数。Cookie 使用 host-only、HttpOnly、Secure、SameSite=Lax；禁止用父域 Cookie 共享身份。

## 配置边界

门户网关配置：

- `BFF_PUBLIC_ORIGIN`：准确的外部 HTTPS origin，同时用于登录写请求的 Origin 核验。
- `BFF_SESSION_COOKIE_NAME`：该部署独立的门户 Cookie 名称。
- `SSO_APPLICATIONS_FILE`：权限为 0600 的 JSON 文件，按小写受众登记 `origin` 和独立 `credential`。
- `IAM_BASE_URL` 与 `IAM_CONTEXT_PATH`：IAM 服务地址及上下文。

每个目标桥使用独立 `SSO_EDGE_CONFIG` 私有文件，字段为：

```json
{
  "audience": "wms",
  "origin": "https://wms.example.test",
  "centralOrigin": "https://portal.example.test",
  "centralExchangeUrl": "http://127.0.0.1:24400/sso/code/exchange",
  "credential": "",
  "exchangeUrl": "http://127.0.0.1:24401/api/v1/auth/sso/exchange",
  "successCodes": ["OK"],
  "port": 24402
}
```

空凭据必须替换为部署端随机生成的独立服务身份；实际值不得进入源码、PR、命令行或日志。内网回源示例使用回环地址；跨主机后端通道须使用经部署方配置与验证的安全网络或 TLS。

`centralExchangeUrl` 是服务端回源配置，不改变浏览器授权域名。明文中心回源仅允许显式回环地址。默认原生成功码为数字 `0`；WMS 使用字符串 `OK`，须通过 `successCodes` 显式登记，不能仅凭 HTTP 200 判断交换成功。

## 八系统接口

| 系统 | 原生交换端点 | 原生撤销端点 | 写请求 CSRF |
| --- | --- | --- | --- |
| IAM | `/api/v1/iam/auth/sso/exchange` | `/api/v1/iam/auth/sso/native/revoke` | `X-IAM-CSRF` |
| SYS | `/api/v1/sys/auth/sso/exchange` | `/api/v1/sys/auth/sso/revoke` | `X-SYS-CSRF` |
| WMS | `/api/v1/auth/sso/exchange` | `/api/v1/auth/sso/revoke` | `/auth/csrf` 返回的 headerName |
| TRUST | `/api/v1/auth/sso/exchange` | `/api/v1/auth/sso/revoke` | `/api/v1/csrf` 返回的 headerName |
| SCS | `/api/v1/auth/sso/exchange` | `/api/v1/auth/sso/revoke` | `X-SCS-CSRF` |
| EHMS | `/api/ehm/v1/auth/sso/exchange` | `/api/ehm/v1/auth/sso/revoke` | `X-EHM-CSRF` |
| EMCS | `/auth/sso/exchange` | `/auth/sso/revoke` | `X-EMCS-CSRF` |
| PSMS | `/api/auth/sso/exchange` | `/api/auth/sso/revoke` | `X-PSMS-CSRF` |

端点均由目标后端处理，不能由前端模拟。交换、撤销、当前授权查询和服务端统一退出使用目标受众的服务身份。业务写请求同时通过本地会话、当前 IAM 授权、本地权限和 CSRF 核验。

IAM 授权断言 1.3 包含 `subject`、`sessionId`、`audience`、`localSubject`、`readOnly`、`superAdmin`、`roles`、`permissions`、`allowedAppCodes`、`applicationAdmin`、`globalRead`、`company/org/domain/zone`、`projectIds` 和 `authorizationVersion`。后端不能根据同名账号自动映射或提权。缺少显式映射、缺少范围、无法执行的范围、失效会话或停用用户必须拒绝。

`POST /api/v1/iam/auth/sso/session/introspect` 每次检查当前授权；`POST /api/v1/iam/auth/sso/session/logout` 撤销平台会话并调用持久化注销协调器。通知失败由重试队列补投，业务后端仍须因中心会话失效而拒绝旧 Cookie。中心暂不可用应明确报错，不得退回默认身份。

`app.sso.logout-endpoints` 使用分号分隔的 `audience=URL` 项，须登记包括 IAM 在内的八个原生撤销端点。每个目标使用对应服务身份校验请求。IAM 原生会话登记同步处理会话 ID 更新及过期事件；持久化中心会话撤销同时阻止重启后旧会话恢复。

## 菜单与数据

地址维护与菜单发布分别管理。`sso_enabled` 仅在目标桥和原生后端同批部署后开启。`featureStatus`、`canLaunch` 与 `unavailableReason` 描述功能状态；看到目录并不授予业务执行能力。发布须有有效 VIEW 权限映射、技术验证与审批，不能以超级管理员身份绕过未完成的发布。

EHMS 目录迁移由实际源码清单生成，保留驾驶舱稳定键并建立十组业务目录。生成器不自动批准功能。项目授权同时校验中央 `sso_project_grant` 和本地绑定；聚合存储的主键包含项目，时序查询与写入均带项目标签。历史记录默认 `UNASSIGNED`，必须依据明确归属回填。

PSMS 业务模式使用原页面框架与服务端会话，禁止演示重置、浏览器角色切换及演示数据覆盖。未完成业务适配的页面应保留目录并置灰，不能以通用首页代替已登记功能。

PSMS 的 CSV 导入使用相同的原生 Cookie 与 CSRF，经 `/api/plans/import` 写入数据库后重新读取台账；不得在业务模式显示另一套账号登录或仅写浏览器存储。业务计划须返回 `workArea`，台账、统计和详情同时按所选授权作业区过滤，服务端仍须独立检查列表条件和对象归属。

PSMS 业务读取也检查中央当前权限。已发布适配器使用 `pps:api:plans:read` 与 `pps:api:overview:read`，兼容既有计划/总览查看编码；其他资源须单独登记 `pps:api:<resource>:read`。`029-psms-native-read-permissions.sql` 为独立全局只读角色登记前两项，不授予导出；入口授权和 GET 方法不能代替业务读取授权。

计划查看授权仅覆盖台账与详情，不能据此读取任务等未发布子资源。中央全权资格也须与本地管理员资格相交；本地查看角色保留只读限制，不能用中央管理员标志绕过本地角色或独立导出授权。

SCS 的私有主体绑定包含 `enabled`、`localSubject`、`role`、`permissions` 与 `routes`。角色为 `ADMIN`、`OPERATOR` 或 `VIEWER`。普通业务读取也必须具有明确路由和当前有效权限，未登记路由默认拒绝；不能因为请求方法为 GET 就授予所有业务数据。以下为单项查看授权的通用格式，实际主体与服务身份由部署方单独维护：

```json
{
  "subject-placeholder": {
    "enabled": true,
    "localSubject": "operator-placeholder",
    "role": "VIEWER",
    "permissions": ["safety:fence:list:view"],
    "routes": [{"method":"GET", "pathPattern":"/api/v1/fences", "permission":"safety:fence:list:view"}]
  }
}
```

本地权限与 IAM 当前权限取交集。账号信息查询与统一退出属于会话维护；写操作另行登记，且只读硬否决与 CSRF 校验仍然生效。SCS 尚不支持的公司、组织、域及区域范围不能转换成全局范围。

IAM 角色权限关联按权限表主键关联后读取有效的 `permission_code`；主键与业务编码可以不同，停用权限不能继续进入授权集合。只读标志仅限制操作，不能替代具体页面或系统的查看授权；目录仅承载获授权的子页，空目录不显示。

`030-core-feature-view-mappings.sql` 为既有五个核心功能补齐显式查看映射，保留已有审批与映射决定，新增规则仍待技术验证和发布批准。全局只读角色取得这些具体读取权限，但不取得导出、业务写入或绕过发布流程的资格。

`IAM/backend/db/isolated/027-platform-readonly-role.sql` 定义独立的全局只读角色，登记 TRUST 原生读取权限，并为该角色授予当前有效目录中的 `:view`、`:read` 权限。迁移不分配用户，不创建业务用户映射，不授予导出；管理员须显式分配此角色及各系统的范围。

SYS 必须配置私有 `sys.sso.bindings-file`，以 IAM 稳定主体为键登记 `{"enabled":true,"localUserId":"本地稳定用户ID"}`。两套数据库的用户 ID 可以不同；后端按显式本地 ID 加载角色及范围，并校验业务用户名与中央 `localSubject` 一致。缺少或停用映射直接拒绝，不能按同名管理员推导权限。撤销该映射会在下一次业务请求生效。

WMS 只读角色仍按中央与本地权限的交集执行；`READONLY_AUDITOR` 不代替具体读取授权。`028-wms-native-read-permissions.sql` 登记原生读取编码并向独立全局只读角色授权，不授予导出或扩大本地租户范围。移除中央读取权限后，已有业务会话下一次请求也不能保留该项读取权限。

EMCS 私有主体绑定除 `enabled`、`userId` 外，还使用与 SCS 相同结构的 `routes`。路由中的权限必须在当前业务权限与 IAM 授权的交集中；中央权限可使用原生编码或 `ems:` 加原生编码。例如 `GET /system/dict/type/list` 可登记 `system:dict:list`，对应 IAM `ems:system:dict:list`。本地 `admin` 角色不能代替当前中央授权。仅同时具备本地管理员和中央全权管理员资格时允许全权操作；账号查询、路由查询、CSRF 和退出属于会话维护。

## 发布与回滚

先在独立端口和专用数据库应用兼容迁移，构建前后端，再记录制品摘要。验证真实浏览器跳转、各角色业务操作、数据范围、重放与统一退出；URL 200、API 编译或模拟身份测试均不能替代业务验收。

目标发布前备份原配置、制品与迁移前数据，逐系统切换并在实际域名复核。前端、后端、SSO 桥、映射、配置及迁移必须属于同一交付版本。运行中的 Java 制品必须使用按摘要保存的独立路径，不能直接覆盖启动时使用的 JAR 后再等待重启。失败时恢复对应制品与配置；不得通过删表或覆盖共享数据回滚。

迁移包括 IAM 功能目录与项目授权、EHMS 项目维度、EMCS 原生令牌撤销表。EMCS 撤销记录必须保存在数据库，不能在应用重启后重新接受已退出的原生令牌。

首版授权码、浏览器事务及多数业务会话为单进程存储，重启后需重新登录。多实例部署必须使用共享原子存储或明确会话路由，不能将当前实现直接扩容后声称跨实例一次性与撤销语义成立。

## 系统范围与管理对象归属

先由数据库管理员应用 `IAM/backend/db/isolated/033` 至 `036` 兼容迁移，再升级依赖这些表的 IAM 后端。运行账号需要 `iam_role_app_scope`、`iam_management_owner` 的业务读写权限，以及 `iam_scope_migration` 的读取权限；建表权限只交给迁移账号。迁移不创建用户，不设置密码，不自动开放菜单。

角色的系统范围来自 `iam_role_app_scope`，管理级别显式记录为 `ADMIN` 或 `BUSINESS`。目录、短票签发及会话查询均检查相同范围；权限编码的前缀不构成系统授权。导航的 `userContext.allowedAppCodes` 返回这组标准大写编码，单系统账号只返回自己的编码。首次迁移记录既有授权，重复运行不会恢复已撤销的范围。

`applicationAdmin` 只表示目标受众内的管理员资格；它由当前数据库范围与管理级别计算，且受 `readOnly` 否决。业务后端还要验证显式本地管理员映射。它不能写入平台 `superAdmin`，也不能用于为其他受众签发授权。`globalRead` 只允许获授全局读取的账号读取平台归属对象，不能授予导出或写权限。

IAM 专用管理员只管理 `iam_management_owner` 中归属 IAM 的用户和角色。新建对象登记归属；共享、历史未知归属对象属于平台。角色分配同时检查对象归属、角色系统范围与受保护角色。授予的权限不得超过操作者当前 IAM 权限；本人拥有的系统不能通过角色名、改密或身份供给接口扩大。

SYS 同批应用 `SYS/backend/db/004-resource-ownership.sql`。字典、配置和操作日志的历史未知归属默认 `GLOBAL`；部署方按可核实的业务归属单独登记 `SYS`。列表、详情、统计、更新与导出均使用相同归属条件。回退到缺少这些条件的旧后端前，必须暂停专用账号或入口。

SCS 的 `GET /api/v1/auth/me` 保留原字段，并返回服务端核验的 `permissions`、`readOnly`、`authorizationVersion` 及写操作所需 `csrfToken`。前端据此过滤菜单、路由和操作按钮；后端仍独立校验每项操作。WebSocket 只接收已登记的业务会话，每次推送检查当前中央会话、本地映射与对应事件读取权限，定时清理失效连接。设备采集密钥仅用于登记的采集端点，不能连接业务推送。
