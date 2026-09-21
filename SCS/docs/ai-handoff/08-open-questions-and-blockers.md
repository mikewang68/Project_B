# 08 - 未决问题与阻塞项清单（Open Questions & Blockers）

> **编制说明**：本清单经过严格源码与目录审计，**已剔除所有通过阅读现有代码即可解答的问题**。仅记录确实无法由当前仓库自身闭环、必须依赖外部团队或真实环境输入的关键阻塞项。

---

## 一、P0 阻塞项（不解决无法启动真实数据库迁移）

### 1. openGauss 真实测试数据库实例与授权 [RESOLVED / 已解决]
- **环境事实已确认**：
  - 实例节点：`bpoc-node4`（IP: `192.168.101.57:5432`）
  - 容器名称：`bpoc-opengauss`（openGauss 6.0.5）
  - 关联缓存：`bpoc-kvrocks`（`192.168.101.57:6666`，healthy）
  - 隔离实例：`127.0.0.1:25432/25433`（`b-project-trust`，严禁操作）
- **当前执行状态**：
  - [RESOLVED] 运行 `deploy/scripts/init-opengauss-scs.sh` 完成 `b_project` 库、`safety_admin` 角色与 `safety` schema 初始化；
  - 25 张表与 21 个序列全部建表赋权成功；
  - 4 组 Compatibility Spike 测试类全部实机验证通过（JSONB, TIMESTAMPTZ, Sequence, Constraints）；
  - `DatabaseProbe` 在 Spring Boot `server` Profile 实机运行中报告 UP。

---

## 二、P1 阻塞项（数据库迁移过程中必须闭环）

### 2. openGauss 驱动对 JSONB 与复杂类型的兼容性实机确认 [RESOLVED / 已解决]
- **当前执行状态**：
  - [RESOLVED] 在目标 openGauss 6.0 实例上执行 `OpenGaussJsonbSpikeTest` 验证通过；
  - 确立方案 A（原生 `PGobject`）为官方推荐规范，方案 B（`Types.OTHER`）作为兼容备选；
  - 阻断解除，可直接编写 `JdbcFenceRepository`、`JdbcRuleRepository` 与 `JdbcAlertRepository`。

### 3. 测试环境分布式缓存服务（Kvrocks / Redis） [RESOLVED / 已解决]
- **当前执行状态**：
  - [RESOLVED] 已提取 `bpoc-kvrocks` 容器认证凭据，注入 `runtime/backend.env`；
  - TCP 6666 端口 `AUTH` + `PING` 验证回执 `PONG`；
  - Spring Boot `KvrocksProbe` 在 `server` Profile 启动时报告 UP。

---

## 三、P2 阻塞项（不阻塞数据库迁移，但正式上线前必须解决）

### 4. 现场真实硬件外设通讯协议（UWB / Radar / PLC / AI视频流）
- **为什么需要**：
  当前系统内所有人员坐标、雷达测距和 PLC 联动回执均为前端或后端定时器生成的模拟数据（Demo/Mock）。要实现真正的“工业级现场卡控”，必须有硬件适配器将设备真实报文转化为平台事件。
- **阻塞任务**：
  - Phase 2 真实设备适配层（Device Adapters）开发；
  - 现场 PLC 紧急停机切断动力联锁测试。
- **需要谁提供什么**：
  **现场硬件厂商 / 集成负责人** 提供：
  1. UWB 定位基站/手环的数据推送协议（网络端口、坐标系、报文格式）；
  2. 毫米波测距雷达与特种车辆之间的通讯接口手册；
  3. PLC 控制箱网络接口协议（如 Modbus TCP 寄存器地址表或 S7 协议说明）；
  4. 现场可用的硬件通讯测试模拟器或调试工具。

### 5. 统一统一身份认证（IAM）接入方案
- **为什么需要**：
  当前系统无统一登录，仅通过 `DemoUserProperties` 模拟固定安全员（李娜，`USR-001`）。正式部署前必须具备真实 RBAC 与组织身份上下文。
- **阻塞任务**：
  - 生产系统正式权限拦截；
  - 派单责任人向真实组织架构员工映射。
- **需要谁提供什么**：
  **底座平台组（IAM 模块）** 提供统一 SSO Token 格式、验签公钥及用户/角色查询 API 契约。

### 6. 时序数据库 openGemini 规划与接入规范
- **为什么需要**：
  高频人员定位点与雷达连续数据被明令禁止进入 openGauss 关系库，规划归属于 openGemini。
- **阻塞任务**：
  - 历史轨迹真实持久化存储与长周期查询；
  - 防碰撞连续测距波形回溯。
- **需要谁提供什么**：
  **云底座架构组** 明确 openGemini 的接入驱动依赖、测点表结构规约与数据保留策略（TTL）。

### 7. 服务器三节点部署环境参数
- **为什么需要**：
  `deploy/` 下的 Easegress 网关和 Nginx 反代配置文件包含 `__NODE4_BACKEND_HOST__` 等占位符，需替换为真实网络拓扑。
- **阻塞任务**：
  - `deploy/scripts/build-server.sh` 与 `verify-deployment.sh` 在真实集群环境的跑通。
- **需要谁提供什么**：
  **运维架构师** 明确 node4（后端）、node5（网关备份）、node6（前端静态托管）的内网 IP、公网反代域名与 SSL 证书。
