# PSMS 后端服务

PSMS（生产调度管理）模块的后端服务，提供 REST API，持久化采用组内基线的**多存储分工**方案。

- 技术栈：Express 5 + PostgreSQL 协议驱动（pg 8）+ TypeScript 5 + Zod
- 业务库：**openGauss 6.0.5**（事务/业务数据）
- 时序库：**openGemini**（设备遥测）

> 存储分工的依据见 `EHMS/docs/EHM-architecture-baseline.md`：
> 资产、工单、权限等**事务数据进入 openGauss**；**遥测与趋势数据进入 openGemini**；
> 缓存与幂等状态进入 Kvrocks；异步事件进入 RocketMQ。

---

## 一、快速开始

### 1. 建立到集群的隧道

openGauss 与 openGemini 都只部署在服务器侧（SERVER-ONLY），开发机需要通过隧道访问：

```cmd
ssh -N -i C:\Users\Administrator\.ssh\bpoc_ed25519 ^
    -L 5432:192.168.101.57:5432 ^
    -L 8086:192.168.101.74:8086 lrz@100.65.200.125
```

> 跳板机 `100.65.200.125` 是 bpoc-node1 的 Tailscale 地址（走 DERP 中继，无直连）。
> **隧道必须保持开启**，后端启动时会先做连通性自检，不通就直接报错退出（不静默降级）。

### 2. 配置与启动

```cmd
cd D:\Project_B-main\PSMS\backend
copy .env.example .env      :: 按需修改；含 # 的口令必须写成 OPENGAUSS_PASSWORD="xxx#yyy"
pnpm db:init                :: 幂等建表 + 建时序库
pnpm seed                   :: 灌入固定种子数据（会先清空业务表）
pnpm dev                    :: 启动服务（默认 3100）
```

另一个窗口验证：

```cmd
pnpm db:stats               :: 两个存储的真实状态（表/行数/索引、measurement/点数）
pnpm smoke                  :: 端到端冒烟（20+ 断言，含直接查库校验）
```

---

## 二、目录结构

```
src/
├── config/env.ts            环境配置（OPENGAUSS_* / OPENGEMINI_*，zod 校验）
├── db/
│   ├── openGaussClient.ts   pg 连接池、事务、健康探针、错误码分类
│   ├── openGeminiClient.ts  InfluxDB 兼容 HTTP 客户端（自研，不引 SDK）
│   ├── lineProtocol.ts      行协议构造与转义
│   ├── schema.ts            21 张表的 DDL + 中文注释（幂等）
│   ├── migrate.ts           迁移执行器（批量优先、失败回退逐条定位）
│   ├── table.ts             轻量表访问层（参数化 WHERE、链式查询、子表读写）
│   ├── tables.ts            11 张表的访问器（字段映射 + 子表装配）
│   ├── telemetry.ts         设备遥测读写（走 openGemini）
│   ├── types.ts             领域对象类型（对外契约形态）
│   ├── inspect.ts           表行数、库概览、审计保留期
│   └── maintenance.ts       数据重置、审计保留期清理
├── middleware/              鉴权、审计留痕、统一错误处理
├── routes/                  12 个路由模块
├── services/                业务服务（状态机、命令流水线、审计）
├── seeds/                   种子数据与写入脚本
└── scripts/                 db-init / db-stats / smoke-test
```

---

## 三、数据模型

**21 张表 = 11 张主表 + 10 张子表**。原文档模型里的内嵌数组在关系库里规范化为子表，
对外接口仍以数组形式返回 —— 存储变了，契约没变。

| 主表 | 说明 | 拆出的子表 |
|---|---|---|
| `users` | 账号 | `user_data_scopes` |
| `plans` | 外部到发计划 | `plan_cargo_items` |
| `work_orders` | 作业工单 | `work_order_crew` |
| `tasks` | 任务拆解 | `task_crew`、`task_dependencies` |
| `equipment` | 设备台账 | — |
| `exceptions` | 生产异常 | `exception_evidence` |
| `interlocks` | 安全联锁 | `interlock_input_signals` |
| `appointments` | 公路预约叫号 | `appointment_documents` |
| `offline_packets` | PDA 离线包 | `offline_packet_conflict_fields` |
| `config_versions` | 系统配置版本 | `config_change_history` |
| `audit_logs` | 审计留痕 | — |

时序侧：openGemini 库 `psms_telemetry`，measurement `equipment_telemetry`，
tags = `equipment_id / point_code / quality`，fields = `value / unit / metadata`。

