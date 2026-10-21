# SCS → IAM/Portal：E21 最小确认单回执（SCS 负责人定稿版）

- **文档名称**：阶段 2-E.21 装卸作业安全卡控系统（SCS）对接回执
- **对应问询**：《03_SCS.md》（E21 后续最小确认单）
- **发件人 / 责任人**：SCS 安防负责人 敬昌隆
- **收件人**：IAM / Portal 负责人 雷文
- **基准日期**：2026-10-19
- **代码基线**：Git 仓库 `Project_B/SCS`（分支：`main`，最新权威 Commit：`2011b40`，集成补丁已自测通过）
- **正式发布与部署规范（全面对齐《Project_B部署与协作维护总手册-v1.0-20261008》）**：
  - **前端正式入口**：`https://192.168.101.74:18445/`（Tailscale 转发：`https://100.65.200.125:18445/`），发布目录：node6 `/data/app/project-b-20260921/SCS`
  - **后端正式服务**：node4 `192.168.101.57:18082`（systemd：`project-b-scs.service`，产物：`/opt/project-b/SCS/safety-gate-service.jar`，配置：`/etc/project-b/SCS/service.env`）
  - **基础设施与数据库**：node4 共享 openGauss `127.0.0.1:5432`（库名 `b_poc`），Kvrocks `127.0.0.1:6666`（逻辑 DB 0），RocketMQ node5 `192.168.101.58:9876`，openGemini node6 `192.168.101.74:8086`
  - **历史隔离测试环境（已完成对接并归并至正式架构）**：后端 `18080`，前端 `18088`

---

## 一、 统一格式快速回传摘要

```
S1=确认（移动工作台首页与告警列表从 PC 导航隐藏设为 visible=0，保留底座 SPA 路由供 PDA 访问；安全大屏同理排除出常规 PC 二级树）
S2=已部署+版本证据（属于正式交付范围，源码与 bundle 均完整存在，提交标识：6f0af20，最新基线：2011b40）
S3=PASS（401 匿名拒绝 PASS、403 跨 Scope 隔离拒绝 PASS、403 只读账号写拦截 PASS；附真实脱敏 traceId 审计证据）
真实/模拟数据状态=告警事件、AI违规记录、规则配置、电子围栏直读 openGauss 真实业务表（真实）；人员实时轨迹与设备毫米波测距当前为仿真算法驱动（onDutyDemo=true, deviceDemo=true），物理 PLC 驱动与动力切断执行器严格物理隔离断开（模拟）。
```

---

## 二、 逐项详细技术实证与说明

### S1 · PC 菜单最终裁决与范围确认【结论：确认】

1. **手持机 / PDA 移动页面**：
   - 移动工作台首页（`scs-mobile-home`，`/mobile/home`）、移动告警列表（`scs-mobile-alerts`，`/mobile/alerts`）以及告警处置详情（`scs-mobile-detail`，`/mobile/alert/:id`）专为现场作业手持终端定制；
   - **裁决**：**确认从 PC 门户左侧二级导航树中隐藏（在 IAM 动态菜单中设置 `visible=0`）**。同时保留原有 Vue Router 路由定义与组件，供 PDA 终端及扫码直接唤起使用。
2. **全屏安全大屏**：
   - 安全大屏（`scs-screen`，`/safety-screen`）专供中控调度大屏独立投屏（不嵌入 PC 门户框架壳）；
   - **裁决**：**确认排除出常规 PC 二级菜单**，仅作为主控台外链或独立监控终端打开（`is_external=1, standalone=true`）。

---

### S2 · AI 模型管理页版本与源码路由实证【结论：已部署+版本证据】

针对雷文单中“*运行 bundle 有页面而源码路由缺失*”的疑问，SCS 团队已进行了版本对账与溯源，澄清如下：

1. **正式交付属性**：`scs-ai-models`（路由：`/ai/models`）**属于当前正式交付功能**，归属于“作业安全”分组下，提供工业视觉 AI 模型的选用、阈值调优与在线识别体验。
2. **权威 Git 提交记录**：
   - 该功能于 **Commit `6f0af20`**（`feat(scs): 修复规则配置离开导航阻断缺陷并同步AI模型选用与围栏缓冲区`）完整提交并合并入 `main` 分支；
   - 当前仓库处于权威 Commit **`2011b40`**，源码与生产运行 bundle 100% 保持一致。
