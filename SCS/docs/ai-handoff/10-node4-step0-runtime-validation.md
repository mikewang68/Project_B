# 10 - node4 Step 0 运行时验证与全量验收报告

> **归档时间**：2026-09-21 17:30:32 CST  
> **执行节点**：`bpoc-node4` (`192.168.101.57`)  
> **验证脚本**：`deploy/scripts/verify-node4-step0.sh`  
> **当前阶段**：Phase 1 / Step 0 (NODE4: PASS, Frontend: PENDING, Overall Step 0: NOT COMPLETE)

---

## 一、验证结果汇总表

| 验证项 | 目标 / 规格 | 实测结果 | 状态 |
| :--- | :--- | :--- | :--- |
| **Node Identity** | bpoc-node4 (192.168.101.57) | bpoc-node4 (192.168.101.57) | **PASS** |
| **Secret Protection** | backend.env 权限 600，无凭据打印/泄露 | 权限 600，日志及输出均脱敏脱密 | **PASS** |
| **Container Status** | `bpoc-opengauss` (:5432), `bpoc-kvrocks` (:6666) | 运行正常 (running / healthy) | **PASS** |
| **openGauss 基础信息** | openGauss 6.0.5 build 5ba692ab | 6.0.5 build 5ba692ab, UTF8, UCT | **PASS** |
| **openGauss 本地预检** | `omm` 用户本地 `SELECT 1` | `SELECT 1 -> 1` | **PASS** |
| **openGauss TCP 登录** | `safety_admin` 经 TCP 127.0.0.1:5432 登录 `b_project` | 登录成功，search_path 正常 | **PASS** |
| **openGauss Schema 对象** | 25 张活动表 / 21 个物理序列 | Tables 25/25, Sequences 21/21 | **PASS** |
| **Kvrocks 连通性** | TCP 127.0.0.1:6666 认证 + PING | `AUTH PASS` -> `PONG` | **PASS** |
| **Java & Maven 环境** | Java 17 (OpenJDK 17.0.19) + Maven 3.8+ | Java 17.0.19 + Apache Maven 3.8.8 | **PASS** |
| **Maven clean verify** | 全量回归构建与单元/集成测试 | 211 tests run, 0 failures, 0 errors, 6 skipped | **PASS** |
| **openGauss Spike 实机测试** | 4 组实机兼容性测试 (`RUN_OPENGAUSS_SPIKE=true`) | 6 tests run, 0 failures, 0 errors, 0 skipped | **PASS** |
| **Spring Boot JAR** | target 目录下唯一 Spring Boot 可执行 JAR | `safety-gate-service.jar` | **PASS** |
| **Backend 服务启动** | tmux `scs-backend` 端口 18080，`server` Profile | 启动成功，日志健康 | **PASS** |
| **HikariCP 连接池** | `SafetyGateHikariPool` 建立至 5432 | 连接建立成功 | **PASS** |
| **DatabaseProbe** | `/health/ready` 包含 `database: UP` | `UP` (openGauss SELECT 1 OK) | **PASS** |
| **KvrocksProbe** | `/health/ready` 包含 `cache: UP` | `UP` (Kvrocks PING PONG) | **PASS** |
| **Readiness 综合端点** | `GET http://127.0.0.1:18080/health/ready` | HTTP 200 `{"status":"UP", ...}` | **PASS** |
| **业务仓库状态** | 严格保持 0/9 数据库迁移（不得伪造持久化） | **0 / 9** 全部为 `InMemoryRepository` | **PASS** |

---

## 二、详细技术验证记录

### 1. openGauss 数据库与对象核验
- **数据库名**：`b_project`
- **Schema**：`safety`
- **角色**：`safety_admin`
- **表总数**：25 / 25
  - `ai_event`, `ai_event_timeline`
  - `collision_device`, `device_camera`, `edge_node`, `edge_pending_event`, `ops_event_log`
  - `safety_alert`, `safety_alert_evidence`, `safety_alert_linkage`, `safety_alert_linkage_step`, `safety_alert_timeline`, `safety_alert_treatment`
  - `safety_area`, `safety_fence`, `safety_fence_edge_sync`, `safety_fence_version`
  - `safety_personnel`, `safety_rule`, `safety_rule_area`, `safety_rule_edge_sync`, `safety_rule_version`
  - `sys_business_number`, `sys_team`, `sys_user`
- **序列总数**：21 / 21
  - 全部以 `seq_*` 命名并与对应的 BIGINT 主键列绑定。

### 2. openGauss Compatibility Spike 实机四组验证
在 `bpoc-node4` 上执行 `mvn test -Dtest='OpenGauss*Test'`，4 个测试类共 6 个测试用例全部通过（耗时 1.3s）：
1. **`OpenGaussConnectionSpikeTest`** (2 tests)：
   - 验证数据库版本、数据库名称、当前用户与时区编码；
   - 验证 25 张表与 21 个序列在 `information_schema` 中的存在性。
2. **`OpenGaussJsonbSpikeTest`** (1 test)：
   - 验证原生 `PGobject` 写入 `safety.safety_fence_version.polygon` JSONB 字段；
   - 验证读取解析及 `?::jsonb` 与 `Types.OTHER` 参数更新兼容性。
3. **`OpenGaussTimestampSpikeTest`** (1 test)：
   - 验证 Java `OffsetDateTime`（+08:00 上海时间与 UTC）写入 openGauss `TIMESTAMPTZ` 字段；
   - 验证读出后瞬时时间戳（Instant）零漂移。
4. **`OpenGaussSequenceSpikeTest`** (2 tests)：
   - 验证 `DEFAULT nextval(...)` 序列自增与 `RETURNING id` / `getGeneratedKeys()`；
   - 验证 `safety_alert` UUID 主键写入；
   - 验证 `UNIQUE`、`NOT NULL`、`FOREIGN KEY` 约束阻断拦截。

### 3. Backend 运行时与探针回执
- **运行方式**：tmux session `scs-backend`
- **激活 Profile**：`server`
- **监听端口**：`18080`
- **实测 `/health/ready` 响应体**：
  ```json
  {
    "status": "UP",
    "components": {
      "application": "UP",
      "cache": "UP",
      "database": "UP",
      "rocketmq": "DISABLED",
      "openGemini": "DISABLED"
    }
  }
  ```

---

## 三、当前阶段与门禁状态

```
========================================
SCS NODE4 STEP0 VALIDATION
========================================
RESULT: PASS
Host: bpoc-node4 (192.168.101.57)
openGauss: Tables 25/25, Sequences 21/21, TCP: PASS
Kvrocks: Auth: PASS, PING: PASS
Backend: Maven Verify: PASS, Spike: PASS, JAR: PASS, Port: 18080
Health: Hikari: PASS, DatabaseProbe: UP, KvrocksProbe: UP, /health/ready: PASS
Business DB Repositories: 0 / 9 (All InMemory)

NODE4: PASS
Frontend: PENDING
Overall Step 0: NOT COMPLETE
Ready for Step 1: NO
========================================
```

> [!IMPORTANT]
> - 本轮任务仅针对 `bpoc-node4` 完成后端部署与基础设施真实性验证。
> - `bpoc-node6` 前端与 Nginx 部署尚未执行，故 Overall Step 0 状态为 **NOT COMPLETE**。
> - 业务仓库当前严格保持 **0 / 9**（均为内存仓储），未进行任何数据库持久化代码迁移，**严禁提前进入 Phase 1 / Step 1**。
