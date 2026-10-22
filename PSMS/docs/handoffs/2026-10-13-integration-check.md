# PSMS 逻辑删除、WriteLog、模拟采集联调记录

## 逻辑删除核查

2026-10-13 对服务器 PSMS 数据库只读核查：21 张表均存在 `valid` 字段；当前有效数据统计为 users 5、plans 5、work_orders 3、tasks 2、equipment 15、exceptions 2、interlocks 2、appointments 3、offline_packets 1、config_versions 1、audit_logs 5，所有子表也只返回 `valid=1` 数据。

后端 `Table` 访问层的列表、详情、计数、子表读取和报表默认追加 `valid=1`；明确传 `valid=0` 才能读取历史。删除、重置和替换均为 `UPDATE ... SET valid=0`，没有物理删除。

## WriteLog 和合约标准

PSMS 的标准适配器位于 `backend/src/services/writeLog.service.ts`：

1. 业务操作先在 `audit_logs` 保存前后快照、操作者、原因和 traceId。
2. `WRITELOG_URL` 配置后，按 JSON REST `POST` 投递 WriteLog，支持 `WRITELOG_API_KEY`。
3. 需要存证的白名单业务调用 `CONTRACT_API_URL`，请求包含 `operation: RegisterEvent` 与同一业务快照，支持 `CONTRACT_API_KEY`。
4. 回执写回 `writelog_state/writelog_id/chain_state/chain_tx_id/chain_error`；未配置或失败为 `PENDING`，不宣称成功。

当前白名单：计划确认/建议、任务拆解、工单派工/接单/开始/暂停/完成、异常确认/解决、联锁覆盖/复位、预约动作、配置发布、离线合并。HTTP 访问只进入 WriteLog，不上链。

项目没有提供真实 WriteLog 或合约地址，所以当前已验证数据库留痕、适配器请求体、白名单和失败状态；真实外部上链仍需配置接口地址与认证后联调。

## S1 模拟数据守护进程

接口前置汇总表确认 PSMS 属于 S1。当前守护进程 `backend/src/scripts/s1-simulator.ts` 使用约定字段：设备编号、点位编码、数值、质量、单位、源时间和来源序号；代表点来自 S1 设备采集点：EQ-IMG-01 的 LOAD/TROLLEY_POS、EQ-IMG-05 的 WEIGHT、EQ-IMG-06 的 LEVEL。

- `pnpm simulate:s1`：每秒批量写入 openGemini `equipment_telemetry`。
- `pnpm simulate:s1 --once`：写一批后退出。
- `deploy/psms-s1-simulator.service`：服务器 systemd 模板。
- UI-007 通过 `/api/monitor/operations` 读取最新点位并在“实时设备采集”卡片展示；模拟接口失败时保留原页面快照。

其他 app 的点位字段和采集周期不从 S1 清单推断，待对应清单确认后再增加适配器。

## 验证

- 后端 TypeScript 检查通过。
- 后端 25 项测试通过，包含逻辑删除、WriteLog/合约白名单、数据库状态回写和 AI 隔离。
- 前端 TypeScript 检查通过，模拟 fetch 备用处理及 AI 助手测试通过。

## 服务器联调

已发布 `20261013-1240`：

- PSMS 服务 active，openGauss/openGemini 健康检查通过，21 张表的有效性列保留。
- S1 模拟守护进程 `project-b-psms-simulator.service` active，每秒写入 4 个点；服务器查询已看到 EQ-IMG-01/05/06 最新值。
- 服务器浏览器已验证“实时设备采集”卡片显示已接收。
- 服务器浏览器已验证 AI 自动登录和 qwen3.8-27b。
- 通过一次无业务副作用的报表导出请求验证审计入库：`writelog_state=PENDING`、`chain_state=NOT_REQUIRED`；第二次同类记录也保持该语义。`WRITELOG_URL` 和 `CONTRACT_API_URL` 尚未配置，因此没有声称外部系统已接收或已上链。
- 后端 27 项测试通过，UI-007 2 项测试通过，前后端 TypeScript 检查通过。
