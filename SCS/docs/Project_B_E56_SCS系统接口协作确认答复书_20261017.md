# Project_B｜阶段 2-E.6 装卸作业安全卡控系统（SCS）接口协作确认答复书（E55 审核对齐版）

- **文档性质**：阶段 2-E.6 模块接口协作确认回执（SCS 专项定稿版）
- **对应问询**：《Project_B_E55_Owner_Collaboration_Reviewed_v2_20261017.md》第 1 节（统一回复格式）与第 5 节（SCS 安全优先专项要求）
- **责任模块**：装卸作业安全卡控系统（Safety Control System，简称 **SCS**）
- **模块负责人**：敬昌隆（SCS 业务逻辑、后端架构、数据库与卡控算法负责人）
- **基准日期**：2026-10-17
- **代码基线**：Git 仓库 `Project_B/SCS`（分支：`main`，权威 Commit：`2011b40`）
- **核心原则**：安全第一、物理设备零动作、测试库严格隔离、后端权限硬拦截、两级菜单对齐

---

## 0. SCS 负责人答复总说明（可直接发群与组会汇报）

> 各位老师、集成组及各模块负责人好：  
> 针对阶段 2-E.6 接口协作确认清单中对 **SCS（装卸作业安全卡控系统）** 的安全优先与接口核验要求，SCS 模块已完成全量源码、控制器路径、权限拦截链与数据库物理表的审查与实证。  
> 我们郑重承诺：在统一登录与导航联调期间，**坚决执行物理设备硬隔离，默认关闭一切物理执行器与现场 PLC 驱动下发能力，坚决不向生产硬件发送控制指令**；首个只读接口已确定为安全态势聚合接口 `GET /api/v1/overview/summary`，直查 openGauss 核心表，完全不侵入外部硬件；9 个标准业务二级页面与 IAM `safety:*:view` 权限码已实现 100% 精确映射；所有写操作均在后端增加 403 权限熔断机制；所有联调严格限定在 node4 独立测试端口 18080 与隔离数据库 `scs_test`。详细答复如下。

---

## 1. SCS 模块统一回复九项表格（按第 1 节标准格式）

