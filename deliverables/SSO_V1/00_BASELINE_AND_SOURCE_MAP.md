# 阶段 0 基线与会话源码地图

日期：2026-10-21
工作区：`D:\智慧仓储项目\Project_B_SSO_Integration_20261021`
分支：`feature/trusted-sso-v1`
基线：`origin/main = 2747ba79e779f350fd2bb2ce67f10e9415693163`

---

## 一、工作区与保护

| 项 | 值 |
|---|---|
| 新工作区 | `D:\智慧仓储项目\Project_B_SSO_Integration_20261021`（git worktree） |
| 分支 | `feature/trusted-sso-v1`（基于 `origin/main`） |
| 工作区状态 | **干净**（0 项变更） |
| 原工作树 `Project_B` | `stage2b/iam-navigation` @ `fa7b65c`，**69 项（29 M + 40 ??），0 暂存，全程未触碰** |
| 其他既有 worktree | `Project_B_feature_e21`（已合并）、`Project_B_portal`（已合并）——保留不动 |

---

## 二、运行时基线（只读实测，2026-10-21）

| 系统 | 地址 | 匿名访问 | 制品/版本 | 判定 |
|---|---|---|---|---|
| IAM | node6 `18086` | 空 body 登录 → 400 | `e21_iam_build.jar`（10-19） | 正常 |
| SYS | node6 `18082` | — | — | 正常 |
| Portal BFF | node6 `18295` | `/portal/` 200，受保护路径 401 | `e21_bff.py` SHA `f62a0e2d...` | 正常 |
| WMS | node4 `18084` | `/auth/me` → **401** | `/home/leiwen/project-b-stage2e5/artifacts/mt-wms.jar`（10-17 14:47） | 正常 |
| SCS（门户链路） | node6 `18088` → node4 **`18080`** | `overview/summary` → **200** | 9-21 旧包 | ⚠️ **未加固** |
| SCS（新实例） | node4 `18082` | → **401** | `1d8fe8d1...` | 匿名已修，但 **JWT 不验签** |
| EHMS | node4 `18083` | `/devices` → **200** | `50fd41e1...`（9-21） | ❌ 匿名可读 |
| PSMS | node6 `18300/13100` | — | 静态演示 | ⚠️ 数据不真实 |
| EMCS | node6 `18095` | 原生登录页 | — | 正常（设计边界） |
| TRUST | node6 `28185` | 后端 API → 401 | `c1eb038d...`（10-21 12:47） | 正常 |

---

## 三、IAM 身份实现（阶段 0.3 核心）

### 3.1 现有 JWT

**文件**：`IAM/backend/src/main/java/com/bdemo/iam/security/JwtService.java`

```java
public String generateToken(String userId, String username) {
  Instant now = Instant.now();
  String sessionId = java.util.UUID.randomUUID().toString();
  sessions.insert(sessionId, userId, now.plus(ttl));
  return Jwts.builder()
      .id(sessionId)              // jti = sessionId
      .subject(userId)            // sub = userId
      .claim("userId", userId)
      .claim("username", username)
      .issuedAt(...)
      .expiration(...)
      .signWith(key)              // HS256
      .compact();
}

public Claims parse(String token) {
  Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  if (claims.getId() == null || sessions.active(claims.getId(), claims.getSubject()) != 1)
    throw new JwtException("会话已失效");
  return claims;
}
```

### 3.2 现状评估

| 契约要素 | IAM 现状 | 缺口 |
|---|---|---|
| 签名 | ✅ HS256，`verifyWith` 校验 | — |
| `jti` | ✅ = sessionId | — |
| 会话撤销 | ✅ 每次 parse 查 `iam_session.active()` | — |
| `sub` | ✅ userId（稳定主键） | — |
| **`iss`** | ❌ **无** | 需补 |
| **`aud`** | ❌ **无** | 需补 |
| 密钥 | ⚠️ **单一 HS256 共享密钥**（`JWT_SECRET`） | 不能分发给六系统 |
| `iam_session` 表 | ✅ 有 `insert` / `active` / `revokeUser` | 可复用 |

