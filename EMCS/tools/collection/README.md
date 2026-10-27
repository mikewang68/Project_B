# EMCS 设备事件采集与模拟守护进程

负责人姜兆阳。支持六类设备：EQ-IMG-10除尘器、EQ-IMG-13高压离心风机、EQ-IMG-14库底卸料设备、EQ-IMG-15空压机、EQ-EXT-04皮带输送机、EQ-EXT-05密闭输送管道。

## 数据依据与边界

点位目录为 `backend-java/src/main/resources/collection/point-catalog.json`，来自交付的《B项目-接口前置清单全量汇总-v0.4-设备协议分工-20261023.xlsx》“设备采集点”工作表，保留原始行号、模块、点号、类型、单位、来源地址及工作簿SHA-256。共181条原始记录：S1关联77条、S2关联104条。同一设备的跨模块原点号均保留，不擅自合并成一条现场测点，也不生成不存在的S5点号。EHM-XL-01、EHM-CC-01为跨模块研究复用模板，不额外冒充现场设备实例。

**源表的厂家寄存器地址仍待确认。** 当前模拟器按标准事件生成实验工程数值，不是原厂PLC驱动，不包含Modbus写操作或设备控制。String/Enum/Bool/DateTime等缺依据字段保留null，数值仅作工程模拟并统一标UNCERTAIN；坏质量场景为null/BAD。单位和点号按原表保持，不把kW推导成kWh，也不以未知值代替0。

此前WMS包作为 `b.device-event.v1` 消息与回执约定参考，没有复制其源码、APK、点位或运行数据。本模块不自动执行收货、设备启停、故障确认等业务动作。

## 接入和展示

- `POST /collection/events`：直接提交UTF-8标准事件原文字节，须携带专用 `X-EMCS-Ingest-Token`。不是浏览器用户Token或SSO服务凭据。默认关闭、只允许明确登记的模拟设备；生产模式需另行授权登记及开启allow-live。
- 事件格式：schema_version、run_id、boot_id、device_id、正整数seq、拼接一致的event_id、simulated、replayed、source_ts_ms、observed_ts_ms、timestamp_origin、payload.points。source_ts_ms缺源时间时明确null，timestamp_origin为gateway_observation；有合法源时间时使用source。时间为毫秒整数，未来超过5分钟拒绝，历史补传保留原时间。
- 点位字段：point_code、value、unit、quality_code、provenance.equipment_code。支持GOOD/UNCERTAIN/BAD/STALE，GOOD不可null；原始值类型严格按目录验证。上限256KiB、256点，拒绝重复JSON字段、重复点号、未登记点、错误单位或来源。
- 成功返回HTTP200和 `{event_id,wire_sha256,duplicate,point_count,state:"STORED",scope:"EMCS_LOCAL"}`。事务提交成功后才响应。**STORED仅表示EMCS入库，不表示公共底座COMMITTED。**
- 同ID同原文字节重试返回duplicate=true，不新增记录；同ID异文409保留原记录。每个已登记设备以数据库行锁串行入库，整事件及点记录同事务。未知/停用设备403，非法数据422，超限413，未配置503。
- `GET /collection/events` 分页查询、`GET /collection/event?eventId=...` 详情、`GET /collection/catalog` 点位目录。原生角色限制为admin/energy_mgr/ops/dispatch/只读审计角色；dispatch仅访问AREA-A设备事件。SSO身份还须在私有routes登记上述GET及energy:collect:raw:view的中央/本地权限交集，未登记仍403。不向浏览器分发采集凭据。
- “原始数据与质量”页面顶部展示设备实例、观测时间、区域、模拟标记、原始点值、质量码与事件SHA，可手动刷新或选择5秒刷新；翻页与详情请求使用序号防止旧响应覆盖新选择。

这条链路存入独立事件台账和读数表，**不自动写入原演示e_raw_reading或重算能源成本**。原演示数据和日期保持原义；源点到能源计量点的正式映射、增量算法、计费口径须另行确认。

## 独立环境安装

