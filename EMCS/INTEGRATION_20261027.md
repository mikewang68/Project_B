# EMCS 2026-10-27 源码接收与验证

接收成员源码包中EMCS的24文件累计补丁。源包基线为a922b7d、目标为9cce4f3；本次独立分支基于实际主线75cfcff。以目标版本 `deploy/isolated-gateway/SSO_PROTOCOL.md` 的原生Cookie会话、中央实时授权和CSRF契约为准，不混用旧PR #15的Bearer交换及浏览器postMessage方案。旧分支保留，未覆盖其工作。

## 本次模块能力

- 由统一桥以专用服务身份调用 `/auth/sso/exchange`，消费服务端票据并建立EMCS会话；`/auth/sso/revoke`处理撤销。
- 每个请求重新调用IAM会话查询，并重新检查本地绑定enabled/userId、账号状态、准确subject/sessionId/localSubject/audience、allowedAppCodes包含EMCS、权限和只读状态。绑定撤销、缺失、无法读取或系统范围不符拒绝访问。
- 本地管理员与中央管理员资格相交；专用lw_emcs_admin仅在中央当前applicationAdmin允许时获得EMCS内管理能力。只读优先；值班员确认告警与派发/关闭等流转权限分开。
- Cookie写请求核验X-EMCS-CSRF；前端恢复原生会话并在写请求前获取CSRF，缺失时不发送业务写请求。
- 原生JWT撤销记录保存在sys_token_revocation；原验证码登录保留。会话不在每次读请求旋转，兼容浏览器并发读取。

## 配置与发布前置条件

私有配置键：emcs.sso.enabled、emcs.sso.iam-base-url、emcs.sso.service-token、emcs.sso.bindings-file。绑定文件以IAM稳定subject为键，包含enabled、userId及按method/pathPattern/permission登记的routes。真实账号映射、凭据、URL与验收记录不进Git。

先在获准独立openGauss库执行 `sql/026_token_revocations.sql` 并验证角色正反例与重启后原生JWT仍被撤销，再同版发布前后端及统一桥。生产Cookie须在批准HTTPS域名使用host-only、HttpOnly、Secure、SameSite=Lax，部署方核实Spring session配置与网关Cookie转交。不存在可用测试环境时不得声称数据库、浏览器统一登录或部署通过。

当前EMCS无法安全执行非空company/org/domain/zone范围时直接拒绝，不能把未知范围转为全域。会话登记为单进程，不能未经共享会话设计直接扩容多实例。回退恢复已验证制品和配置，保留新增兼容撤销表，不通过删表回退；旧版本缺少隔离能力时停用相关入口或专用账号。

## 验证边界

运行 `backend-java` 下的 `mvn -B test package` 及 `web` 的Node测试与生产构建。新增回环HTTP测试验证交换后系统范围、sid、映射禁用/重绑/缺失等变化即时生效；原包测试覆盖Cookie并发读、换会话、CSRF、中心撤销、告警角色和本地退出路由。模拟测试不等于真实IAM或数据库联调。

周绪敬网关包是WMS扫码、称重及工艺设备协议参考资料，不是EMCS点位或获准采集入口。未将其中APK、设备控制、私有运行数据或S2点位混入本模块，也不据此宣称能源实时采集完成。
