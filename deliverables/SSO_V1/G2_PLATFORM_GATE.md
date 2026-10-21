# Gate 1：平台可信票据 + 隔离验证

日期：2026-10-21
分支：`feature/trusted-sso-v1`
commit：`ee4e0a1`
状态：**`INTEGRATION_TESTED`**（IAM 侧票据链路 15/15 PASS）

---

## 一、结论

| 项 | 结果 |
|---|---|
| 契约冻结 | ✅ `SSO_AUTHZ_SLO_CONTRACT_V1.md` |
| IAM 票据签发 | ✅ `POST /api/v1/iam/auth/sso/ticket` |
| 票据验签 + 一次性消费 | ✅ `POST /api/v1/iam/auth/sso/verify-and-consume` |
| 注销联动（撤销未消费票据） | ✅ `POST /api/v1/iam/auth/sso/revoke` |
| 数据库迁移 | ✅ `010-sso-ticket.sql`（3 表，幂等） |
| 编译 | ✅ BUILD SUCCESS（含 9 个新类） |
| 隔离实例 | ✅ 端口 `18380`，独立验证库 `iam_sso_verify_20261021` |
| 契约测试 | ✅ **15/15 PASS** |
| 目标系统接入（WMS） | ⏸ **未执行**（见第五节） |

---

## 二、实现要点

### 2.1 密钥隔离

| 密钥 | 用途 | 是否可分发 |
|---|---|---|
| `app.jwt.secret`（`JWT_SECRET`） | IAM 登录会话 JWT | ❌ **不可**分发给任何业务系统 |
| `app.sso.ticket-secret`（`SSO_TICKET_SECRET`） | SSO 短票 | ⚠️ 仅分发给需要本地验签的目标系统（V1 的已知限制） |

服务构造时强制校验：

```java
if (secret.length() < 32) {
  throw new IllegalStateException("SSO_TICKET_SECRET 未配置或长度不足 32 字符；可信 SSO 已关闭（fail-closed）");
}
```

**未配置 → 应用启动失败**，不会出现"静默不校验"。

### 2.2 一次性消费（原子）

```sql
UPDATE iam.sso_ticket SET consumed_at=CURRENT_TIMESTAMP, consumed_by=#{consumedBy}
 WHERE jti=#{jti} AND consumed_at IS NULL AND revoked_at IS NULL
   AND expires_at > CURRENT_TIMESTAMP
```

返回 0 行 = 已被消费（重放）→ 拒绝。这是 CAS 语义，并发安全。

### 2.3 换票前置授权

`SsoSubjectGuard.requireBound()` 两道检查：

1. `iam.sso_subject_binding` 有显式登记且 `enabled=1`；
2. 否则回退到**既有导航授权**——该用户在 `projectNavigationTree()` 返回的 systems 中是否包含目标 app。

后者复用了已验收的授权来源，不是新造的第二套逻辑。任何异常 → 拒绝（fail-closed）。

### 2.4 状态码规范

| 场景 | 状态 |
|---|---|
| 签名错 / 过期 / 过早 / TTL 超限 / 重放 / 缺字段 | **401** |
| 未被授权访问目标系统 / 映射停用 / 未知受众 | **403** |
| 成功 | 200 |

> 初版把所有失败都落到 500，会让调用方误判为系统故障。已修正。

### 2.5 会话联动

`JwtAuthFilter` 把 JWT 的 `jti` 写入 `request.setAttribute("iam.sessionId")`，票据签发时作为 `sid` claim 写入，注销时用 `revokeBySession(sid)` 批量撤销未消费票据。

> 该属性不进日志、不回传浏览器。

---

## 三、契约测试结果（15/15）

环境：`iam_sso_verify_20261021`（PG 兼容模式）+ 端口 18380 + 独立 `SSO_TICKET_SECRET`

| # | 用例 | 期望 | 实测 | 判定 |
|---|---|---|---|---|
| — | admin 登录 | 200 | 200 | ✅ |
| — | scoped_user 登录 | 200 | 200 | ✅ |
| T1 | 签发 aud=wms 票据 | 200 | 200 | ✅ |
| T1 | 票据含 jti | 是 | `345a7f29-...` | ✅ |
| T1b | 合法票据验签 + 消费 | 200 | 200 | ✅ |
| T2 | 错误签名 | 401 | **401** | ✅ |
| T3 | `alg:none` | 401 | **401** | ✅ |
| T4 | 已过期 | 401 | **401** | ✅ |
| T5 | `nbf` 在未来 | 401 | **401** | ✅ |
| T6 | 签发时错误受众 | 403 | **403** | ✅ |
| T6b | 验签时错误受众 | 401 | **401** | ✅ |
| T7 | 错误 issuer | 401 | **401** | ✅ |
| T8 | 同一 jti 重放 | 401 | **401** | ✅ |
| T9 | `scoped_user` 请求 wms（无权限） | 403 | **403** + "该主体未被授权访问目标系统: wms" | ✅ |
| T17 | TTL > 60s | 401 | **401** | ✅ |

