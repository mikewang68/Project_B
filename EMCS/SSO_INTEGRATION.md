# EMCS 可信 SSO 接入与回退

本模块接收 IAM V1.1 集中验票协议，作为原生 `/login` 验证码登录的并行入口。默认关闭。本地 HTTP 模拟验票测试不代表已与真实 IAM、openGauss、Kvrocks 或 Portal 完成联调。

## 接收补丁与修复范围

收到的补丁包含5个文件，仅有编译通过的交付声明。在当前主线可无冲突应用，但存在以下运行缺口，本实现已修正：

- 使用不存在的 `system_user` / `id` / `enabled` 字段，导致本地映射无法查询。现改为 `sys_user.user_id/status/del_flag`。
- HTTP Session 与原应用 `STATELESS` Bearer鉴权不兼容，换票后的下一请求无法延续身份。现使用持久化、可撤销的随机Bearer令牌，不保存HTTP SecurityContext。
- 映射不存在、停用或查询失败时回退配置，会绕开数据库禁用策略。现只有数据库绑定，查询故障拒绝。
- 未校验IAM返回受众、本地主体和数据范围，未接入注销或只读拒绝。现校验显式绑定并接入IAM服务身份注销通知。
- Spring默认LogoutFilter会拦截 `/logout` 并返回302。现由应用控制器执行SSO令牌撤销并返回原生JSON。

本分支以当次远端主线为基线，不自动包含另一个未推送的本地公共任务分支。IAM、Portal及其他子系统源码不在本次修改范围。

## 服务端接口

通过网关访问时统一加 `/emcs-api`；直接访问服务时不加。不得通过URL传票据。

| 方法及路径 | 语义 |
|---|---|
| POST `/api/auth/sso/exchange` | JSON `{ticket}` 或 `X-SSO-Ticket`，同时提供时必须一致。成功返回 `{code:0,data:{token,expiresAt,userId,userName,readOnly,scopeArea}}`，Cache-Control为no-store |
| GET `/api/auth/sso/health` | 只返回配置是否启用、固定受众emcs、契约版本1.1等非敏感诊断信息，不是端到端健康证明 |
| POST `/api/auth/sso/logout-notify` | 仅有效 `X-SSO-Service-Token` 可调用，正文 `{iamSessionId,subject,audience:"emcs",reason}`；与当前IAM SsoLogoutNotifier字段一致。幂等撤销该subject+sid的本地会话 |
| POST `/logout` | 持有SSO Bearer时撤销该本地令牌；不代替IAM全局注销，也不调用需要IAM用户身份的 `/auth/sso/revoke` |
| GET `/getInfo` | 原生响应增加 `sso:{enabled,readOnly,scopeArea}`，主体、角色、权限仍来自本地数据库 |
| GET `/getRouters` | SSO会话仅保留已授权的能源业务候选及所需目录；通用管理页、缺失/隐藏页不加入SSO路由 |

SSO令牌以 `emcs_sso_` 开头，由32字节安全随机数生成；数据库只保存SHA-256摘要。前端沿用 `EMCS-Token` 与 `Authorization: Bearer`。原生JWT继续走原验证链路，不被当成SSO令牌。每次请求校验SSO令牌有效期、撤销状态、绑定与本地账号状态，并重读本地角色/页面权限。

本次撤销表只覆盖SSO令牌。原生验证码登录JWT仍没有服务端退出黑名单，不能把SSO退出测试表述为原生JWT全局撤销已完成。前端退出无论请求是否成功都会清理本机状态；请求失败时不能宣称远端会话已撤销。

错误：缺票或冲突票据400；IAM拒票、非法断言、受众/契约不符401；无绑定、停用账号、主体/范围不符403；配置缺失或验票依赖不可用503。业务写权限不足403。不会向调用者返回远端响应正文、SQL异常或服务凭据。

## IAM V1.1 对齐依据

