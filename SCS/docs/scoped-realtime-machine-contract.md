# SCS 范围实时通知与可信设备来源

此规范描述软件侧契约，不代表现场设备已经登记或完成协议联调。部署配置、实际账号、凭据和实验截图存放在私有运维目录。

## 浏览器实时通知

浏览器经正常 SSO 获取 HttpOnly 会话，再连接 `/ws/live`。`/api/v1/auth/me` 的 `scopedRealtime=true` 表示服务端已核对当前菜单 VIEW 与原生读取权限，前端才启用 V2 实时连接。客户端不能提供项目、区域或 SQL 条件来决定订阅范围。

每条业务事件按真实根对象的数据库归属解析受众；每个连接再次核对 IAM 当前会话、当前选择范围和精确菜单 VIEW。L1 的 GLOBAL 范围仍要求对应资源 VIEW；L2 使用本次会话已选择的获批范围；L3/L4 使用固定范围。只读用户可接收其获准读取的通知，通知不授予任何写操作。

V2 消息只发送 `{type,eventId,ts,data:{refresh:true}}`，不发送对象 ID、坐标、原始报文、SQL 或业务明细。页面收到后通过已有鉴权 HTTP 接口重新读取。撤销菜单 VIEW 后下一条事件不再投递；停用身份或会话撤销后连接关闭，周期扫描上限 5 秒，消息到达时即时复验。IAM 不可用时 V2 暂停消息投递，不使用旧授权缓存宽限。

无法查证归属、数据库异常、对象已删除、部分归属、混合旧数据与有归属数据均默认拒绝。只有明确查证全部三字段为 NULL 或明确 LEGACY/UNASSIGNED/SCS 的旧事件可走旧原生连接；禁止将“解析失败”当作旧数据。全局参考配置通知仅限人员配置与模型选用两类明确事件，并继续检查对应资源读取权限。

事件对菜单映射见 `ScopedLiveEventPolicy.views`；多对象事件需要当前选择范围覆盖所有对象。事件无对象 ID 时，仅可信单范围写入上下文可产生相应范围失效通知；旧后台没有范围上下文时不能回退广播。

## 可信机器来源

启动配置 `app.security.machine-bindings-file` 指向服务端受保护 JSON 文件。默认不配置来源。文件必须是绝对路径、普通文件、非符号链接，其他用户不得读写；建议 root 所有、服务组只读、0640，父目录 root 所有且不可由应用写入。真实设备映射必须由负责人核定后登记。

JSON 顶层是 `version:1` 和 `sources` 数组。每项使用以下字段：

| 字段 | 规则 |
|---|---|
| `id` | 唯一来源编码，字母、数字、下划线、短横线，长度 1–64 |
| `enabled`、`expiresAt` | 显式启用且未到期；禁用或改动下一次校验生效 |
| `keySha256` | 独立高熵来源口令的 SHA-256；口令和此摘要均不得进入源码、报告或截图 |
| `projectId/regionId/subsystemCode` | 唯一可信 tuple，字符白名单；SCS 固定；拒绝 LEGACY/UNASSIGNED |
| `routes` | 下列五条更新路由的明确子集 |
| `objects` | 根类型到对象 ID 数组的精确允许清单；同一对象不可同时属于两个已启用来源，即使 tuple 相同 |

请求使用 `X-SCS-Source-ID` 和独立 `X-API-Key`。命名来源错误、未登记、过期、停用或口令不匹配直接拒绝，不回退旧共享 API key。请求体若携带 `source`，必须与登记来源相同；任何层级出现客户端 tuple、`userContext` 或 `my_info` 均拒绝。

| 路由 | 批字段/对象 ID | 登记根类型 |
|---|---|---|
| `/api/v1/ingest/person-position` | `positions[].id` | PERSONNEL |
| `/api/v1/ingest/device-state` | `devices[].code` | COLLISION |
| `/api/v1/ingest/radar` | `readings[].pairDevice` | COLLISION |
| `/api/v1/ingest/zone-state` | `zones[].code` | AREA |
| `/api/v1/ingest/uwb-station` | `stations[].stationId`；登记 ID 为 `UWB:` 加原 ID | RUNTIME |

全批次预检允许清单、重复 ID、根对象存在与三属性完全一致，再进入服务。SQL 查询和原子 UPDATE 同时追加可信 tuple 与来源对象清单。缓存更新先在事务中锁定已登记根对象并复核归属；已登记区域可以初始化该根对象的运行缓存，不会新建区域根。未知或未归属对象不会被机器请求自动认领。

旧共享 key 继续用于旧无归属管道；它不能修改任何有归属或部分归属的数据库根对象，也不能覆盖同 ID 的有归属运行缓存。旧凭据不能访问浏览器或 WebSocket。

### 雷达派生告警的窄授权

雷达来源通常只登记上报设备（如 `VEH-07`），但按距离阈值需要创建/升级 `ALERT`。命名来源在当前请求内可持有“派生告警”授权：只对 `ALERT` 根、且只放行 `dedup_key LIKE 'COLLISION:<当前设备>:%'` 的告警，仍叠加来源 tuple 过滤；归属以已通过报文校验的 `readings[].pairDevice`（pair 的当前设备）为准，服务端固定配对的对端设备不需要出现在来源对象清单里，但无范围读取只允许无归属 legacy 行或来源自己的 tuple。首次接管时，旧共享 key/公共底座留下的无归属未关闭防碰撞告警会被认领到当前 tuple 后复用，避免重复建单。AI 事件仍按 CAMERA 对象授权，不在此列。

### 部署顺序与凭据隔离

新增路由/报文校验必须先部署后端，再修改 `machine-bindings-file` 增加对应 `routes`；旧后端会把含未知路由的整个绑定文件判为非法，导致同一文件里的其它来源（如 UWB 桥）一起 403。绑定文件保持 root 所有、服务组只读、0640，部署脚本应显式 `install -m 0640`，不要用默认 0644。

设备桥的 `SCS_API_KEY` 可能已被 UWB 命名来源复用，不能假设它就是旧共享 key。device-state/zone-state 使用独立的 `SCS_LEGACY_API_KEY`（未设置时才回退 `--api-key`）；雷达使用 `SCS_RADAR_SOURCE_ID` + `SCS_RADAR_API_KEY`。三条通道分别校验，缺哪条只影响对应通道，不再要求一个全局 `--api-key` 必须存在。

## 当前边界

五条路由仅更新已登记对象，不创建新业务根；雷达是例外：它不新建 `COLLISION` 根，但可派生/升级 `ALERT` 根，授权边界见上。AI 事件涉及新根派生，仍按 CAMERA 对象单独授权；后续需明确可信来源、对象所有权、幂等命名空间及新根归属策略。SIM 验证只能证明软件机制，不能替代现场设备、坐标系、传感准确性、广播或模型训练/部署验收。暂停的来源及测试身份应保留审计记录并结束启用。
