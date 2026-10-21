# EHM本地日志查询 API（1.7.4）

基址 `/api/ehm/v1/system`。适用server profile，数据库为既有openGauss。所有接口均产生日志。调用方传已有IAM Bearer，由服务器配置的`/auth/me`在线校验；不是AUDIT机器Token，也不接受X-User-Id或URL角色授权。

| 方法与路径 | 功能 | 约束 |
|---|---|---|
| GET `/audit-session` | 返回当前可信身份、只读状态与本地查询范围 | 无会话在optional模式返回未验证；required模式401 |
| GET `/audit-events` | 查询实际数据库日志 | 已验证用户；默认本人，专项读取名单可看当前EHM作用域 |
| GET `/audit-events/{id}` | 日志元数据与SHA-256 | 事件必须在当前用户/实例作用域内，否则404 |
| GET `/audit-events/{id}/receipt` | 在线读取并核验公共AUDIT回执 | 在本地可见性校验后使用服务端专用READ凭据，不返回凭据 |

分页`page=0..10000`，`size=1..100`。可选精确筛选`action/outcome/delivery/userId/eventId/traceId`。`from/to`为ISO8601 UTC时间，筛选本地入队时间，不是客户端时钟；前端本轮未暴露日期控件。禁止用户指定app/domain/zone扩大范围。`delivery`只接受PENDING/SENDING/ACCEPTED/FAILED。响应包含items/total/page/size/scope/timeBasis，事件不返回请求正文、模型对话、密码或配置。

回执`lookupState=NOT_CONFIGURED/NOT_RECEIVED/UNAVAILABLE`不证明上链完成；`VERIFIED_PUBLIC_RECEIPT`表示公共回执通过本地匹配校验，`chainState`仍须区分NOT_CONFIGURED/ACCEPTED/COMMITTED等。COMMITTED另需非空交易编号；该代理不替代直接向Fabric验证交易。

新增配置：`EHM_AUDIT_QUERY_ACTORS`（稳定IAM用户ID，默认空），`EHM_AUDIT_READER_TOKEN_FILE`（服务端文件，READ范围匹配既有endpoint/domain/zone）。不可使用业务写Token代替只读Token。无新的日志修改/删除或任意重投API。

隔离验收可在既有获准schema内配置`EHM_OPENGAUSS_TABLE`、`EHM_AUDIT_OUTBOX_TABLE`、`EHM_AUDIT_TASK_TABLE`，默认仍分别为aggregate_store/audit_outbox/audit_task。新表须由拥有迁移权限的账户提前创建；仅配置表名不会自动复制/迁移历史数据。运行中不清理旧表、不更改日志原文。