### 设计取舍

1. **主键用契约里的语义化字符串**（`PLAN-001`、`WO-001`），与前端 DO-xxx 契约、审计的 `objectId` 完全一致，便于排查。
2. **内嵌结构拆子表**，不用 jsonb —— 便于独立查询、聚合与约束。
3. **只有真无结构的字段才用 jsonb**：`supplements` / `specs` / `payload` / 审计的 `before_state`、`after_state`。
4. **枚举用 TEXT + CHECK**，不用数据库 enum 类型，避免增删取值要做类型迁移。
5. **外键只用在「内嵌子表 → 主表」**；跨实体引用（工单→计划、任务→工单）故意不加外键，
   避免删一张主表时级联清掉需要留档的历史单据，这类引用由应用层保证并建索引。
6. **审计保留期**：关系库没有 TTL，改为按 `audit_retention_days` 做 `DELETE`，
   由启动期与运维定时任务触发。生产环境建议再叠加按月分区 + `DROP PARTITION`。

---

## 四、常用命令

| 命令 | 作用 |
|---|---|
| `pnpm dev` | 开发模式启动（tsx watch） |
| `pnpm build` | 编译到 `dist/` |
| `pnpm start` | 运行编译产物 |
| `pnpm typecheck` | 类型检查（本项目以此为质量门槛） |
| `pnpm db:init` | 幂等建表 + 建时序库 |
| `pnpm db:stats` | 存储自检（`--tables` 看列清单，`--indexes` 看索引） |
| `pnpm seed` | 重置并灌入种子数据 |
| `pnpm smoke` | 端到端冒烟测试 |

---

## 五、健康检查

`GET /api/health` 同时返回两个存储的真实状态：

```json
{
  "ok": true,
  "openGauss": { "connected": true, "version": "(openGauss 6.0.5 ...)", "latencyMs": 42,
                 "pool": { "total": 3, "idle": 3, "waiting": 0 },
                 "tables": { "plans": 12, "work_orders": 20, "...": 0 } },
  "openGemini": { "enabled": true, "reachable": true, "database": "psms_telemetry",
                  "databaseExists": true, "latencyMs": 1180 }
}
```

**降级策略**：openGauss 连不通 → 启动失败（不做静默降级）；
openGemini 不可达 → 只告警，业务读写不受影响（与组内 SCS/EHMS 的探针降级一致）。

---

## 六、部署到集群内

生产部署时不需要隧道，把 `.env` 的地址改为集群内地址即可：

```env
OPENGAUSS_HOST=192.168.101.57      # 或 Easegress 网关
OPENGEMINI_URL=http://192.168.101.74:8086
OPENGAUSS_CONNECT_TIMEOUT_MS=8000   # 内网直连可收紧
```

建库脚本（一次性，需 DBA 用超管执行）：

```sql
CREATE USER psms WITH PASSWORD '<强口令>' CREATEDB NOCREATEROLE;
CREATE DATABASE psms WITH OWNER = psms ENCODING = 'UTF8' DBCOMPATIBILITY = 'PG';
GRANT ALL PRIVILEGES ON DATABASE psms TO psms;
```

> ⚠️ 注意：openGauss **禁止初始用户（omm）远程登录**，所以应用必须使用独立角色；
> 另外 openGauss 的 `CREATE USER` **不支持 `NOSUPERUSER` 选项**。
> 选 `DBCOMPATIBILITY = 'PG'` 是为了与本模块使用的 PostgreSQL 协议驱动语义严格对齐
> （组内 SCS 的 `b_project` 也是 PG 兼容模式）。

---

## 七、从 MongoDB 迁移过来的变更（留档）

本模块早期版本使用 MongoDB（文档模型）。2026-09-24 按用户决策改为
**openGauss（业务）+ openGemini（时序）**，MongoDB 已彻底移除：

- 删除 11 个 mongoose 模型与 `config/db.ts`；
- 卸载 `mongoose` / `mongodb` / `mongodb-memory-server` 依赖；
- 删除内嵌 mongod 数据目录与二进制缓存；
- 原「内嵌数组」全部规范化为子表；
- 原 MongoDB 聚合管道（`$match/$group/$sort`）改写为标准 SQL `GROUP BY`；
- 原「时序集合 + TTL 索引」分别改为 openGemini measurement 与按保留期 `DELETE`。
