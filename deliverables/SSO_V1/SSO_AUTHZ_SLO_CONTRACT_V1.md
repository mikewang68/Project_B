# SSO / 授权 / 单点注销 契约 V1

状态：**DRAFT → FROZEN_V1**（本文件冻结后修改需版本号递增）
日期：2026-10-21
适用：IAM、SYS、WMS、PSMS、SCS、EHMS、EMCS、TRUST

---

## 0. 设计前提（基于实际代码盘点）

| 事实 | 后果 |
|---|---|
| IAM JWT 是 **HS256 单密钥**，无 `iss`/`aud` | 不能把密钥分发六系统 |
| IAM 已有 `iam_session`（insert/active/revokeUser） | 可做撤销源 |
| IAM 无 OIDC / JWKS / introspection | 需新建 |
| WMS 已有 `sso/exchange`，且**验签 + aud + 60s + sub 映射**都有效 | 可作参考实现与首个集成目标 |
| WMS 该实现**未回流仓库** | 需 Owner 先回流，或我们按其行为对齐 |

**V1 选择**：**服务器到服务器的短票交换（Backend-for-Backend Ticket Exchange）**，不引入 OIDC，不改 IAM 现有登录。

---

## 1. 身份链

```
用户
  │ 1. 用户名/密码（+ 验证码，若该系统要求）
  ▼
IAM /auth/login
  │ 2. 签发 IAM 会话 JWT（HS256，仅 IAM 内部使用）
  ▼
Portal / BFF
  │ 3. BFF 持有 IAM token + 会话；不向浏览器暴露长期票据
  │ 4. 用户点击某系统 → BFF 判定该用户是否有该系统权限
  ▼
IAM /auth/ticket   ← 【V1 新增】BFF 换取一次性票据
  │ 5. IAM 返回 ticket（属主 = 该用户，aud = 目标系统，jti，≤60s，一次性）
  ▼
目标系统 /auth/sso/exchange   ← 各系统实现
  │ 6. 目标系统验签 + 校验 iss/aud/exp/nbf + 原子消费 jti
  │ 7. 按 sub 映射本地受限主体（禁止默认 admin）
  │ 8. 建立本地会话（Session/JWT），返回 Set-Cookie
  ▼
用户浏览器（同源 /<system>/ 前缀）
```

---

## 2. 票据规范（Ticket）

### 2.1 格式

JWT（JWS Compact），**HS256**，使用**独立于 IAM JWT_SECRET 的 `SSO_TICKET_SECRET`**。

> 若后续引入非对称，改为 RS256 + JWKS，版本号升 V2；V1 内部允许 HS256，前提是：
> - 密钥仅存在于 IAM 与目标系统的服务端配置（0600）
> - 不写入浏览器、不写入前端 bundle、不写入日志

### 2.2 Claims

| Claim | 类型 | 必填 | 说明 |
|---|---|---|---|
| `iss` | string | ✅ | 固定 `project-b-iam`（可配置，必须校验） |
| `aud` | string | ✅ | 目标系统：`iam`\|`sys`\|`wms`\|`psms`\|`scs`\|`ehms`\|`emcs`\|`trust` |
| `sub` | string | ✅ | IAM 用户主键（`iam_user.id`），**不是 username** |
| `username` | string | ⚪ | 便于审计；不作为映射依据 |
| `jti` | string | ✅ | UUID，**一次性** |
| `iat` | number | ✅ | 签发时间（秒） |
| `nbf` | number | ✅ | 生效时间（秒） |
| `exp` | number | ✅ | 过期时间（秒），`exp - iat ≤ 60` |
| `sid` | string | ⚪ | IAM 会话 ID（`iam_session.id`），用于联动注销 |
| `company` | string | ⚪ | 公司/租户码 |
| `org` | string | ⚪ | 组织码 |
| `domain` | string | ⚪ | 业务域 |
| `zone` | string | ⚪ | 区域 |
| `perms` | string[] | ⚪ | 该系统的**只读视角**权限码（不用于最终授权，仅供审计） |

### 2.3 硬性校验（缺一 fail-closed）