参考同仓库IAM的 `SsoTicketController`、`SsoTicketService`、`SsoSubjectGuard` 和 `SsoLogoutNotifier`：

- EMCS服务端调用 `${EMCS_SSO_IAM_BASE_URL}/auth/sso/verify-and-consume`，发送ticket、固定audience/consumer `emcs`及该受众的服务身份令牌。
- IAM负责签名、issuer、audience、时效、原始会话和一次性消费。EMCS不解码浏览器claims授权，也不另行维护第二个票据消费点。
- 仅接受HTTP200且数值code=0，数据包含subject(UUID)、非空sessionId、localSubject、contractVersion=1.1、domain、zone。company/org可空，但也必须与本地登记一致。
- 当前IAM把audience集合输出为字符串 `[emcs]`；接收端仅接受它或精确字符串 `emcs`，拒绝其他或多受众值。
- `localSubject` 必须精确等于已启用EMCS用户的user_name。company/org/domain/zone全部与本地绑定逐字匹配，domain/zone不接受缺失/空值。
- IAM全局退出应配置本受众的logout-notify地址。IAM投递失败到重试成功之间，已建立令牌不会神奇失效；本地会话默认15分钟、可配1–60分钟。此通知链路仍需真实环境验证。

## 绑定与数据范围

迁移表 `emcs_sso_subject_map` 是唯一绑定来源，没有内联配置、管理员默认值或导航回退。每个IAM subject显式关联一个独立本地账号；同受众的local_user_id唯一，避免多个IAM主体共用一个本地管理身份。

| 字段 | 必须由双方确认的含义 |
|---|---|
| iam_subject | IAM稳定UUID，不能用用户名替代 |
| audience | 固定emcs，与IAM服务身份登记一致 |
| local_user_id | 已存在且status=0、del_flag=0的sys_user |
| enabled | 1启用、0拒绝；无记录即拒绝 |
| read_only | 默认1；只有审批明确允许业务写入时才设0，仍受本地角色及功能权限约束 |
| scope_area | 默认空即拒绝；填既有e_area.area_code为区域受限模式；`*`仅表示明确批准的本地全区域范围，不能由缺省推导 |
| iam_company/org/domain/zone | 与IAM绑定的可信断言精确匹配；不创建新的Zone实体，不从URL/Header推断范围 |

区域受限会话目前只开放总览、设备画像、告警的已有区域校验查询，以及getInfo/getRouters/logout。其他尚未完整实现区域隔离的接口直接403，区域受限会话始终只读。设备画像的eventId附加查询也校验告警区域。原生dispatch角色的A区限制与SSO范围取交集，不能利用全域SSO绑定扩大该角色范围。

全域SSO会话需要本地相应energy页面权限（显式绑定的本地admin保留其原生业务授权），写操作还需通过原有角色规则。只读会话拒绝状态变更及GET形式导出，允许明确只读的报表预览和退出。管理、监控和pipeline入口不向SSO会话开放。

映射禁用或绑定内容变化后，下一请求检查失败会撤销该令牌；重新启用配置不恢复已经撤销的会话。授权管理员应在改变映射时一并撤销相应会话，避免修改又立即还原、其间没有请求检查的情形。

## 前端入口协议（需Portal配合验证）

入口 `/emcs/sso?redirect=/energy/overview`。redirect只允许代码中的能源路由白名单，不能包含票据、任意外链或查询参数。此页面是本次新增的接入协议；当前Portal代码未在本分支改造，不能宣称真实Portal已接通。