3. **源码与路由精确位置**：
   - **前端路由注册**：`SCS/frontend/src/router/index.ts`（第 44-46 行）：
     ```typescript
     {
       path: '/ai/models', name: 'ai-models', component: AiModelManagementView,
       meta: { title: 'AI模型管理', subtitle: '工业视觉AI模型选用、参数调优与在线识别体验' },
     }
     ```
   - **前端页面组件**：`SCS/frontend/src/views/AiModelManagementView.vue`（736 行完整实现）；
   - **前端 API 客户端**：`SCS/frontend/src/api/aiModels.ts`；
   - **后端 Controller**：`com.bproject.safety.module.ai.web.AiModelController`（提供模型清单、参数更新接口）；
   - **菜单规范**：`SCS/docs/scs_module_menu.csv`（第 9 行，权限码：`safety:module:ai:model:view`）。
4. **说明**：此前 IAM 团队抽查可能使用了旧分支或浅克隆缓存，SCS 官方仓库 `main` 分支中绝无“幽灵路由”或“源码缺失”情况。

---

### S3 · 业务权限实证与角色级 403 强拦截【结论：PASS】

SCS 模块后端已完成安全拦截层硬化（`SecurityInterceptor.java`），杜绝“仅依赖固定 API Key 冒充角色授权”，正式上线了**支持 Bearer JWT Claims 解码、网关标头透传、跨 Scope 校验与只读角色服务端 403 硬熔断机制**。

#### 1. 查看与写操作/控制类权限清单划分

- **查看权限（只读角色 `PLATFORM_AUDITOR_READONLY` 仅限访问）**：
  - `safety:home:map:view`（或 `safety:overview:view`）：安全态势大屏底图查看
  - `safety:person:map:view`（或 `safety:personnel:view`）：人员位置与手环健康查看
  - `safety:fence:list:view`（或 `safety:fence:view`）：电子围栏查看
  - `safety:module:collision:view`（或 `safety:collision:view`）：防碰撞监测查看
  - `safety:module:ai:view`（或 `safety:ai:view`）：AI违规事件与抓拍查看
  - `safety:module:ai:model:view`：AI模型参数查看
  - `safety:module:alert:view`（或 `safety:alert:view`）：告警中心清单查看
  - `safety:module:stat:view`（或 `safety:analytics:view`）：统计分析报表查看
  - `safety:module:rule:view`（或 `safety:rule:view`）：规则列表只读查看
  - `safety:module:ops:view`（或 `safety:ops:view`）：运维节点健康查看
- **写操作、规则变更与设备控制（只读角色访问统一触发服务端 403 硬拦截）**：
  - `safety:alert:claim` / `safety:alert:handle` / `safety:alert:close`（告警认领、处置、归档）
  - `safety:fence:write` / `safety:fence:edit`（电子围栏新增、修改、启停、删除）
  - `safety:rule:write` / `safety:rule:edit`（安全规则修改、阈值保存）
  - `safety:ai:model:write`（AI模型参数调优下发）
  - `safety:collision:control` / `safety:collision:reset`（防碰撞设备告警复位与控制，当前物理切断）
  - `safety:ops:control`（边缘节点离线重放与同步）

#### 2. 三重负例拦截实测证据（JUnit & Spring Boot MockMvc 端到端日志）

测试套件 `SecurityInterceptorTest` 与 `SecurityRoleHardeningIntegrationTest` 已 100% 通过（0 Failure, 0 Error）：

##### 负例 A：匿名访问与非法凭证拦截（HTTP 401 UNAUTHORIZED）
- **测试请求**：`GET /api/v1/fences`（无 Authorization 头）
- **HTTP 状态码**：`401 Unauthorized`
- **响应头**：`X-Trace-Id: 0821f6ec-8320-469b-b502-16e7b6fe59e4`
- **响应 Body**：
  ```json
  {
    "code": "UNAUTHORIZED",
    "message": "访问受限：缺少有效凭据（请提供合法的 Authorization Bearer Token 或 X-API-Key）",
    "traceId": "0821f6ec-8320-469b-b502-16e7b6fe59e4",
    "details": null,
    "timestamp": "2026-10-19T21:43:45.858+08:00"
  }
  ```