**结论**：IAM 的 JWT 是**内部会话令牌**，不是可跨系统的身份断言。按契约 D1，**不得**把 IAM 的 HS256 密钥复制给六系统。

### 3.3 AuthController 现有端点

| 方法 | 路径 |
|---|---|
| POST | `/auth/login` |
| GET | `/auth/me` |
| POST | `/auth/logout` |

**没有**：`/auth/exchange`、`/auth/introspect`、JWKS、OIDC discovery。

---

## 四、BFF 短票机制（阶段 0.3 第二个核心）

### 4.1 现有实现

**位置**：`node6:/home/leiwen/e21_bff.py` 的 `create_sso_ticket()`

```python
def create_sso_ticket(username, company="default", exp_delta=60):
    payload = {
        "iss": "project-b-iam-bff",
        "aud": "wms",
        "sub": username,        # ⚠️ 用 username 而不是 IAM userId
        "company": safe_company,
        "iat": now, "nbf": now, "exp": now + exp_delta,
        "jti": str(uuid.uuid4())
    }
    # HS256，密钥为 SSO_JWT_SECRET（与 IAM 的 JWT_SECRET 不同）
```

### 4.2 现状评估

| 契约要素 | BFF 现状 | 评价 |
|---|---|---|
| `iss` | ✅ `project-b-iam-bff` | 固定值，未纳入配置 |
| `aud` | ✅ 固定 `wms` | 仅支持 WMS |
| `sub` | ⚠️ **username** | 契约要求稳定主键（userId） |
| `jti` | ✅ | — |
| `iat/nbf/exp` | ✅ 60s | — |
| 签名 | ✅ HS256 独立密钥 | — |
| **一次性消费** | ❌ 无 | BFF 不记录已用 jti |
| 撤销 | ❌ 无 | — |
| 受众路由 | ❌ 硬编码 wms | 需抽象为适配器 |

### 4.3 密钥现状（安全问题）

| 项 | 值 |
|---|---|
| 运行中 BFF 用的密钥 | **代码内默认字面量** `SecretKey_Stage2D4_Windows_TeacherDemo_...` |
| `~/.secrets/bff_secrets.env` 是否含 `SSO_JWT_SECRET` | 有 1 行，但进程 environ 中为 0 条 |
| 影响 | 默认密钥已随源码公开，任何人可签发票据 |

> PR #11 已把新代码改为**必填无默认值**，但**运行实例仍是旧版**（10-20 部署的副本）。

---

## 五、WMS SSO 交换（重大发现）

### 5.1 仓库源码 vs 运行制品不一致

| 项 | 事实 |
|---|---|
| 仓库 `WMS/WMS/src/main/java/com/mt/wms/auth/AuthController.java` 端点 | `/csrf` `/login` `/me` `/tenant` `/password` |
| 仓库中搜索 `sso/exchange` | **0 结果**（整个仓库） |
| 运行实例 `18084` 端点探测 | `POST /api/v1/auth/sso/exchange` → **401 + "SSO票据不能为空"** |
| 运行 JAR | `/home/leiwen/project-b-stage2e5/artifacts/mt-wms.jar`（10-17 14:47，SHA `114a000033ce775e...`） |

**结论**：WMS 的 SSO 实现**只存在于 Owner 本地构建的制品中，未回流到 GitHub 仓库**。

### 5.2 实测：WMS 票据校验比预期严格

| 测试 | 结果 | 判定 |
|---|---|---|
| 空 ticket | 401 `SSO票据不能为空` | ✅ |
| 错误签名 | 401 `SSO票据签名验证失败` | ✅ **验签有效** |
| `alg:none` | 401 `SSO票据格式非法` | ✅ **拒绝 none** |
| 有效期 >60s | 401 `SSO票据有效时间超过限制(<=60s)` | ✅ |
| `aud=scs`（错受众） | 401 `SSO票据受众(aud)无效: scs` | ✅ **aud 校验有效** |
| `sub=scoped_user`（正确签名、45s） | **200**，返回 `id=10, username=scoped_user, roles=["NORMAL"]` | ✅ **独立主体** |
| `sub=admin` | 200，`id=1, roles=["ADMIN"]` | ✅ |

