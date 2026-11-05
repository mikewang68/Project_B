# 公共设备接口契约

接口前缀 `/api/device-data/v1`。除 `GET /health` 外均要求 `Authorization: Bearer ...`。浏览器 TRUST SSO、组织名称或请求正文中的身份不能替代本服务凭据。范围策略在启动时载入，变更策略需重新核对授权并重启服务。

| 接口 | 行为 |
|---|---|
| `POST /events` | JSON 原文最多 256 KiB，接收并持久保存事件；202 表示已受理，不是业务完成 |
| `GET /events?event_id=...` | 返回原事件、原文 SHA-256、状态及 Broker 标识；范围模式仅允许读取获授设备 |
| `GET /history?device_id=...&limit=...&cursor=...` | 按公共提交顺序读取 COMMITTED 事件，返回每项游标及 has_more；范围模式要求明确设备 |
| `POST /assets?device_id=...` | `application/octet-stream`，1 byte～1 MiB，服务计算并返回摘要；范围模式需资产开关和设备写权限 |
| `GET /assets?sha256=...&device_id=...` | 按设备所有权和读权限返回原始资产；共享摘要不能跨设备授权 |
| `GET /latest?device_id=...` | 查询获授权设备的最新已提交事件 |
| `GET /points/latest?device_id=...&point_code=...` | 查询获授权设备包含该点的最新已提交事件 |
| `GET /health` | 进程存活状态，不证明数据库/Broker或业务链路完成 |

事件须包含 `schema_version=b.device-event.v1`、`simulated=true`、`run_id`、`boot_id`、`device_id`、正整数 `seq`，以及 `event_id=run_id:boot_id:device_id:seq`。设备号以 `SIM-` 开头。`observed_ts_ms` 为正整数；`source_ts_ms` 必须存在，无来源时间时为 null，且 `timestamp_origin=gateway_observation`。

`payload.points` 含 1～256 个唯一点号。每点含 `point_code`、`value`、`unit`、`quality_code` 和非空对象 `provenance`；value 可为 null 或复合 JSON，unit 为字符串或 null，质量限 GOOD/UNCERTAIN/BAD/STALE。不把无值、低质量或对象值转成伪数值。

相同事件号重复提交时比较解析后的语义；异文冲突拒绝，服务保留首次原文及摘要。调用方重试仍应保留原事件号、原始字节和摘要，不能因超时或响应丢失另造事件。原文摘要和回执摘要不符时调用方应保留记录并停止确认。

状态先受理再由 Broker 消费事务变为 COMMITTED，回执包含 `event_id/state/wire_sha256/broker_message_id/accepted_ms/committed_ms`。COMMITTED 只表示公共库事务完成。授权、质量、资产和游标约束不能由 Broker ACK 代替。

history 按 `(committed_ms,event_id)` 排序，迟到事件按提交时间进入后续页。游标不透明，不能按设备时间自行推进；空页不清空旧游标。查询、校验、业务应用任一步失败，不提交该条的业务游标。不同消费者、设备或身份范围不得共用同一日志文件；旧队列/游标不自动迁移或换绑。

错误常见为 400 参数/事件错误、401 凭据无效、403 未授权、404 不可见或不存在、409 同号冲突、415 类型不符、503 依赖或拒绝日志不可用。保留失败记录；不要删除未提交事件来取得通过结果。
