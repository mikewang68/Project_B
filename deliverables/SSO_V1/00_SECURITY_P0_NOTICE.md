# P0 安全通报（待发送）

日期：2026-10-21
状态：**`READY_TO_SEND`** —— 我无法直接发消息，请项目负责人转发给对应负责人

---

## 一、通报清单

| # | 事项 | 接收人 | 证据 | 严重度 |
|---|---|---|---|---|
| 1 | SCS JWT 不验签，任意伪造身份可读全部业务数据 | 敬昌隆 | `alg:none` + `roles:["ADMIN"]` → **200** | **P0** |
| 2 | SCS 固定 token 后门 | 敬昌隆 | `scs-readonly-token` → **200** | **P0** |
| 3 | SCS 门户链路仍连旧实例（匿名 200） | 敬昌隆 + 环境维护者 | node4:18080 `overview/summary` → **200** | **P0** |
| 4 | EHMS 匿名可读 devices | 闫鑫钰 | `GET /api/ehm/v1/devices` → **200**，`EHM_JWT_ENABLED=false` | **P0** |
| 5 | BFF 运行实例使用源码默认 SSO 密钥 | 环境维护者 | 进程 environ 无 `SSO_JWT_SECRET`，代码含默认字面量 | **P1** |
| 6 | WMS `sso/exchange` 未回流仓库 | 周绪敬 | 仓库 0 处实现，运行 JAR 有该端点 | P1 |

---

## 二、可直接发送的正文

### 2.1 给敬昌隆（SCS）

> 敬昌隆你好，我是雷文。这次是**安全通报**，两个 P0 必须尽快处理。
>
> **1. JWT 只解码不验签。**
> 源码 `SCS/backend/src/main/java/com/bproject/safety/config/SecurityInterceptor.java` 第 178–217 行：
> 只做 `Base64.getUrlDecoder().decode(parts[1])` + `readTree`，没有任何签名校验。
> 我实测：构造 `alg:none` + 空签名 + payload 里写 `roles:["ADMIN"]`，请求 `http://192.168.101.57:18082/api/v1/overview/summary` 直接返回 **200**。
> 而且 `SCS/backend/pom.xml` 里没有 jjwt / java-jwt / nimbus 任何 JWT 库依赖——说明当前**根本不具备验签能力**。
>
> **2. 固定 token 后门。**
> 同文件第 219–230 行：
> ```java
> boolean isReadOnlyTestToken = "scs-readonly-token".equals(token)
>     || (!expectedBearerToken.isBlank() && (expectedBearerToken + "-readonly").equals(token))
>     || token.contains("readonly");
> ```
> 实测 `Authorization: Bearer scs-readonly-token` → **200**。
> `token.contains("readonly")` 意味着任何含该子串的字符串都被当合法只读身份。这应该是遗留调试代码。
>
> **3. 门户仍连旧实例。**
> `/safety/` 现在走 node6:18088 → node4:**18080**（9-21 旧包），匿名 `overview/summary` 返回 **200**。
> 加固版在 18082（匿名 401），但没接进门户。
>
> **需要你做的**：
> 1. 引入 JWT 库（jjwt 或 nimbus），实现签名 + 算法白名单 + `iss`/`aud`/`exp` 校验，显式拒绝 `alg:none`
> 2. 移除 `token.contains("readonly")` 与固定 token 生产逻辑
> 3. 给出修复后的 commit、JAR SHA-256、回滚包
> 4. 决定门户 `/safety/` 是否切到 18082/18445
>
> 我们的 IAM 侧有 HS256 会话令牌，但**不会**把密钥复制给你；如果需要跨系统身份，建议走 IAM 的 introspection 或一次性票据交换（我可以提供契约草案）。
>
> 补充：`/api/v1/fences` 用只读 token 返回 500、`/api/v1/state` 在 18080 是 404 / 18082 是 500，这两个也需要排查。

---

### 2.2 给闫鑫钰（EHMS）

