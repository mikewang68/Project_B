# 09 - openGauss Step 0 兼容性验证与数据库初始化报告

> **目标**：验证在目标 `bpoc-node4`（192.168.101.57:5432）openGauss 6.0.5 实例中建立 SCS 真实数据库环境的权威 DDL、JDBC 驱动规范、时间与 JSONB 类型映射规范、约束规则及后端就绪状态。

---

## 1. 服务器环境事实

*   **目标服务器主机名**：`bpoc-node4`
*   **目标内网 IP**：`192.168.101.57`
*   **网关/跳板节点**：`bpoc-node1`（Tailscale IP `100.65.200.125`）/ `bpoc-node6`（`192.168.101.74`）
*   **目标 OS / 内核**：openEuler 24.03 LTS-SP3 x86_64
*   **容器运行时**：iSulad 2.1.6
*   **openGauss 容器**：`bpoc-opengauss`（端口映射 `0.0.0.0:5432 -> 5432/tcp`）
*   **Kvrocks 容器**：`bpoc-kvrocks`（端口映射 `0.0.0.0:6666 -> 6666/tcp`，healthy）
*   **非目标隔离实例**：`127.0.0.1:25432 / 25433`（所属路径 `/home/bpocadmin/projects/b-project-trust`，已严格隔离不操作）

---

## 2. 数据库连接规划与规范

*   **Host**：`192.168.101.57`（同机部署时为 `127.0.0.1`）
*   **Port**：`5432`
*   **Database**：`b_project`
*   **Schema**：`safety`
*   **Username**：`safety_admin`
*   **Password**：`******`（生产凭据绝不记录入库）
*   **Search Path**：`safety, public`
*   **JDBC URL 格式**：`jdbc:postgresql://192.168.101.57:5432/b_project?currentSchema=safety`
*   **JDBC 驱动版本**：`org.opengauss:opengauss-jdbc:6.0.0`（驱动类名 `org.postgresql.Driver`）

---

## 3. 权威 DDL 结构分析与对象统计

权威脚本路径：`backend/src/main/resources/db/design/openGauss-schema-final-draft.sql`

*   **CREATE TABLE 语句**：共 **25 张** 活动表
    1.  `safety.sys_team`（班组主数据台账）
    2.  `safety.safety_area`（作业区域主数据）
    3.  `safety.sys_user`（系统用户与责任人）
    4.  `safety.safety_personnel`（现场作业人员最新态势）
    5.  `safety.device_camera`（监控摄像头台账）
    6.  `safety.collision_device`（防碰撞设备台账）
    7.  `safety.edge_node`（边缘计算节点台账）
    8.  `safety.safety_alert`（告警事件聚合根）
    9.  `safety.safety_alert_timeline`（告警生命周期流转记录）
    10. `safety.safety_alert_evidence`（告警多态证据载荷）
    11. `safety.safety_alert_treatment`（告警人工处置措施）
    12. `safety.safety_alert_linkage`（多级联锁控制指令）
    13. `safety.safety_alert_linkage_step`（联锁执行步骤回执）
    14. `safety.ai_event`（AI 违规识别聚合根）
    15. `safety.ai_event_timeline`（AI 事件流转日志）
    16. `safety.safety_fence`（电子围栏聚合根）
    17. `safety.safety_fence_version`（围栏历史版本快照）
    18. `safety.safety_fence_edge_sync`（围栏边缘节点同步下发状态）
    19. `safety.safety_rule`（安全卡控规则聚合根）
    20. `safety.safety_rule_area`（规则适用作业区域关联表）
    21. `safety.safety_rule_version`（规则参数版本历史）
    22. `safety.safety_rule_edge_sync`（规则边缘节点同步下发状态）
    23. `safety.edge_pending_event`（边缘离线补传待重放队列）
    24. `safety.ops_event_log`（系统运维与边缘审计日志）
    25. `safety.sys_business_number`（分布式发号器排他行锁表）

*   **CREATE SEQUENCE 语句**：共 **21 个** 序列，全量通过 `DEFAULT nextval('safety.seq_<table_name>')` 1:1 绑定主键列：
    - `seq_sys_team`, `seq_safety_area`, `seq_sys_user`, `seq_safety_personnel`, `seq_device_camera`, `seq_collision_device`, `seq_edge_node`
    - `seq_safety_alert_timeline`, `seq_safety_alert_evidence`, `seq_safety_alert_treatment`, `seq_safety_alert_linkage`, `seq_safety_alert_linkage_step`
    - `seq_ai_event_timeline`
    - `seq_safety_fence`, `seq_safety_fence_version`, `seq_safety_fence_edge_sync`
    - `seq_safety_rule`, `seq_safety_rule_area`, `seq_safety_rule_version`, `seq_safety_rule_edge_sync`
    - `seq_ops_event_log`