### 5.3 此前的结论需要更正

历史报告称"所有 Portal 用户统一映射本地 admin"。**实测不成立**：

- BFF 传 `sub=username`
- WMS 按 `sub` 查本地用户，返回**各自的 id / roles / permissions**
- 网关日志可见 12:52 的 `scoped_user` 换票成功记录

**正确的描述**：WMS 已具备独立主体映射，但**映射到的权限过宽**（见 5.4）。

### 5.4 新发现：scoped_user 在 WMS 权限过宽

`sub=scoped_user` 换票返回：

```json
{
  "username": "scoped_user",
  "displayName": "WMS操作员",
  "roles": ["NORMAL"],
  "permissions": ["tenant:switch", "integration:manage", "stockin:write", "stockout:read", ...]
}
```

该账号在 IAM 侧只有 2 页权限（用户列表 + 字典），却在 WMS 拿到 `integration:manage`、`stockin:write` 等写权限。**这是业务权限映射问题，归 WMS Owner。**

---

## 六、SCS 安全缺陷（源码级确认）

**文件**：`SCS/backend/src/main/java/com/bproject/safety/config/SecurityInterceptor.java`

### 6.1 缺陷 1：JWT 只解码不验签（第 178–217 行）

```java
if (token.contains(".")) {
    String[] parts = token.split("\\.");
    if (parts.length >= 2) {
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode node = objectMapper.readTree(payloadBytes);
            // 仅检查 exp，无签名验证
            String sub = node.path("sub").asText(...);
            Set<String> roles = ...;       // 直接从 payload 读
            Set<String> permissions = ...; // 直接从 payload 读
            return new AuthPrincipal(sub, roles, permissions, scope, expired, isReadOnly);
```

**依赖检查**：`SCS/backend/pom.xml` 中**没有** jjwt / java-jwt / nimbus 依赖；`SecurityInterceptor` 中**没有**任何 `verify` / `Signature` / `JWK` / `publicKey` 代码。

**结论**：SCS 当前**不具备**验签能力，需引入 JWT 库或改为调用 IAM introspection。

### 6.2 缺陷 2：固定 token 与子串匹配（第 219–230 行）

```java
boolean isReadOnlyTestToken = "scs-readonly-token".equals(token)
        || (!expectedBearerToken.isBlank() && (expectedBearerToken + "-readonly").equals(token))
        || token.contains("readonly");
```

**实测**：`Authorization: Bearer scs-readonly-token` → **200**（18082 新实例）。

### 6.3 门户链路仍指向旧实例

| 链路 | 匿名 `overview/summary` |
|---|---|
| 门户 `/safety/` → node6:18088 → node4:**18080** | **200**（无鉴权） |
| node4:**18082**（新实例） | **401** |

---

## 七、EHMS 安全与可用性

| 检查 | 结果 |
|---|---|
| 匿名 `GET /api/ehm/v1/devices?page=0&size=1` | **200** |
| `analytics/overview?days=30` | **500** |
| 运行 JAR | `50fd41e1...`（2026-09-21） |
| `EHM_JWT_ENABLED` | **false** |
| `EHM_RBAC_ENABLED` | **false** |
| `GlobalExceptionHandler` 是否有 `NoResourceFoundException` | ❌ 无（所有未匹配路由 → 500） |
| `OperationalAnalyticsController` 是否在 JAR 内 | ❌ 不存在 |

---

## 八、八系统能力矩阵（当前真实状态）