> 闫鑫钰你好，我是雷文。**EHMS 有一个 P0 安全问题**，另外驾驶舱 500 的根因我也查清了。
>
> **P0：匿名可读设备数据。**
> `GET http://192.168.101.57:18083/api/ehm/v1/devices?page=0&size=1` 无任何凭据 → **200**，返回真实设备数据。
> 服务配置里 `EHM_JWT_ENABLED=false`、`EHM_RBAC_ENABLED=false`。
> 需要确认：这是演示期有意开放，还是漏配？如果是演示期开放，请书面确认，我们在验收表里如实标注。
>
> **P0：驾驶舱读取失败。**
> 线上 `/opt/project-b/EHMS/ehm-service.jar` SHA `50fd41e1...`，构建时间 **2026-09-21 16:11**。
> 这个 JAR 里**没有 `OperationalAnalyticsController`**（我用 unzip 列过类清单，analytics 目录下只有 `DashboardController`），而前端 `live-visuals.js` 调用的 `/analytics/overview` 正是它提供的 → **500**。
>
> 顺带解释你说的"本地 404 / 线上 500"：线上旧版 `GlobalExceptionHandler` 没有 `NoResourceFoundException` 分支，所有未匹配路由被兜底成 500。你本地 1.7.3 有那个分支，所以是 404。
>
> **需要你提供**：
> 1. 1.7.3 可部署制品（JAR + 前端 bundle 的 SHA-256）+ 部署窗口
> 2. 确认线上是否应开启强制身份
> 3. 线上持久日志路径（`journalctl -u project-b-ehms` 返回 `-- No entries --`，取不到堆栈）
>
> 我们没动 EHMS 的任何代码、JAR 或配置。

---

### 2.3 给环境维护者

> 你好，我是雷文。两个环境侧的安全问题需要你知道：
>
> **1. BFF 运行实例在用源码里的默认 SSO 密钥。**
> `node6:/home/leiwen/e21_bff.py` 第 48 行：
> ```python
> raw_secret = os.environ.get("SSO_JWT_SECRET", "SecretKey_Stage2D4_...")
> ```
> 运行进程的 environ 里**没有** `SSO_JWT_SECRET`，`~/.secrets/bff_secrets.env` 里虽然有一行但未生效。
> 这意味着默认密钥已随源码公开，任何人可以签发 WMS 票据。
> 仓库里的新版本（PR #11）已改为**必填无默认值**，但线上还没换。
> **建议**：设置真实密钥并重启 BFF（会中断所有人会话，需要维护窗口）。
>
> **2. SCS 门户链路仍指向未加固的旧实例。**
> `/safety/` → node6:18088 → node4:**18080**，匿名可读业务数据（200）。
> 加固版 18082 已就绪（匿名 401），但未接入。
> **建议**：等敬昌隆交付验签修复后，一起切换。

---

### 2.4 给周绪敬（WMS）

> 周绪敬你好，我是雷文。两个事，一个是好消息，一个需要你确认。
>
> **好消息：你们的 SSO 交换实现比我们之前以为的好。**
> 我实测 `/api/v1/auth/sso/exchange`：
> - 错误签名 → 401「签名验证失败」
> - `alg:none` → 401「格式非法」
> - 有效期 >60s → 401「超过限制」
> - 错受众（aud=scs）→ 401「受众无效」
> - `sub=scoped_user` → **200，返回 id=10, roles=["NORMAL"]**
> - `sub=admin` → 200，id=1, roles=["ADMIN"]
>
> 也就是说**独立主体映射是通的**，不是之前说的"所有人都是 admin"。之前那个结论我收回。
>
> **需要你确认：源码没回流。**
> GitHub 仓库 `WMS/WMS/src/main/java/com/mt/wms/auth/AuthController.java` 里只有 `/csrf` `/login` `/me` `/tenant` `/password`，**没有 sso/exchange**。整个仓库搜 `sso/exchange` 是 0 结果。
> 运行中的 `/home/leiwen/project-b-stage2e5/artifacts/mt-wms.jar`（10-17 14:47）有该端点。
> 请把这段实现推回仓库，否则别人无法复现，我也没法给你提补丁。
>
> **需要你处理的一个权限问题**：
> `scoped_user` 在 IAM 侧只有 2 页权限，但换票后在 WMS 拿到了 `integration:manage`、`stockin:write` 等写权限。这个映射过宽，需要收紧。
>
> 另外首登改密弹窗（`passwordChangeRequired=true`）和登出不撤销会话还是老问题。

---

## 三、发送登记

| # | 接收人 | 发送时间 | 确认 |
|---|---|---|---|
| 1 | 敬昌隆 | — | 未发送 |
| 2 | 闫鑫钰 | — | 未发送 |
| 3 | 环境维护者 | — | 未发送 |
| 4 | 周绪敬 | — | 未发送 |

> 本文件状态 `READY_TO_SEND`。我无法直接发消息，需项目负责人转发。
> 发送后请把「发送时间」和「确认」填上，状态改为 `SENT`。