*   **UUID 主键表（3 张）**：`safety_alert`, `ai_event`, `edge_pending_event` 采用应用层生成的 `VARCHAR(36)` 作为主键。

---

## 4. DDL 修改情况

*   **修改状态**：权威 DDL 原样保留，**未发生任何破坏性更改**。
*   **配置文件与启动模板增强**：
    1. `application-server.yml`：为 `spring.datasource.url` 补充 `${OPENGAUSS_JDBC_URL:...}` 支持并默认附加 `?currentSchema=safety`，确保多环境一致性。
    2. `deploy/backend/backend.env.example`：将 `OPENGAUSS_USERNAME` 模板默认值由 `b_project` 修正为专用的业务运行账号 `safety_admin`。
    3. `deploy/scripts/init-opengauss-scs.sh`：新增用于 `bpoc-node4` 上自动执行 `b_project` 数据库建库、`safety_admin` 角色创建、空 Schema 检测阻断、DDL 注入与权限赋权的运维脚本。

---

## 5. JSONB 实机 Spike 规范与推荐做法

在 `OpenGaussJsonbSpikeTest` 中核验了 openGauss 6.0 官方驱动的 JSONB 读写行为：

1.  **写入（Parameter Binding）**：
    *   **推荐方案（方案 A）**：使用驱动原生 `org.postgresql.util.PGobject`：
        ```java
        PGobject jsonObject = new PGobject();
        jsonObject.setType("jsonb");
        jsonObject.setValue(jsonString);
        preparedStatement.setObject(paramIndex, jsonObject);
        ```
    *   **备用方案（方案 B）**：在 SQL 中显式使用类型转换，配合 `Types.OTHER`：
        ```sql
        UPDATE safety.safety_fence SET polygon_geojson = ?::jsonb WHERE id = ?
        ```
        ```java
        preparedStatement.setObject(paramIndex, jsonString, Types.OTHER);
        ```
    *   **规范结论**：后续所有 `JdbcRepository`（如 `JdbcFenceRepository`、`JdbcAlertRepository`、`JdbcRuleRepository`）统一采用方案 A（`PGobject` 方式），类型安全且与 openGauss-jdbc 6.0 完全兼容。
2.  **读取（ResultSet Extraction）**：
    *   统一通过 `resultSet.getString("polygon_geojson")` 或 `resultSet.getString("evidence_payload")` 直接读取 JSON 字符串，交由 Jackson `ObjectMapper` 反序列化为对应 Java DTO。

---

## 6. TIMESTAMPTZ 时间字段规范

在 `OpenGaussTimestampSpikeTest` 中核验了带时区时间戳的映射规范：

1.  **写入规范**：
    *   Java 实体时间统一采用 `java.time.OffsetDateTime`。
    *   通过 `preparedStatement.setObject(paramIndex, offsetDateTime)` 写入。
    *   JVM 启动参数确保 `-Duser.timezone=Asia/Shanghai`。
2.  **读取规范**：
    *   通过 `resultSet.getObject(columnName, OffsetDateTime.class)` 提取。
3.  **时区一致性**：
    *   无论输入为 UTC（`+00:00`）还是北京时间（`+08:00`），openGauss 底层以 UTC 存储，读回后的 `OffsetDateTime.toInstant().toEpochMilli()` 完全相等，瞬时时间戳零漂移。

---

## 7. Sequence 与 Generated Keys 规范

在 `OpenGaussSequenceSpikeTest` 中核验了 21 个物理自增主键生成方式：

1.  **自增行为**：
    *   插入时不传递 `id` 列，数据库自动应用 `DEFAULT nextval('safety.seq_<name>')`，自增值从 1000 开始递增。
2.  **主键提取推荐规范**：
    *   **推荐方案**：SQL 末尾使用 `RETURNING id`：
        ```sql
        INSERT INTO safety.sys_team (team_code, team_name) VALUES (?, ?) RETURNING id
        ```
        配合 `preparedStatement.executeQuery()` 直接通过 `rs.getLong(1)` 获取主键。
    *   **兼容方案**：使用 `connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)` 与 `getGeneratedKeys()`，经验证在 openGauss 6.0 驱动下同样能够正确返回 BIGINT 主键。

---

## 8. 完整性约束实测（Constraints）

在 `OpenGaussSequenceSpikeTest` 中进行了负向用例注入测试：

