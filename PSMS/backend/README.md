# PSMS 后端服务

PSMS（生产调度管理）模块的后端服务，提供 REST API 与 **MongoDB** 持久化。

- 技术栈：Express 5 + Mongoose 8 + MongoDB 7 + TypeScript 5 + Zod
- 默认端口：`3100`
- 数据存储：MongoDB（默认内嵌自动启动，数据持久化到 `backend/.data/mongodb`）

---

## 快速开始

```bash
cd PSMS/backend

# 1) 安装依赖（首次）
pnpm install

# 2) 准备环境变量（首次）
cp .env.example .env

# 3) 写入种子数据（首次）
pnpm seed

# 4) 启动服务
pnpm dev
```

首次启动时 `mongodb-memory-server` 会联网下载 mongod 二进制（约 60MB，
缓存在 `.cache/mongodb-binaries/`），**之后可完全离线运行**。

验证是否正常：

```bash
curl http://localhost:3100/api/health
```

---

## npm 脚本

| 命令 | 说明 |
|------|------|
| `pnpm dev` | 开发模式启动（tsx 热重载） |
| `pnpm build` | 编译 TypeScript 到 `dist/` |
| `pnpm start` | 以编译产物启动（需先 `pnpm build`） |
| `pnpm typecheck` | 仅做类型检查，不产出文件 |
| `pnpm seed` | 清空集合并重新写入固定种子数据 |
| `pnpm db:stats` | 连接自检：打印 mongod 版本、集合数与各集合文档量 |
| `pnpm smoke` | 端到端冒烟测试（需服务已在运行） |

---

## MongoDB 运行方式

由 `backend/.env` 的 `MONGODB_URI` 决定：

| `MONGODB_URI` | 行为 |
|---------------|------|
| 留空（默认） | **内嵌模式**：自动拉起一个真实的 `mongod` 进程，监听 `127.0.0.1:27017`，数据以 WiredTiger 引擎持久化到 `MONGODB_DATA_DIR` |
| 填写 URI | **外部模式**：连接该 MongoDB 实例，例如 `mongodb://127.0.0.1:27017/psms` |

两种模式都是真实的 MongoDB，使用相同的集合、索引与聚合能力。

### 内嵌模式的数据在哪

```
backend/.data/mongodb/           # WiredTiger 数据目录（真实库文件）
├── WiredTiger                   # 存储引擎元数据
├── WiredTiger.wt
├── _mdb_catalog.wt               # 集合目录
├── collection-*.wt               # 集合数据
├── index-*.wt                    # 索引数据
└── journal/                      # 预写日志（崩溃恢复用）
```

进程退出后数据保留；下次启动自动加载。
若要彻底重来，删除 `.data/mongodb/` 后重新 `pnpm seed` 即可。

### 用图形/命令行工具连

内嵌 mongod 监听固定端口，可直接用 MongoDB Compass 或 mongosh 连：

```
mongodb://127.0.0.1:27017/psms
```

---

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MONGODB_URI` | 空 | 空 = 内嵌 mongod；非空 = 外部实例 |
| `MONGODB_DB_NAME` | `psms` | 数据库名 |
| `MONGODB_EMBEDDED_PORT` | `27017` | 内嵌 mongod 监听端口 |
| `MONGODB_DATA_DIR` | `.data/mongodb` | 内嵌数据目录（相对 `backend/`） |
| `MONGOMS_DOWNLOAD_DIR` | `.cache/mongodb-binaries` | mongod 二进制缓存目录 |
| `MONGOMS_VERSION` | `7.0.14` | 内嵌 mongod 版本 |
| `AUTO_SEED` | `true` | 集合为空时自动灌入种子数据 |
| `JWT_SECRET` | — | 必填，至少 16 位；生产环境务必替换 |
| `JWT_EXPIRES_IN` | `8h` | 访问令牌有效期 |
| `JWT_REFRESH_EXPIRES_IN` | `7d` | 刷新令牌有效期 |
| `PORT` | `3100` | HTTP 监听端口 |
| `NODE_ENV` | `development` | 运行环境 |
| `CORS_ORIGIN` | `http://localhost:5173` | 允许的前端来源 |
| `LOG_LEVEL` | `info` | 日志级别 |

---

## 接口一览

统一响应信封与前端契约保持一致：成功 `{ ok: true, data, auditLogId?, traceId? }`，
失败 `{ ok: false, errorCode, message, details?, auditLogId, traceId }`。

