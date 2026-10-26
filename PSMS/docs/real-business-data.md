# 业务数据读取适配

真实模式下，工单、车辆预约、全流程监控和异常页面读取 `/api/business/:collection`，保留业务库的字段和状态。演示模式原有页面不变。此适配提供列表、分页、状态和作业区筛选、详情及定时刷新。

| collection | 数据对象 | 读取权限 |
|---|---|---|
| work-orders | 作业工单 | pps:api:work-orders:read |
| tasks | 工单任务进度 | pps:api:monitor:read |
| equipment | 设备状态及采集时间 | pps:api:monitor:read |
| appointments | 车辆预约 | pps:api:appointments:read |
| exceptions | 异常记录 | pps:api:exceptions:read |

查询参数 `page`、`pageSize`（最大200）、`status`、`workArea`；工单与任务另支持 `planId`。每次查询以同一只读快照返回 `items`、`total`、`page`、`pageSize`、`generatedAt`。权限、过滤格式或数据库故障返回错误，不转换为成功的空集合。

工单、任务、设备和异常按当前业务用户的授权作业区过滤。跨区参数返回403；未指定作业区时取全部授权作业区。预约表尚无作业区归属，只允许明确的全局数据范围访问。不会因为具备管理员角色就绕过数据范围。

设备 `ONLINE` 是业务记录状态，采集时效依据 `last_heartbeat`、`last_telemetry_at` 与服务端生成时间判断。缺少采集时间或过期时明确提示，不推导设备当前健康。

返回字段不含司机证件、手机号及设备内部网络地址。040迁移只登记四个读取权限，不授予角色权限、不批准菜单发布，也不授权导出。

现有命令鉴权同时检查URL和请求体动作。工单 `start/accept/pause/complete/cancel` 要求执行权限，预约 `approve` 要求审批权限，不能以普通写入权限调用请求体中的执行或审批动作。

## 受控业务操作

`POST /api/business/work-orders` 根据授权范围内的计划创建草稿工单，接收 `planId`、`orderType`、`priority`、`description`。作业区与计划批次由服务端读取，不接受浏览器指定；创建本身不确认计划、不派工或执行设备。

`POST /api/business/:collection/:id/actions` 请求包含 `action`、最近查询返回的 `record_version` 作为 `expectedVersion`、必填 `reason`；分配设备另含 `equipmentId`。动作权限按表中资源的 `write`、`approve` 或 `execute` 独立判定，且服从本地角色、数据范围与只读限制。任务和设备本身不开放这个写入接口。

- 工单：分配设备、接单、开始、暂停、完成、取消。派工和开始前检查计划已确认、设备同区在线且有及时采集、没有触发中的相关联锁。未完成关联任务时禁止直接完成工单。
- 预约：待审→审核通过→签到排队→叫号→入场→完成离场。签到分配队列号时锁定预约表，禁止跳过当前业务状态。
- 异常：认领→提交解决结果→关闭，处理原因及执行人写入业务记录和审计。

所有操作先锁定授权范围内的对象，核对精确版本，再检查状态。业务更新与审计在同一事务提交；旧版本返回409，不自动重试。响应包含对象新状态、新版本、`auditLogId` 和 `traceId`。未取得响应时先查询记录与审计，不自动重复提交。

统一登录模式下，原 `/work-orders/:id`、`/appointments/:id`、`/exceptions/:id` POST 也走同一事务实现，必须携带上述版本和原因，避免旧接口绕过状态、范围或审计检查。非统一登录开发模式保留原接口行为。

此流程更新调度业务记录，不代表已经向现场设备发出控制指令。消息接收、现场执行回执、AI建议人工确认和完整任务执行链路仍需分别接入并核验。
