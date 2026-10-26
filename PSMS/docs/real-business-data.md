# 业务数据读取适配

真实模式下，工单、车辆预约、全流程监控和异常页面读取 `/api/business/:collection`，保留业务库的字段和状态。演示模式原有页面不变。此适配提供列表、分页、状态和作业区筛选、详情及定时刷新；业务写入流程须分别接入与验证。

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