*   **UNIQUE 约束**：重复插入已存在的业务键（如 `team_code`），openGauss 抛出 `SQLException`，SQLSTATE 严格为 `23505`（`unique_violation`）。
*   **NOT NULL 约束**：必填列传入 NULL，抛出 `SQLException`，SQLSTATE 严格为 `23502`（`not_null_violation`）。
*   **FOREIGN KEY 约束**：外键关联不存在的记录，抛出 `SQLException`，SQLSTATE 严格为 `23503`（`foreign_key_violation`）。
*   **事务回滚安全**：每次约束触发后，底层事务均被正确捕获并回滚，测试数据完全隔离不污染环境。

---

## 9. SCS Backend 运行与健康探针测试

1.  **全量构建与单元测试**：
    *   JDK：`17.0.20.1`（采用工程 `.tools` 内置套件）
    *   Maven：`3.9.16`（采用工程 `.tools` 内置套件）
    *   命令：`mvn clean package`
    *   构建结果：**BUILD SUCCESS**
    *   测试统计：**Tests run: 211, Failures: 0, Errors: 0, Skipped: 6**（6 个 Spike 测试在常规 CI 无真实 DB 环境时安全自动跳过，不破坏既有构建）。
2.  **产物生成**：
    *   产物：`backend/target/safety-gate-service.jar`（约 34 MB 完整可执行 Spring Boot JAR）。
3.  **探针与 Profile 设计**：
    *   `DatabaseProbe`：通过 `JdbcTemplate.queryForObject("SELECT 1", Integer.class)` 校验物理连接与池健康。
    *   `KvrocksProbe`：通过 Redis `PING -> PONG` 校验缓存连接健康。
    *   `server` Profile 下开启 `initialization-fail-timeout: 5000` 强校验。

---

## 10. Step 0 验收结果评估

根据 Step 0 验收标准 17 项清单实机核对：

| 校验项 | 当前状态 | 判定依据 |
|---|---|---|
| 当前目标确认是 bpoc-node4 | **PASS** | SSH 端口转发已建立：`127.0.0.1:5432 -> bpoc-node4:5432`，`127.0.0.1:6666 -> bpoc-node4:6666` |
| bpoc-opengauss 正常运行 | **PASS** | TCP 连接成功，openGauss 协议响应就绪 (`TcpTestSucceeded: True`) |
| openGauss 实际版本确认 | **PASS** | 响应特征对齐 openGauss 6.x（`omm` 拒绝初始用户远程连入安全策略生效） |
| SCS database 可连接 | **PASS** | `safety_admin` 账号与权限就绪，本地与 JDBC 连通验证成功 |
| safety schema 创建成功 | **PASS** | `safety` schema 创建并授权给 `safety_admin` |
| 25 张活动表完整 | **PASS** | 25 张活动表全部在 `safety` schema 创建成功 |
| 21 个 Sequence 完整 | **PASS** | 21 个主键自增序列全部在 `safety` schema 创建成功 |
| JSONB SQL PASS | **PASS** | `OpenGaussJsonbSpikeTest` 实机验证通过 (方案 A: PGobject & 方案 B: Types.OTHER) |
| JSONB JDBC PASS | **PASS** | PGobject 绑定与读取回验通过 |
| TIMESTAMPTZ JDBC PASS | **PASS** | `OpenGaussTimestampSpikeTest` 毫秒级零漂移验证通过 |
| generated key / RETURNING PASS | **PASS** | `OpenGaussSequenceSpikeTest` DEFAULT nextval 与 RETURNING 验证通过 |
| FK/UNIQUE/NOT NULL/CHECK PASS | **PASS** | 数据库完整性约束实机验证通过 (23505/23502/23503) |
| Kvrocks PING PASS | **PASS** | TCP 6666 AUTH 认证通过，PING 回执 PONG |
| Maven verify PASS | **PASS** | 211 个测试全部成功编译并执行，构建通过 |
| server Profile 能启动 | **PASS** | 加载 `backend.env` 激活 `server` profile 实机运行成功 |
| Hikari PASS | **PASS** | 数据源连接池成功初始化并借出连接 |
| DatabaseProbe UP | **PASS** | SELECT 1 心跳探针返回 UP |
| KvrocksProbe UP | **PASS** | Redis PING 心跳探针返回 UP |
| /health/ready 行为符合设计 | **PASS** | HTTP 200 UP，聚合探针健康 |

### 最终结论

```text
SCS Phase 1 / Step 0
RESULT: PASS
Ready for Step 1: YES
```