| 方法 | 路径 | 说明 | 对应契约 |
|------|------|------|----------|
| GET | `/api/health` | 健康检查（含 MongoDB 连接状态与文档量） | — |
| POST | `/api/auth/login` | 登录换取令牌 | — |
| POST | `/api/auth/refresh` | 刷新访问令牌 | — |
| GET | `/api/auth/me` | 当前用户 | — |
| GET | `/api/plans` | 计划列表（过滤/排序/分页） | API-002 |
| GET | `/api/plans/:id` | 计划详情 | API-002 |
| POST | `/api/plans/:id/confirm` | 计划确认 | API-004 |
| POST | `/api/plans/:id/recommendation` | 接车窗口推荐 | API-005 |
| GET | `/api/plans/:id/tasks` | 计划下任务列表 | API-007 |
| POST | `/api/plans/:id/tasks` | 任务拆解（整体重建） | API-007 |
| GET | `/api/work-orders` | 工单列表 | API-008 |
| GET | `/api/work-orders/:id` | 工单详情 | API-008 |
| POST | `/api/work-orders/:id` | 工单命令 `assign/accept/start/pause/complete/cancel` | API-008/009 |
| GET | `/api/appointments` | 预约列表 | API-010 |
| GET | `/api/appointments/:id` | 预约详情 | API-010 |
| POST | `/api/appointments/:id` | 预约流转 `approve/checkin/call/enter/complete` | API-011 |
| GET | `/api/monitor/operations` | 监控快照（工单+设备+遥测） | API-012 |
| GET | `/api/exceptions` | 异常列表 | API-014 |
| GET | `/api/exceptions/:id` | 异常详情 | API-014 |
| POST | `/api/exceptions/:id` | 异常处置 `acknowledge/resolve/close` | API-015 |
| GET | `/api/interlocks` | 联锁列表 | API-016 |
| GET | `/api/interlocks/:id` | 联锁详情 | API-016 |
| POST | `/api/interlocks/:id` | 联锁处置 `trigger/override/approveOverride/reset` | API-017 |
| GET | `/api/offline/packets` | 离线包列表 | API-018 |
| GET | `/api/offline/packets/:id` | 离线包详情 | API-018 |
| POST | `/api/offline/packets/:id` | 离线包处置 `sync/resolve` | API-019 |
| GET | `/api/reports` | 统计报表（数据库侧聚合） | API-020 |
| POST | `/api/reports/export` | 报表受控导出 | API-021 |
| GET | `/api/settings` | 读取系统配置 | API-022 |
| POST | `/api/settings/:id` | 配置命令 `edit/submit/approve/publish/rollback` | API-023 |
| GET | `/api/audit-logs` | 审计日志查询 | API-024 |
| POST | `/api/audit-logs/export` | 审计日志受控导出 | API-024 |
| POST | `/api/demo/reset` | 演示数据重置（真正重灌种子） | API-025 |
| POST | `/api/demo/scenario` | 演示场景切换 | API-013 |

> 契约来源：`PSMS/docs/baseline/openapi.yaml` 与 `PSMS/src/contracts/`。
> 前端契约使用 `/mock/*` 前缀（MSW 拦截），后端使用 `/api/*` 前缀，
> 路径语义与 operationId 保持一致。

---

## 数据模型

11 个集合，全部使用**语义化字符串 `_id`**（如 `PLAN-001`、`WO-001`），
与前端契约的 `DO-xxx` 标识形式对齐。

| 集合 | 说明 | 文档数（种子） |
|------|------|----------------|
| `users` | 用户账号 | 5 |
| `equipments` | 设备台账 | 15 |
| `equipment_telemetry` | 设备遥测（**时序集合**） | 40 |
| `plans` | 到发计划 | 5 |
| `work_orders` | 作业工单 | 3 |
| `tasks` | 拆解任务 | 2 |
| `exceptions` | 生产异常 | 2 |
| `interlocks` | 安全联锁 | 2 |
| `appointments` | 公路预约 | 3 |
| `offline_packets` | PDA 离线包 | 1 |
| `configs` | 系统配置版本 | 1 |
| `audit_logs` | 审计日志（含 TTL 保留期） | 5 |

集合结构、索引、内嵌/引用取舍与约束说明见
[`docs/PSMS-数据库设计文档.md`](../docs/PSMS-数据库设计文档.md)。

---

## 演示账号

密码统一为 `password123`。

| 用户名 | 角色 | 数据域 |
|--------|------|--------|
| `admin` | super_admin | `*` |
| `scheduler` | scheduler | AREA-A, AREA-B |
| `dispatcher` | dispatcher | AREA-A |
| `operator` | operator | AREA-A |
| `viewer` | viewer | AREA-A, AREA-B |

登录示例：

```bash
TOKEN=$(curl -s -X POST http://localhost:3100/api/auth/login \
  -H "content-type: application/json" \
  -d '{"username":"admin","password":"password123"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

curl -s http://localhost:3100/api/plans -H "Authorization: Bearer $TOKEN"
```

---

## 目录结构

```
backend/
├── src/
│   ├── config/          # env 校验、MongoDB 连接（内嵌/外部）
│   ├── lib/             # 日志、HTTP 参数助手、ID 生成
│   ├── middleware/      # 鉴权、审计留痕、统一错误处理
│   ├── models/          # 11 个 Mongoose 模型 + 索引同步 + 集合管理
│   ├── services/        # 11 个业务服务（全部走 Mongoose）
│   ├── routes/          # 12 个路由
│   ├── seeds/           # 种子数据定义与 CLI
│   ├── scripts/         # 自检 / 冒烟测试工具
│   └── index.ts         # 服务入口（含优雅关闭）
├── .data/mongodb/       # 内嵌 MongoDB 数据目录（git 忽略）
├── .cache/              # mongod 二进制缓存（git 忽略）
├── .env / .env.example
├── package.json
└── tsconfig.json
```

---

## 已知差异与后续工作

1. **角色体系尚未对齐**：后端角色为 `super_admin / admin / scheduler / dispatcher /
   operator / viewer`，前端契约为 13 个大写 `RoleCode`（`DISPATCHER`、`SAFETY`、
   `AUDITOR` 等）。`middleware/auth.ts` 的权限判定目前是管理员粗粒度放行，
   未落到前端那套权限码（`plan:confirm`、`interlock:approve` 等）。
2. **字段命名尚未统一**：后端模型字段（`sourceStation`、`arriveTime`、`cargoItems`）
   与前端契约字段（`sourceSystem`、`arrivalDepartureTime`、`conflicts`）不同名，
   枚举取值也不一致。两套契约需要一次专门的对齐工作。
3. **前端尚未接入后端**：前端 `src/` 未引用 `/api/*`，`.env` 的
   `VITE_USE_BACKEND` 目前是死配置。接入需要改造 12 个业务域的 gateway 层。
4. **鉴权覆盖不完整**：只读接口使用 `optionalAuth`，未强制登录。