1. 构建时设置 `VITE_EMCS_SSO_PORTAL_ORIGIN` 为批准Portal的精确origin（协议+主机+端口，不含路径或通配符）。缺省为空时不接受浏览器交接。
2. Portal通过同意的窗口/iframe打开SSO入口，监听该窗口发出的 `{type:"EMCS_SSO_READY",nonce}`。Portal也必须校验EMCS精确origin与来源窗口。
3. Portal为当前IAM身份获取一次性emcs短票，向原窗口发送 `{type:"EMCS_SSO_TICKET",nonce,ticket}`，targetOrigin为EMCS的精确origin。
4. EMCS同时校验event.origin、event.source与随机nonce，仅消费一次，随后POST换票。票据不写URL、Cookie、localStorage或防重复请求缓存。
5. 成功后只保存返回的本地令牌，并整页重新导航到白名单路径，丢弃旧身份的路由与状态。失败保留明确错误，可由用户选择原生验证码登录；不会自动代登。

`ssoWrite`指令对能源业务写入、重算、归档、巡检及敏感导出按钮禁用；请求层也限制只读调用。最终安全边界仍是后端，不信任前端的readOnly声明。区域受限用户可能看到接口403；新增更多区域能力前应先完成对应服务端过滤。

## 配置键与启用顺序

只交付键名和非敏感默认值，真实配置由获准渠道管理：

- `EMCS_SSO_ENABLED` 默认false。
- `EMCS_SSO_IAM_BASE_URL` 指向IAM服务根路径，包括实际context-path；缺失时关闭。
- `EMCS_SSO_SERVICE_TOKEN` 为IAM登记的emcs专用服务令牌，至少32字符，不是用户JWT或票据签名密钥。
- `EMCS_SSO_ALLOW_INSECURE_HTTP` 默认false，只能在明确批准的隔离实验环境临时开启；正式凭据应通过HTTPS传输。
- `EMCS_SSO_TIMEOUT_MS` 默认3000，限制500–10000。
- `EMCS_SSO_SESSION_MINUTES` 默认15，限制1–60。
- `VITE_EMCS_SSO_PORTAL_ORIGIN` 前端精确来源，默认空。

1. 记录独立测试实例版本、端口、审批范围和数据库/缓存边界，保存旧制品及必要配置备份。
2. 核对迁移009尚未执行，停测试实例后在**独立测试库**执行 `backend-java/sql/009-emcs-sso-subject-map.sql`。这是可选迁移，不属于根目录演示数据初始化循环。脚本不插入账号、不授予默认管理员，也不修改已有业务表。
3. 若曾试运行原交付的旧版009（仅一张映射表），不要直接重跑新脚本；先关闭SSO、备份并核对已有数据，再另行审查升级。重复运行默认应报错，不能悄悄掩盖旧结构。
4. 在授权范围内登记双方确认的映射和既有区域，配置IAM地址与专用服务身份，再启用SSO。
5. 前后端版本一起验证，保持原验证码登录、菜单隐藏及能源接口回归；完成真实验票、重放、无绑定、只读写拒绝、跨范围、注销通知与浏览器握手负例后才考虑推广。

## 构建与验证

```bash
cd backend-java
mvn clean verify
cd ../web
pnpm --ignore-workspace install --frozen-lockfile
node --test test/*.test.js
pnpm run build:prod
```

后端新增测试使用真实Spring Security/MVC/MyBatis、H2隔离库和回环HTTP IAM模拟服务。H2只对原建表脚本的客户端指令、时区设置和生成列STORED关键字作测试兼容处理，不证明openGauss方言、实际IAM密码学配置或部署网络正确。现有需要openGauss/Kvrocks的测试仍按环境变量显式启用。

## 回退

优先设置 `EMCS_SSO_ENABLED=false` 并重启获准测试实例，停止接收SSO令牌；原生验证码登录继续可用。恢复上个已验证前后端制品及配置。

若确认不再需要SSO数据，先备份映射、会话及注销墓碑，再执行 `backend-java/sql/rollback/009-emcs-sso-subject-map.sql`，仅移除三张本次新增SSO表。**不要运行00-schema.sql回退，不修改共享库。**

运行中不要清理仍可能关联有效IAM会话的注销墓碑。当前不提供自动清理任务；生命周期和存储保留策略需与IAM会话最大寿命一起确认。