1. 备份并在获准测试库执行 `sql/027_device_collection.sql`。该脚本新增设备登记、事件、读数三张表及六个SIM实例，不改原业务记录。只执行一次；本地H2回归不能代替真实openGauss验证。
2. 配置 `EMCS_COLLECTION_ENABLED=true`、独立随机且至少32字符的 `EMCS_COLLECTION_INGEST_TOKEN`，默认 `EMCS_COLLECTION_ALLOW_LIVE=false`。使用原生.env模板键或环境管理器，Spring不自动读取.env文件。真实值不进Git。
3. 默认SIM实例登记于AREA-A。实际设备必须先由负责人登记device_id、equipment_code、area_code、simulated、enabled；不能把SIM实例换为现场数据。更改历史设备区域会影响历史事件可见范围，应按受控数据归属迁移处理，不能随意更新。
4. 入口可为批准HTTPS网关（包含/emcs-api前缀）或仅本机隧道。工具拒绝明文非回环地址、URL内凭据与重定向，凭据只从文件读取。

从 `EMCS/tools/collection` 运行：

```bash
# 离线生成六类设备事件，只保存在本地队列
python collector.py --output runtime/demo --capture-only
# 向获准回环测试服务发送队列原文；令牌文件在仓库外安全管理
python collector.py --output runtime/demo --flush-only --emcs-url http://127.0.0.1:18103 --token-file /private/emcs-ingest.token
# 常驻模拟采集与发送，每5秒一轮（Ctrl+C/SIGTERM安全退出）
python collector.py --output runtime/demo --emcs-url http://127.0.0.1:18103 --token-file /private/emcs-ingest.token --interval 5
```

守护进程使用SQLite FULL同步本地outbox，网络失败及响应丢失保留原ID、原字节和摘要，重启后继续发送；每次启动boot_id独立，历史待发事件不重编号。默认最多10000条待发，接近上限暂停生成，先补发，不丢弃旧事件。每轮发送最多100条，失败至少10秒间隔；凭据错误也会保留队列，修正配置后恢复。已发送记录留存，需要运维规划归档和磁盘容量。

`--once` 为单轮采集；`--scenario bad-quality` 生成为空值BAD的异常事件。`--flush-only` 有未成功事件时退出码2，成功或capture-only为0。输出不包含凭据或原HTTP异常正文。

## 可选公共底座链路

公共底座真实入口与令牌必须由董博涵私下提供。可额外配置 `--base-url <批准入口/api/device-data/v1> --base-token-file <私有文件>`。先POST原始事件得到202，再按**同一个event_id**查询；只有COMMITTED、broker_message_id、wire_sha256及返回event与本地原文都一致后才转发EMCS。PENDING/SENT、错误摘要、事件不一致或超时均保留原文等待重试，不用latest轮询冒充可靠事件消费。

当前未提供公共底座环境，因此该模式只有模拟回执负例测试，未进行真实公共底座验收。直接EMCS模式可独立完成软件链路，不伪造公共底座回执。

## 自动验证

```bash
python -m unittest -v test_collector.py
# 后端目录，EMCS_PYTHON指向可运行Python的绝对路径
EMCS_PYTHON=/usr/bin/python3 mvn verify
```

后端新增测试覆盖服务身份与用户身份隔离、幂等、冲突、单位/类型、坏质量、未知/停用设备、模拟属性、时间范围、区域过滤及并发入库。配置EMCS_PYTHON后执行真实Python进程→回环HTTP Spring服务→H2数据库→应用查询接口的六设备181点闭环，不依赖外部网络；未设置时仅该跨进程用例明确跳过。

## 现场尚待确认

厂家及型号/固件、原厂点表和寄存器地址、量程/倍率/枚举、设备实例与区域、源时间可靠性、实际采样频率、只读采集网络及公共底座鉴权均需提供。满足这些条件后才能实现并验证对应真实协议驱动。当前没有原厂驱动、Android设备模拟APK或现场光学/电气采集验收，不以本软件模拟闭环代替这些结论。

回退：关闭EMCS_COLLECTION_ENABLED和模拟守护进程，恢复已验证前后端，保留新增兼容表及outbox数据，不通过删表或覆盖共享数据回退。
