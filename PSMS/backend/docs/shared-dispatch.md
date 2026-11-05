# 调度计划、工单共享读取契约

该入口提供已登记的跨系统只读资源，不改变用户的业务归属或服务受众。消费方仍以自己的 WMS、EHMS 或 SCS 身份登录，不申请 PSMS 的普通业务账号。

## 资源与动作

| 资源键 | 提供方接口 | 消费方业务菜单 |
|---|---|---|
| `PSMS:shared:plans` | `GET /api/shared/dispatch/plans[/{id}]` | WMS 入库、EHMS 工单、SCS 规则 |
| `PSMS:shared:work-orders` | `GET /api/shared/dispatch/work-orders[/{id}]` | 同上 |

消费方菜单固定为 `WMS:stockin`、`EHMS:workorders`、`SCS:rules`，动作固定为 `VIEW`。共享授权与菜单动作授权分别管理。只有 IAM 中的资源登记、项目/区域/系统上级授权、本人岗位动作和显式共享 `VIEW` 同时有效时，才能读取相应项目、区域。

本入口以区域为共享边界，包含该区域内多个 PSMS 作业区；它不将 PSMS 原生 `AREA-*`、WMS 仓库或 EHMS 业务区域解释成中央区域。消费系统自身的业务操作继续执行原生范围检查。共享结果不能直接作为修改其他系统记录的授权依据。

## 调用与可信边界

消费系统后端从已验证的本地 SSO 绑定读取主体和 IAM 会话，调用提供方时携带：

- `X-Shared-Audience`：自己的服务受众。
- `X-Shared-Subject`、`X-Shared-Session-Id`：本地已验证绑定中的值。
- `X-SSO-Service-Token`：该消费系统的后端服务凭据。

浏览器仅调用本系统的 `/shared-dispatch/plans` 或 `/shared-dispatch/work-orders`；不可向浏览器提供服务凭据。消费后端忽略浏览器自带的上述身份头，始终使用自己的配置和会话绑定。不同系统的接口前缀分别沿用 `/api/v1` 或 `/api/ehm/v1`。

PSMS 对每次读取调用 IAM `/auth/sso/resource-filter`。IAM 验证消费服务凭据和当前会话，再计算共享授权；PSMS 使用 SDK 验证的参数化条件，固定数据所有者为 `subsystem_code='PSMS'`。客户端不能指定资源表、菜单动作、SQL、列名或数据范围。无共享授权返回空列表，越域详情返回 404；身份或菜单动作失效返回 401/403，IAM 不可用时拒绝读取。

只支持 `page`（1–10000）和 `pageSize`（1–100）；详情编号仅允许字母、数字、下划线和连字符。列表、总数和详情使用同一范围条件；列表和总数在同一只读可重复读事务中查询。非 GET/HEAD 请求拒绝，状态回写须另行设计业务命令。

## 数据最小化

计划提供编号、批次、车次、货物类型、到达时间、作业区、状态、优先级、归属和更新时间。工单提供编号、计划关联、作业区、设备、状态、类型、优先级、归属和更新时间。

供应商联系方式、电话、补充 JSON、人员分配、班组、指令及反馈等字段不进入共享结果。历史未归属记录和其他来源系统的行不伪装成 PSMS 当前区域记录。

## 配置

PSMS 显式设置 `PSMS_SHARED_READ_ENABLED=true`；对应消费系统设置 `wms.shared-dispatch.enabled`、`ehm.shared-dispatch.enabled` 或 `app.shared-dispatch.enabled`，以及同前缀的 `base-url`。默认均关闭。

提供方地址须使用 HTTPS；独立实验室的回环 HTTP 需显式开启 `allow-loopback-http`。PSMS 调用 IAM 的回环 HTTP 另由 `PSMS_SHARED_ALLOW_LOOPBACK_HTTP` 控制。客户端拒绝带用户名、查询参数、片段或其他路径的基地址，不跟随重定向。真实地址和服务凭据放在受保护的部署配置中。

响应 `data` 包含 `sourceApp`、`resourceKey`、`total`、`page`、`pageSize` 和 `items`。各消费系统保留自己的外层响应约定。共享读取的候选验收与正式启用分别记录。