| 系统 | NAV | PAGE | DATA | AUTHZ | SECOND_LOGIN | GLOBAL_LOGOUT | DZ | FORMAL |
|---|---|---|---|---|---|---|---|---|
| IAM | ✅ | ✅ | ✅ | ✅ | ✅（自身） | ✅ | ❌ | ✅ 4 页 |
| SYS | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ 3 页 |
| WMS | ✅ | ✅ | ✅ | ⚠️ PARTIAL | ✅ **独立主体** | ❌ | ❌ | ❌ |
| SCS | ✅ | ✅ | ⚠️ 仿真 | ❌ **可伪造** | ✅（无认证） | ❌ | ❌ | ❌ |
| EHMS | ✅ | ✅ | ⚠️ 500 | ❌ **匿名可读** | ✅（无认证） | ❌ | ❌ | ❌ |
| PSMS | ✅ | ⚠️ 静态 | ❌ | ⚠️ 401 | ❌ | ❌ | ❌ | ❌ |
| EMCS | ✅ | ✅ 登录页 | ⏸ | ❌ | ❌ | ❌ | ❌ | ❌ |
| TRUST | ✅ | ✅ | ✅ | ⚠️ 映射 0 | ❌ | ❌ | ❌ | ❌ |

**正式发布：2/8**

---

## 九、源码定位清单（供阶段 2/3 使用）

### 9.1 IAM

| 文件 | 作用 |
|---|---|
| `security/JwtService.java` | JWT 签发/校验 |
| `security/mapper/SessionMapper.java` | `iam_session` insert/active/revokeUser |
| `auth/AuthController.java` | login / me / logout |
| `auth/AuthService.java` | 登录逻辑 + PasswordEncoder |
| `security/LoginUser.java` | 主体对象（roles/accessMode/readOnly） |
| `security/ReadonlyHardVetoInterceptor.java` | 只读硬否决 |
| `config/SecurityConfig.java` | 拦截器注册 + 401 体 |
| `user/UserService.java` | `load()` 从 DB 推导角色 |
| `navigation/*` | 菜单投影（已完成，不改） |

### 9.2 WMS

| 文件 | 作用 |
|---|---|
| `auth/AuthController.java` | ⚠️ 仓库版**无** sso/exchange |
| `auth/AuthService.java` | 登录 |
| `auth/ActiveAccountFilter.java` | 账号状态过滤 |
| `auth/WmsPrincipal.java` | 主体 |
| `auth/TenantContextService.java` | 租户上下文 |
| `common/config/SecurityConfiguration.java` | 安全配置 |

### 9.3 SCS

| 文件 | 作用 |
|---|---|
| `config/SecurityInterceptor.java` | **缺陷集中地**（178–230 行） |

### 9.4 EHMS

| 文件 | 作用 |
|---|---|
| `shared/web/GlobalExceptionHandler.java` | 缺 `NoResourceFoundException` |
| `analytics/adapter/in/web/DashboardController.java` | 仅 dashboard，无 operational analytics |
| `asset/adapter/in/web/DeviceController.java` | devices API |

### 9.5 其他

| 系统 | 说明 |
|---|---|
| PSMS | 前端静态；后端 `optionalAuth` 需审计 |
| EMCS | RuoYi 验证码，需 SSO 桥接端点 |
| TRUST | 六页可用；`trust:*` 权限码未入 IAM 库 |

---

## 十、缺失材料登记

| 材料 | 状态 |
|---|---|
| `Project_B_Owner_Source_Fix_Report_20261021.md` | ❌ 未找到（不在项目根目录） |
| WMS `sso/exchange` 源码 | ❌ 不在仓库（仅Owner本地制品） |
| SCS JWT 库依赖 | ❌ pom 中不存在 |
| TRUST 15 个功能码明细 | ⚠️ 仅回执中的 6 个读取码 + 文字描述 |
| 各系统 Domain/Zone 字段定义 | ⚠️ 未在源码中定位到统一约定 |

---

## 十一、P0 通报

见 `00_SECURITY_P0_NOTICE.md`。状态：**`READY_TO_SEND`**（我无消息连接，需项目负责人转发）。