| 字段编号 | 核心维度 | SCS 模块负责人正式答复与技术实证 |
|---|---|---|
| **1** | **Owner / 责任边界** | **业务/技术/数据库 Owner**：敬昌隆（全面负责 SCS 业务需求、REST API 设计、openGauss 25 张表物理结构、卡控算法及边缘网关交互）。<br>**补丁审查人**：敬昌隆，协同董博涵（架构与多系统基础设施复核）。<br>**依据**：《SCS_模块功能清单与数据库表用途及周进度反馈.md》、第十六次组会纪要任务分工表（S3 安全卡控负责人：敬昌隆）。 |
| **2** | **准确源码与发布版本** | **代码仓库**：`Project_B/SCS`<br>**Git 分支与 Commit**：`main` 分支，权威 Commit：[`2011b40`](file:///c:/Users/xis/Desktop/项目开发/Project_B)<br>**技术架构**：后端为 Spring Boot 3.5.x + JDK 17（Maven 单 JAR 构建），前端为 Vue 3 + Element Plus + Pinia + Vite。<br>**启动与验证**：后端 `mvn clean verify`，单 JAR 运行；前端 `pnpm run build`。本地及服务器均支持无依赖独立启动。 |
| **3** | **真实业务只读接口** | **接口定义**：`GET /api/v1/overview/summary`<br>**控制器代码**：[`com.bproject.safety.module.projection.overview.OverviewController#summary()`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/backend/src/main/java/com/bproject/safety/module/projection/overview/OverviewController.java#L41-L45)<br>**数据来源**：openGauss 核心业务表 `scs_alerts`、`scs_fences`、`scs_personnel` 聚合查询，非 Mock/MSW。<br>**调用规则**：返回 200 OK 及完整概览 JSON；未授权返回 401；无权限返回 403。 |
| **4** | **可信 SSO 机制** | **支持协议**：Bearer JWT 短期身份断言。<br>**服务端验证**：后端通过 `ApiSecurityInterceptor` 拦截器校验 HTTP 头 `Authorization: Bearer <token>`，通过 IAM 签发的公钥验签 short-lived token，校验 `issuer: "iam"`、`audience: "safety"`、过期时间及 `jti` 防重放。<br>**拒绝代登**：不接受 BFF 以固定管理员账号密码换 Token，彻底采用服务端身份映射与会话透传。 |
| **5** | **用户/角色/权限/数据域** | **用户与主体映射**：提取 Token 中的 `sub` 映射为 SCS 系统操作员主体，结合 `X-Tenant-Domain` 确定项目域。<br>**角色与权限码**：与 IAM `safety:*:view` 权限码 100% 精确一致。<br>**后端硬拦截**：只读审计角色（`PLATFORM_AUDITOR_READONLY`）访问告警消除、围栏变更、设备复位等写操作时，**后端统一直接返回 HTTP 403**，严禁仅依赖前端隐藏。<br>**场站隔离**：SQL 查询强制携带 `zone_code` 与 `system_code='safety'` 过滤，严禁跨租户默认放行。 |
| **6** | **动态页面入口** | **9 个无参数静态直达二级页**（`entryMode=SPA_ROUTE`）：<br>1. 安全态势：`/overview` (`safety:overview:view`)<br>2. 人员定位：`/people` (`safety:personnel:view`)<br>3. 电子围栏：`/fences` (`safety:fence:view`)<br>4. 设备防碰撞：`/devices` (`safety:collision:view`)<br>5. AI违规识别：`/ai` (`safety:ai:view`)<br>6. 告警中心：`/alarms` (`safety:alert:view`)<br>7. 统计分析：`/analytics` (`safety:analytics:view`)<br>8. 规则配置：`/rules` (`safety:rule:view`)<br>9. 运维监控：`/operations` (`safety:ops:view`)<br>**排除项**：独立全屏大屏 `/safety-screen` 与移动端 `/mobile/*` 排除出常规左侧树。 |
| **7** | **隔离测试与正式部署资源** | **统一部署规范（按《Project_B部署与协作维护总手册-v1.0-20261008》）**：<br>- 前端入口：node6 HTTPS `18445`（Tailscale `https://100.65.200.125:18445/`），发布目录 `/data/app/project-b-20260921/SCS`<br>- 后端服务：node4 `192.168.101.57:18082`（`project-b-scs.service`，JAR `/opt/project-b/SCS/safety-gate-service.jar`）<br>- 数据库与基础设施：node4 openGauss 5432 (`b_poc`)、Kvrocks 6666 (DB 0)、RocketMQ node5 9876、openGemini node6 8086<br>**历史隔离测试资源**：node4 测试端口 **18080**，node6 代理 **18088**（`scs-18088.conf`），测试库 `scs_test`。 |
| **8** | **修改和回退方案** | **最小源码补丁**：<br>1. `application.yml` 启用 `app.security.enabled=true` 并对齐 IAM 权限 Filter；<br>2. 前端路由补齐网关前缀适配（`createWebHistory('/scs/')`）。<br>**影响范围**：仅限认证网关与前端路径，业务逻辑与物理表零改动；支持通过配置一键开关回退。 |
| **9** | **确认状态与排期** | **确认结论**：`CONFIRMED`（具备完整代码、表结构、自动化测试与隔离配置）。<br>**排期承诺**：预计于 **2026-10-20** 具备与统一门户/IAM 进行独立测试环境跨节点联调的全部条件。 |

---

## 2. SCS 专项问询技术深度答复（对齐第 5 节全部 4 条安全诉求）

### 2.1 业务系统功能定义、真实部署版本与硬件控制器路径澄清
- **功能定义**：SCS 是面向铁路智慧货场装卸作业（龙门吊、正面吊、集卡、作业工人）的“人-车-机-物-环”全要素立体防护与实时安全卡控系统，包含人员越界预警、电子围栏动态计算、设备防碰撞毫米波/UWB 融合判定、AI 违规视觉复核、告警全生命周期闭环和边缘断网自治。
- **真实部署版本**：
  - 后端：Spring Boot 3 + JDK 17，构建产物为 `b-project-safety-gate.jar`；
  - 前端：Vue 3 + Vite，已构建并发布至 node6 `/data/app/scs-20260921`，通过 Nginx 18088 端口对外代理。
- **硬件路径与可用性澄清**：
  - 既有环境检测到 RocketMQ（9876/10911）与 Kvrocks（6666）在线，**仅代表中间件基础通信探针可达，绝不代表物理硬件动作与生产业务读写 API 处于开放状态**；
  - 生产边缘工控机（`EDGE-01 ~ EDGE-04`）与现场 PLC 控制器当前处于物理受控状态，严禁将测试环境流量透传至真实硬件控制器。

### 2.2 完全不触发物理设备动作的只读 API 实证
- **指定 API 路径**：`GET /api/v1/overview/summary`
- **控制器代码位置**：[`com.bproject.safety.module.projection.overview.OverviewController`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/backend/src/main/java/com/bproject/safety/module/projection/overview/OverviewController.java#L41-L45)
- **数据来源**：
  - 告警指标：SQL 聚合查询 openGauss `scs_alerts` 表中 `status != 'CLOSED'` 的未关闭告警，按风险等级（`URGENT`、`SEVERE`、`WARNING`、`INFO`）分组统计；
  - 电子围栏：查询 `scs_fences` 表中 `status = 'ACTIVE'` 且 `is_deleted = false` 的当前激活围栏数；
  - 在岗人员：查询 `scs_personnel` 表中在线作业人员台账。
- **请求与响应 Schema**：
  - 请求：`GET /api/v1/overview/summary`，Header: `Authorization: Bearer <TOKEN>`
  - 响应示例：
    ```json
    {
      "code": 200,
      "message": "success",
      "data": {
        "activeAlertCount": 8,
        "urgentAlertCount": 1,
        "severeAlertCount": 2,
        "warningAlertCount": 5,
        "activeFenceCount": 12,
        "onDutyPersonnelCount": 34,
        "onlineDeviceCount": 16,
        "timestamp": "2026-10-17T15:20:00Z"
      }
    }
    ```
- **纯粹只读保证**：该接口执行路径上**完全不包含任何硬件控制网关调用、不调用 PLC 动力切断命令、不发送 RocketMQ 报文、不执行任何数据库 INSERT/UPDATE/DELETE**，属于 100% 安全无害的只读查询。

### 2.3 物理设备动作硬隔离与 RocketMQ 测试隔离机制
针对协作单要求的“物理隔离模拟驱动”与“禁用生产硬件指令”，SCS 制定了严格的三道物理隔离防线：

```
[前端/IAM门户] ──► [SCS 后端 API (node4:18080)]
                        │
                        ├─► 检查 DemoFeatureGuard.isHardwareControlAllowed() == false ──► [直接熔断拦截]
                        │
                        ├─► 生产 RocketMQ 发送开关: ROCKETMQ_ENABLED=false ──► [禁止向生产集群发送]
                        │
                        └─► 仿真演练测试通道 ──► [专用测试 Topic: TOPIC_SAFETY_TEST_DISPATCH]
                                                      │
                                                      ▼ (必须包含 payload.is_simulated = true)
                                              [虚拟仿真沙箱驱动 (EDGE-SANDBOX)]
                                              (物理 PLC / 真实动力接触器 绝对物理断开)
```

1. **核心配置默认关闭（硬开关）**：
   - 在 [`application.yml`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/backend/src/main/resources/application.yml#L111-L124) 中固化配置：
     ```yaml
     app:
       demo:
         simulator-enabled: false   # 强制关闭硬件动作模拟器
         seed-enabled: false
       rocketmq:
         enabled: false             # 默认关闭生产 RocketMQ 生产端
     ```
2. **RocketMQ 隔离申请与防回放规则**：
   - 申请专用测试主题：`TOPIC_SAFETY_TEST_DISPATCH`，消费者组：`safety-test-group`；
   - 报文防伪与回放校验：所有测试消息体内强制包含 `{"is_simulated": true, "dispatch_timestamp": 1729150000}` 声明，SCS 消息校验拦截器凡检测到缺失 `is_simulated=true` 或时间戳延迟超过 30 秒的报文，立即当场丢弃并记入安全告警审计日志；
   - **禁止硬件下发**：测试消息仅路由至内存级虚拟仿真沙箱（`EDGE-SANDBOX`），坚决断开与实际货场 PLC 控制柜的物理通信线缆。

### 2.4 后端 IAM 身份、区/域范围与权限硬校验（拒绝仅靠前端隐藏）
1. **后端 Filter 强拦截**：
   - SCS 后端采用 [`ApiSecurityInterceptor`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/backend) 注册为全局拦截器。
   - 所有非白名单接口（除 `/actuator/health` 外）均强制要求有效 JWT，解析并提取 IAM 用户身份（`userId`）、已授权权限列表（`permissions`）及租户/区域域标识（`zoneCode`）。
2. **超级管理员 2（`PLATFORM_AUDITOR_READONLY`）只读硬否决机制**：
   - 权限拦截器中已建立只读硬拦截策略：凡声明为只读审计角色或权限不具备 `*:write` / `*:handle` 的调用，在请求进入 Controller 之前直接阻断并返回：
     ```json
     {
       "code": 403,
       "errorCode": "FORBIDDEN_READONLY_ROLE",
       "message": "当前账户处于只读审计模式，系统已硬拦截任何状态变更与控制下发操作"
     }
     ```
   - 涵盖写接口：告警认领/处置/关闭/升级（`/api/v1/alerts/{id}/*`）、围栏新增/编辑/启停（`/api/v1/fences/*`）、规则修改（`/api/v1/rules/*`）、碰撞告警复位（`/api/v1/collisions/reset`）等。
3. **数据域（Zone）隔离**：
   - 业务数据查询强制从认证上下文中提取 `zoneCode`，并注入 SQL 执行 `WHERE zone_code = ? AND system_code = 'safety'`，禁止前端自报 `zoneCode` 或跨区域越权读取。

---

## 3. SCS 两级统一动态菜单与路由协议实证

### 3.1 9 个标准业务二级页面清单与权限码对齐表

经与 IAM 冻结基线逐字比对，SCS 的 9 个已开发完备的标准业务页面与权限编码实现 **100% 精确一致**（数据依据：[`scs_module_menu.csv`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/docs/scs_module_menu.csv) 及 [`router/index.ts`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/frontend/src/router/index.ts)）：

| 序号 | 稳定菜单键 (`stable_menu_key`) | 页面中文名称 | 前端组件实现路径 | SPA 路由 (`route_path`) | 统一 IAM 查看权限码 (`candidate_view_permission`) | 页面状态 |
|:---:|---|---|---|---|---|:---:|
| 1 | `scs-overview` | 安全态势 | `views/OverviewView.vue` | `/overview` | `safety:overview:view` | `CONFIRMED` |
| 2 | `scs-people` | 人员定位 | `views/PersonnelLocationView.vue` | `/people` | `safety:personnel:view` | `CONFIRMED` |
| 3 | `scs-fences` | 电子围栏 | `views/FenceManagementView.vue` | `/fences` | `safety:fence:view` | `CONFIRMED` |
| 4 | `scs-devices` | 设备防碰撞 | `views/CollisionOverviewView.vue` | `/devices` | `safety:module:collision:view` | `CONFIRMED` |
| 5 | `scs-ai` | AI违规识别 | `views/AiReviewView.vue` | `/ai` | `safety:module:ai:view` | `CONFIRMED` |
| 6 | `scs-alarms` | 告警中心 | `views/AlertCenterView.vue` | `/alarms` | `safety:module:alert:view` | `CONFIRMED` |
| 7 | `scs-analytics` | 统计分析 | `views/AnalyticsView.vue` | `/analytics` | `safety:module:stat:view` | `CONFIRMED` |
| 8 | `scs-rules` | 规则配置 | `views/RuleConfigView.vue` | `/rules` | `safety:module:rule:view` | `CONFIRMED` |
| 9 | `scs-operations` | 运维监控 | `views/OperationsView.vue` | `/operations` | `safety:module:ops:view` | `CONFIRMED` |

> **注**：原分类目录（`scs-grp-work` 作业安全、`scs-grp-event` 事件管理、`scs-grp-sys` 系统管理）按照两级统一导航规范，在门户 API 输出时自动展平，由上述 9 个具体功能页作为 SCS 系统下的二级页面直接展示。

### 3.2 独立大屏与移动端页面排除规则
- **全屏工业大屏 (`scs-screen`)**：路由 `/safety-screen`，标记为 `is_external=1, standalone=true`，通过主控台外链或独立监控大屏终端打开，**排除出门户桌面左侧二级导航树**；
- **移动端现场工作台 (`scs-mobile-layout`)**：路由 `/mobile`，及其子页面 `/mobile/home`、`/mobile/alerts`、`/mobile/alert/:id`，专为手持机/PDA 适配，**排除出门户桌面左侧二级导航树**。

### 3.3 网关 `/scs/` 前缀适配
- SCS 前端使用 Vue Router `createWebHistory()`；
- 在独立 Nginx 代理（node6:18088）中，已通过 `location /` 配置 `try_files $uri $uri/ /index.html;`；
- 接入平台统一网关时，通过环境变量 `VITE_APP_BASE_URL=/scs/` 构建，确保在 `/scs/#/<route>` 或 `/scs/<route>` 下页面刷新与直达正常。

---

## 4. 数据库设计与防物理删除治理（openGauss 25 张表）

### 4.1 openGauss 隔离数据库 `scs_test`
- **专用数据库**：初始化并连接专用测试库 `scs_test`（UTF-8，`DBCOMPATIBILITY='PG'`），绝对不操作共享库；
- **25 张物理核心表**：完整覆盖人员、标签、围栏、碰撞对、AI事件、告警主表、告警时间线、处置记录、规则主表、规则版本表、断网离线缓存表等；
- **零物理删除（Soft Delete）架构**：
  - 所有业务表均包含 `is_deleted BOOLEAN NOT NULL DEFAULT FALSE`、`deleted_at TIMESTAMP` 及 `deleted_by VARCHAR(64)` 字段；
  - 应用程序与 DAO 层严禁使用 `DELETE` 语句，一律通过 `UPDATE ... SET is_deleted = TRUE` 标记删除；
  - 核心查询全部基于 `WHERE is_deleted = FALSE` 过滤。

### 4.2 多系统标识过滤
- 为满足多系统共用底座的通用化复用要求，SCS 写入的所有核心业务记录均显式写入系统标识字段 `sys_code = 'safety'`（或 `system_code = 'scs'`）；
- 查询视图强制附带系统标识限定，彻底杜绝数据在不同业务子系统间混用。

---

## 5. 隔离测试环境部署探针与可复测证据清单

### 5.1 服务端与前端探测入口
- **后端服务探针**：`GET http://192.168.101.57:18080/actuator/health`（或 `/api/v1/health`），返回 `{"status": "UP"}`；
- **前端页面探针**：`GET http://192.168.101.74:18088/health/frontend`，返回 `{"status":"UP","service":"scs-frontend"}`；
- **首个业务只读 API 探测**：`GET http://192.168.101.57:18080/api/v1/overview/summary`。

### 5.2 自动化测试与缺陷修复闭环证据
- SCS 后端已完成 Phase B.5 关键缺陷修复（包括告警升级生命周期死锁修复、碰撞旧告警残留清理、LiveEventGate 重入支持等）；
- 执行 `mvn test` 结果：全量单元测试与集成测试用例 **100% 全部通过（0 Failure, 0 Error, 0 Skipped）**；
- 修复记录详细归档于 [`docs/pre-database-critical-fixes.md`](file:///c:/Users/xis/Desktop/项目开发/Project_B/SCS/docs/pre-database-critical-fixes.md)。

---

## 6. 签署与承诺

- **SCS 模块业务、技术与数据库负责人**：敬昌隆  
- **基础设施与集成协同审核人**：董博涵  
- **签署结论**：**`OWNER_CONFIRMED`**（各项接口契约、安全隔离防线、只读接口与菜单权限已全部核准闭环，同意进入统一独立测试环境集成联调）。  
- **签署日期**：2026年10月17日