目标系统必须依次校验：

1. 签名有效（算法白名单仅允许 `HS256`，显式拒绝 `none`）
2. `iss` 等于配置值
3. `aud` 等于本系统 code
4. `nbf ≤ now ≤ exp`
5. `exp - iat ≤ 60`
6. `jti` 未被消费过（原子操作）
7. `sub` 对应用户存在且 `status = active`
8. 可选：`sid` 对应的 IAM 会话未撤销

---

## 3. 一次性与防重放

### 3.1 消费存储

目标系统维护 `sso_ticket_consumed(jti PK, sub, aud, consumed_at)`，保留 ≥ 10 分钟。

消费必须是**原子**的：

```sql
INSERT INTO sso_ticket_consumed(jti, sub, aud, consumed_at) VALUES (?,?,?,?)
-- 主键冲突 = 已用过 → 拒绝
```

### 3.2 重放窗口

即使在有效期内，同一 `jti` 第二次使用也必须被拒绝。

---

## 4. 主体映射

### 4.1 原则

| 规则 | 说明 |
|---|---|
| R1 | 按 `sub`（IAM userId）映射，**禁止按 username 猜测** |
| R2 | 映射表显式配置；未命中则**拒绝**，不得 fallback 到 admin |
| R3 | 映射结果必须是**最小权限主体**，不得沿用管理系统管理员 |
| R4 | 映射可绑定公司/组织/Zone 范围 |

### 4.2 映射表

各系统自建，例如：

```
sso_subject_map(
  iam_user_id  varchar PK,
  local_user   varchar NOT NULL,   -- 本地受限主体
  company_code varchar,
  org_code     varchar,
  zone_code    varchar,
  enabled      smallint NOT NULL DEFAULT 1
)
```

### 4.3 未映射时的行为

| 场景 | 行为 |
|---|---|
| `sub` 无映射 | **403 + 明确原因**，不建立会话 |
| 映射存在但 `enabled=0` | 403 |
| 映射存在但主体被停用 | 403 |

---

## 5. 授权

### 5.1 分层

| 层 | 职责 |
|---|---|
| Portal/BFF | 决定「菜单是否显示」「是否允许发起换票」 |
| 目标系统 | 决定「该主体能否访问该 API / 该资源」 |

**菜单显示 ≠ API 授权。** BFF 的 403 不作为目标系统鉴权的替代证据。

### 5.2 只读硬否决

角色集合中包含只读审计角色（以 IAM 实际枚举为准，如 `PLATFORM_AUDITOR_READONLY`）时：

- 所有写方法（POST/PUT/DELETE/PATCH）→ 403
- 带副作用的 GET（导出、触发、批量）→ 403
- **多角色时 deny 优先**（同时有 admin 与 readonly → 只读生效）

### 5.3 Domain / Zone

| 规则 | 说明 |
|---|---|
| D1 | `companyCode`/`orgCode`/`domain`/`zone` 来自**服务端会话**，不信任请求参数 |
| D2 | 查询必须附带范围过滤，不能"字符串透传即隔离" |
| D3 | 写操作必须校验目标资源属于该范围 |
| D4 | 字段不存在 → 列为**阻断**，不得视为"全部可见" |
| D5 | `zone-A` 不得读取/修改 `zone-B` 的对象 |

---

## 6. 注销（SLO）

### 6.1 触发

Portal 登出 / IAM 主动撤销 / 会话过期。

### 6.2 顺序

```
1. BFF 撤销自身会话（内存）
2. BFF 调用 IAM /auth/logout → iam_session.revoked_at = now
3. BFF 向每个「已建立业务会话」的系统 POST /auth/logout-notify
     - Body: {sid, sub, reason, timestamp}
     - 签名: 与票据同密钥的短 JWT（aud = 目标系统，jti 一次性）
4. 目标系统校验签名 → 撤销该 sub/sid 对应的本地会话
5. 浏览器侧清除 BFF Cookie（补充手段，不是主手段）
```

### 6.3 硬性要求