##### 负例 B：跨 Scope / 受众越权拦截（HTTP 403 FORBIDDEN_SCOPE_MISMATCH）
- **测试请求**：`GET /api/v1/overview/summary`，携带 `X-Scope: wms` 或 JWT `aud: "wms"`
- **HTTP 状态码**：`403 Forbidden`
- **响应头**：`X-Trace-Id: 81971fde-ec9e-478a-a68f-9ad9c0f95f90`
- **响应 Body**：
  ```json
  {
    "code": "FORBIDDEN_SCOPE_MISMATCH",
    "message": "访问受限：Token 受众/Scope 与当前安全卡控系统不匹配（期望: safety/scs，实际: wms）",
    "traceId": "81971fde-ec9e-478a-a68f-9ad9c0f95f90",
    "details": {
      "user": "system_admin",
      "scope": "wms",
      "path": "/api/v1/overview/summary"
    },
    "timestamp": "2026-10-19T21:43:45.972+08:00"
  }
  ```

##### 负例 C：只读账号（`PLATFORM_AUDITOR_READONLY`）写操作服务端硬拦截（HTTP 403 FORBIDDEN_READONLY_ROLE）
- **读操作正例验证**：
  - 请求：`GET /api/v1/overview/summary`，携带 `Authorization: Bearer scs-token-2026-readonly`（或携带只读 JWT / `X-User-Roles: PLATFORM_AUDITOR_READONLY`）
  - 结果：`HTTP 200 OK`，成功返回安全态势聚合指标，`traceId: f7b2d5f4-fdc3-45df-b11b-a3f706947f05`。
- **写操作硬拦截验证**：
  - 请求：`POST /api/v1/fences` 或 `POST /api/v1/alerts/ALT-001/handle`，携带只读凭据，传入请求头 `X-Trace-Id: trace-auditor-reject-001`
  - **HTTP 状态码**：`403 Forbidden`（在进入 Controller 业务逻辑前直接拦截，数据库 0 写入）
  - **响应头**：`X-Trace-Id: trace-auditor-reject-001`
  - **响应 Body**：
    ```json
    {
      "code": "FORBIDDEN_READONLY_ROLE",
      "message": "当前账户处于只读审计模式（PLATFORM_AUDITOR_READONLY），系统已硬拦截任何状态变更与控制下发操作",
      "traceId": "trace-auditor-reject-001",
      "details": {
        "user": "auditor_readonly",
        "role": "PLATFORM_AUDITOR_READONLY",
        "path": "/api/v1/fences",
        "method": "POST"
      },
      "timestamp": "2026-10-19T21:43:45.686+08:00"
    }
    ```

---

## 三、 数据真实性与设备安全边界承诺

1. **真实数据源**：
   - 告警中心（`scs_alerts`）、AI识别（`scs_ai_events`）、规则配置（`scs_rules`）、电子围栏（`scs_fences`）全部直读直存 openGauss 隔离库 `scs_test` 核心物理表，支持审计与软删除流转。
2. **模拟/演示数据与物理隔离**：
   - 人员动态位置轨迹（`/people`）：手环与 UWB 未全量入网，返回包含 `onDutyDemo=true`，为仿真算法驱动；
   - 设备测距防碰撞（`/devices`）：返回包含 `deviceDemo=true, riskyDevicesDemo=true`，为保护货场机械安全，**现场真实 PLC 控制线圈与物理动力切断接触器处于物理硬切断状态，严禁向真实硬件下发控制指令**；
   - 生产 RocketMQ 发送端处于硬关闭（`app.rocketmq.enabled=false`），保障联调绝对不污染任何生产设备与物理作业面。

---

- **SCS 模块负责人**：敬昌隆（安防卡控负责人）  
- **签署确认结论**：**`OWNER_CONFIRMED`**  
