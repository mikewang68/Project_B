# TRUST 服务器间 SSO 接入

SSO 默认关闭。设置 `TRUST_SSO_ENABLED=true`、`TRUST_SSO_IAM_BASE_URL`（不带 `/auth/sso` 后缀）及 `TRUST_SSO_SERVICE_TOKEN` 后才接受换票。令牌至少32字符，只保存在服务端私密配置；不进入浏览器、URL、公开源码或日志。未启用 `TRUST_SSO_REQUIRE_UNIFIED_SESSION` 时，原 IAM 登录继续有效；启用后业务请求须使用统一会话。来源系统 Bearer 接口独立校验。

## 换票与会话

`POST /api/v1/auth/sso/exchange` 接收 `{"ticket":"一次性票据"}`，请求头为 `X-SSO-Service-Token`。TRUST 调用 IAM `POST /auth/sso/verify-and-consume`，发送 `{"ticket":"...","audience":"trust"}`。有效时返回 `200 {"ok":true}`、`Cache-Control: no-store` 及新本地会话 Cookie，替换旧会话。票据长度20～8192，消费、过期和重放判定由中央 IAM 完成。

每次业务请求调用 IAM `POST /auth/sso/session/introspect`，正文为 `{"subject":"中央稳定ID","sessionId":"中央会话ID","audience":"trust"}`。两个接口均使用上述服务请求头，成功响应必须为 HTTP 200、`{"code":0,"data":{...}}`，data 包含非空文本 `subject/sessionId/audience/localSubject/org`、boolean `readOnly`、字符串数组 `permissions`，可选 `username`。后续响应的五个身份绑定字段须与换票结果完全一致。IAM 必须只为有效主体和有效会话签发这类成功响应；不得将失效会话包装成成功授权。

本地按 `trust_users(username=localSubject,org_id=org)` 找到唯一 ADMIN/EDITOR/VIEWER 映射。缺失、角色无效或组织不符返回403；数据库不可达返回503。不自动建账号，不回退模拟身份。无 V2 上下文时，中央 `permissions` 与本地角色上限取交集；VIEWER 或中央 readOnly 使用原严格只读限制，ADMIN/EDITOR 的钱包 manage/review 仍需逐项显式授权，`superAdmin` 不提供隐式权限。

存在 V2 `userContext` 时，先由同版本 IAM context-client 校验主体、目标系统、项目/区域和动作范围，再按新版动作矩阵授权；获授的 AUDIT_VERIFY、DOWNLOAD、EXPORT 可以在只读状态执行。中央或本地任一只读限制均禁止设置修改、写入、上传及钱包审批。组织绑定始终保留；不会因中央权限扩大本地组织。兼容角色仅用于旧控制器，不能取代细粒度检查。

`/api/v1/integrations/**` 始终校验自身来源 Bearer，不以 SSO Cookie 代替。除换票、撤销及原来源接口豁免外，业务写入仍需 CSRF；新会话先 GET `/api/v1/csrf` 再发送返回的 token。

## 撤销和退出

IAM 以服务凭据调用 `POST /api/v1/auth/sso/revoke`，正文 `{"iamSessionId":"...","subject":"...","audience":"trust","reason":"..."}`。sessionId 与 subject 同时匹配才失效本地会话；重复调用返回 `200 {"ok":true,"revoked":0}`。reason 只为协议兼容，不改变授权判断。

浏览器继续 POST `/api/v1/logout`（需要 CSRF）。SSO 会话触发 IAM `POST /auth/sso/session/logout`，请求正文同 introspect，成功要求 `200 {"code":0,"data":{}}`。本地退出保留本人/组织日志，并总是清除本地会话；响应204，仅在 SSO 退出附 `X-TRUST-Central-Logout: confirmed|unconfirmed`。unconfirmed 表示本地已退、中央未确认，不能展示“全局注销成功”。日志固定记录 `SSO_CENTRAL_LOGOUT_UNCONFIRMED`，不写票据或令牌。

IAM 401/403 按对应状态拒绝；其他非200、格式异常、连接或5秒请求超时为503。连接超时3秒。本地关闭或缺配置为503，服务凭据不符403，缺少合法票据/撤销字段400。失败不进入业务链。

## 集成与运行边界

浏览器不直接持有换票服务凭据。BFF负责服务器间换票和 Cookie 转交；必须确认浏览器实际收到本地会话、Domain/Path/Secure/SameSite 与外部 HTTPS/反代路径一致，并防止共享服务凭据泄露。默认 Cookie HttpOnly、SameSite=Strict，跨站方案须由集成方结合实际同源部署验证，不能只凭后端200判登录成功。

撤销索引在本进程内存，按单实例使用；重启须重新登录，不提供跨实例会话复制。每请求 introspect 持续核查中央撤权。不得将这一实现直接当多实例广播撤销。

这些兼容修正不新增数据库迁移，保留既有009及主线 V2 范围迁移。V2 迁移由运行方按同版部署方案单独核验，不自动为历史记录认领范围。发布前按当前部署清单核版本、组织与主体映射；回退须恢复已核验的完整兼容版本及配置，不能只换旧 JAR。中央门户部署由中央集成方管理。前端资源和 HTML 中脚本摘要须同批更新。