| 要求 | 说明 |
|---|---|
| L1 | 目标系统必须 `session.invalidate()` 或等效的服务端撤销 |
| L2 | 仅删 Cookie **不算**注销完成 |
| L3 | 登出后，用旧 Cookie 直连业务后端必须 **401** |
| L4 | 通知失败必须记录并可重试；有失败不得报 `SLO_PASS` |
| L5 | 不依赖浏览器 iframe 逐个域访问 |

---

## 7. 失败处理

| 故障 | 期望行为 |
|---|---|
| IAM 不可达 | 换票失败 → 拒绝进入目标系统（不降级为匿名/模拟） |
| 票据密钥缺失 | 启动即 fail-closed（不生成、不校验） |
| 目标系统映射表缺失 | 拒绝并记录 |
| 消费存储不可用 | 拒绝（不能跳过防重放） |
| 时钟偏差 | 允许 ±30s，超出拒绝 |

---

## 8. 契约测试用例（必须可执行）

| # | 用例 | 期望 |
|---|---|---|
| T1 | 有效票据 | 200 + 建立会话 |
| T2 | 错误签名 | 401 |
| T3 | `alg:none` | 401 |
| T4 | 过期（`exp < now`） | 401 |
| T5 | 未生效（`nbf > now`） | 401 |
| T6 | 错 `aud` | 401 |
| T7 | 错 `iss` | 401 |
| T8 | 同一 `jti` 二次使用 | 401 |
| T9 | `sub` 无映射 | 403 |
| T10 | `sub` 已停用 | 403 |
| T11 | 只读主体写操作 | 403 |
| T12 | 只读 + 高权限双角色写操作 | 403 |
| T13 | 跨 Zone 读取 | 403 或空集（不得外泄） |
| T14 | 跨 Zone 写入 | 403 |
| T15 | 注销后旧 Cookie 直连 | 401 |
| T16 | BFF 返回 403 但绕过 BFF 直连业务 | 业务层仍需 401/403 |
| T17 | 票据有效期 > 60s | 401 |
| T18 | 缺失 `jti` | 401 |

---

## 9. 版本与兼容

| 项 | 规则 |
|---|---|
| 契约版本 | `v1`，写入票据 `ver`（可选） |
| 字段新增 | 向后兼容，接收方忽略未知字段 |
| 字段语义变更 | 版本号递增，双写过渡期 ≥ 1 个发布周期 |
| 密钥轮换 | 支持 `kid`（V2 引入）；V1 通过双密钥并行过渡 |

---

## 10. V1 的已知限制（必须如实标注）

| # | 限制 | 影响 |
|---|---|---|
| 1 | 使用 HS256 共享密钥 | 每个目标系统都持有密钥，任一处泄露即全局风险；V2 应改 RS256 + JWKS |
| 2 | 无 `kid` | 密钥轮换需停机窗口 |
| 3 | 注销通知为主动推送 | 目标系统离线时会丢失，需补偿任务 |
| 4 | Domain/Zone 依赖各系统自建字段 | 未实现的系统只能标 `DZ_BLOCKED` |
| 5 | 未覆盖刷新令牌 | 长会话需重新换票 |

---

## 11. 与现有实现的对应关系

| 现有 | 本契约 | 差异 |
|---|---|---|
| IAM `/auth/login` JWT | 保留（IAM 内部） | 新增 `iss`/`aud` 可选 |
| BFF `create_sso_ticket` | 演进为调用 IAM `/auth/ticket` | `sub` 从 username 改为 userId |
| WMS `/auth/sso/exchange` | 目标端实现参考 | 需补 `jti` 消费与 `sub` 映射 |
| SCS `resolvePrincipal` | 需完全替换 | 当前不验签 |
| EHMS RBAC | 需启用并接映射 | 当前关闭 |

---

## 12. 冻结确认

| 项 | 状态 |
|---|---|
| 票据规范 | ✅ FROZEN |
| 校验顺序 | ✅ FROZEN |
| 一次性消费 | ✅ FROZEN |
| 主体映射原则 | ✅ FROZEN |
| 注销顺序 | ✅ FROZEN |
| HS256 → V2 非对称 | ⏸ 待评审 |