**15/15 PASS。**

---

## 四、未做的事

| 项 | 状态 | 原因 |
|---|---|---|
| WMS 目标端接入新票据 | ⏸ **未做** | 见第五节 |
| 修改 WMS 源码 | ❌ 未做 | 仓库内无 `sso/exchange` 源码 |
| 部署到生产 IAM 18086 | ❌ 未做 | 需 Owner 评审 + 维护窗口 |
| 修改 `nav_eligible` | ❌ 未做 | 无授权 |
| 其他七系统接入 | ⏸ 未做 | 阶段 3 范围 |

---

## 五、WMS 接入的中断点（必须如实记录）

### 5.1 事实

| 项 | 状态 |
|---|---|
| WMS 运行实例已有 `/api/v1/auth/sso/exchange` | ✅ 可用 |
| 该端点行为 | ✅ 验签、aud 校验、60s TTL、`sub` 独立主体映射全部有效 |
| 该端点源码在 GitHub 仓库 | ❌ **不存在**（全仓库 0 处） |
| 该 JAR 是否可重建 | ❌ 无法从仓库构建出这个端点 |

### 5.2 影响

按契约，WMS 是首选集成目标。但它现有的票据格式与我们 V1 契约有三处差异：

| 项 | WMS 现有 | V1 契约 |
|---|---|---|
| `iss` | `project-b-iam-bff` | `project-b-iam` |
| `sub` | username | IAM userId |
| 密钥 | BFF 的 `SSO_JWT_SECRET` | IAM 的 `SSO_TICKET_SECRET` |
| 一次性消费 | ❓ 未知（无源码） | 必须原子消费 jti |

**在没有 WMS 源码的情况下，我无法：**
- 补上 `jti` 一次性消费
- 修改 `sub` 映射键
- 验证重放行为

### 5.3 解锁条件

| # | 需要 | 负责人 |
|---|---|---|
| 1 | WMS 把 `sso/exchange` 实现回流到 GitHub | 周绪敬 |
| 2 | 确认该端点是否已有 jti 去重 | 周绪敬 |
| 3 | 对齐 `iss`/密钥/sub 语义 | 双方 |

**在此之前，WMS 接入状态为 `BLOCKED_ON_SOURCE`。**

---

## 六、可复现方式

```bash
# 1. 建库（PG 兼容）
CREATE DATABASE iam_sso_verify_20261021 WITH DBCOMPATIBILITY 'PG' TEMPLATE template0;

# 2. 应用 SQL（顺序固定）
002 → 004 → 005 → 009 → 008 → 006 → 007 → 021 → 010

# 3. 生成票据密钥
head -c 48 /dev/urandom | base64 | tr -d '/+=' | head -c 40 > /secure/sso_ticket.env

# 4. 启动
java -jar iam-backend.jar \
  --server.port=18380 \
  --spring.datasource.url="jdbc:postgresql://<host>:5432/iam_sso_verify_20261021?currentSchema=iam" \
  --app.sso.ticket-secret="$(cat /secure/sso_ticket.env)" \
  --app.sso.issuer="project-b-iam" \
  --app.sso.ticket-ttl-seconds=45

# 5. 契约测试
python3 sso_gate1.py   # 15 用例
```

---

## 七、已知限制（V1）

| # | 限制 | 缓解 |
|---|---|---|
| 1 | HS256 共享密钥 | 每个接入系统都持有，任一处泄露影响全局；V2 改 RS256 + JWKS |
| 2 | 无 `kid` | 轮换需停机窗口 |
| 3 | 注销通知为主动推送 | 目标系统离线会丢，需补偿任务（`sso_logout_notify` 已建表） |
| 4 | 消费记录由各系统自管 | 集中式消费记录未实现 |
| 5 | 仅 IAM 侧完成 | 目标系统接入需逐个 Owner 配合 |

---

## 八、下一步

| 优先级 | 事项 | 依赖 |
|---|---|---|
| P0 | 发送 `00_SECURITY_P0_NOTICE.md` | 项目负责人转发 |
| P0 | 请求周绪敬回流 WMS `sso/exchange` 源码 | 周绪敬 |
| P1 | WMS 按 V1 契约对接（含 jti 消费） | 上一条 |
| P1 | SCS 引入 JWT 库 + 验签 | 敬昌隆 |
| P2 | EHMS 启用 RBAC + 映射 | 闫鑫钰 |
| P2 | PSMS / TRUST / EMCS 接入 | 各 Owner |
