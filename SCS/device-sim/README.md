# SCS 设备原始协议模拟器（B01~B03 敬昌隆负责范围）

共 12 类模拟对象及协议替身：原有 8 类设备，加转运车辆、摄像头视频源、边缘节点/采集网关遥测、PLC 命令回执。后三项补齐现有系统的支撑角色或执行部件，不代表新增采购三类整机。完整字段、来源证据和更新方式见 [模拟设备类型与数据格式](docs/模拟设备类型与数据格式.md)。

| 设备 | 调研定位键 | 原始协议形态 | 参考设备（类似替代，未采购） | 端口 |
|---|---|---|---|---|
| UWB 人员定位终端与基站 | EQ-EXT-03 | **UDP JSON**（引擎→网关，1~5Hz+事件） | 沃旭 FB-LS1000 引擎 | 9000（发）/9001（下行注入） |
| 毫米波测距/激光雷达 | IF-IN-RADAR | **Modbus TCP**（FC03/04/06，10~20Hz 轮询） | Benewake TF03-180（寄存器同源） | 15020 |
| AI 视频分析边缘盒 | IF-IN-AI-EVENT | **HTTP Webhook POST + JPEG 快照** | DeepStream 事件 schema | 15030 |
| 现场一体化声光报警器 | IF-OUT-ALARM-LIGHT | **Modbus TCP**（FC03/06/16 灯位/蜂鸣/回执） | PATLATE LA6-POE（寄存器风格） | 15040 |
| **门式起重机（龙门吊）** | 组会指定 | **Modbus TCP**（FC03/06，状态/速度/方向/载重/起升/风速/联锁） | 通用门式起重机 PLC，**寄存器全部我方构造** | 15050 |
| **液压翻箱机** | 组会指定 | **Modbus TCP**（FC03/06，作业阶段/举升角度/液压压力/联锁） | 液压翻转机 PLC，**寄存器全部我方构造** | 15060 |
| 限位开关 | 装卸区 A | Modbus TCP（压合/计数/健康） | 项目构造 PLC 点表 | 15070 |
| 位置传感器 | 装卸区 B | Modbus TCP（mm 测距/占用/健康） | 项目构造点表 | 15080 |
| 转运车辆 | VEH-07 / VEH-08 | JSONL；可选 UDP JSON / SCS HTTP | 现有车辆台账及 device-state 契约，原始帧为项目构造 | 无默认网络；UDP 目标可配置 |
| 摄像头视频源 | CAM-01 等 | HTTP JPEG / MJPEG / health | 补充取图/断流替身，非 RTSP/ONVIF | 15100 |
| 边缘节点/采集网关遥测 | EDGE-01 等 | HTTP health JSON | 对照边缘节点模型合成，非完整边缘 Agent | 15110 |
| PLC 命令与回执 | PLC-01 等、绑定目标设备 | Modbus TCP（命令/提交/回执） | 项目构造点表，非厂家安全 PLC 协议 | 15120 |

全部**纯 Python 标准库零依赖**（AI 盒快照图有 PIL 时更精致，无则退化 1×1 JPEG）。

> **龙门吊与翻箱机没有实物、没有厂家手册**：资料卡 `docs/05`、`docs/06` 里的寄存器地址与换算
> 全部是我方构造的预案，**不得作为现场接线或 PLC 点表依据**；设备到位后必须按实际手册替换。

## 快速开始

```powershell
# 原有八类协议验证（会监听本地端口并写模拟寄存器；不覆盖新增四项或 SCS 端到端）
cd device-sim
python verify\verify_all.py

# 单独长跑（Ctrl+C 退出）
# UWB 每人一条独立路线，路线在 uwb/routes.json（形状/速度/起始偏移各自独立，改路线不用改代码）
python uwb\uwb_sim.py --dest 127.0.0.1:9000 --scenario patrol --rate 2
# 只跑某一个人（一人一进程）：--only WB-1003
python uwb\uwb_sim.py --dest 127.0.0.1:9000 --rate 2 --only WB-1003
python radar\radar_sim.py --port 15020 --scenario approach
python crane\crane_sim.py --port 15050 --scenario travel
python tipper\tipper_sim.py --port 15060 --scenario dump
python aibox\aibox_sim.py --webhook http://127.0.0.1:15031/hook --scenario mixed
python alarmlight\alarmlight_sim.py --port 15040
```

## 设备状态 → SCS 页面动态展示

`bridge/device_state_bridge.py` 是联调用的**网关替身**（正式网关属董博涵范围）：
轮询龙门吊/翻箱机 Modbus 从站 → 换算成 SCS 设备状态字段 → `POST /api/v1/ingest/device-state`（X-API-Key）；
同一进程还轮询**毫米波雷达**（15020）→ `POST /api/v1/ingest/radar`，把相对距离/相对速度/雷达健康喂进碰撞配对，
防碰撞页的距离与风险等级由它驱动。

```powershell
$env:SCS_API_KEY = "<机器凭据>"   # 不要写进脚本
python bridge\device_state_bridge.py --scs http://127.0.0.1:20986 --api-key-env SCS_API_KEY
python bridge\device_state_bridge.py --scs http://127.0.0.1:20986 --api-key-env SCS_API_KEY --once
```

SCS 侧只更新已登记设备（`CRANE-01` / `TIP-02` 等）的展示字段并广播 `collision.changed`，
防碰撞页已订阅该事件，**无需刷新自动更新**。未知设备编码会被忽略，不会新建设备。

## 状态上报与故障语义

`uwb/uwb_to_scs.py` 将基站帧按 `--station-interval`（默认 5s）单独调度，不依赖人员位置是否存在或人员上报周期。每个基站只保留最新待发帧；HTTP 200 后移除，失败可在 `--stale-seconds`（默认 10s）内重试，超过期限丢弃。新收到的同值帧仍可正常上报，`--dry-run` 也打印基站帧。

基站停报后，桥接不会反复发送已成功转发的旧帧。后端仍以最后一次接受报告的时间计算新鲜度，超过 10s 在查询时判离线；人员页按自己的 5s 周期读取，因此界面变化还受轮询时间影响。HTTP 请求仍串行执行，这不是源时间校验或严格的实时调度保证。

区域上报 `/api/v1/ingest/zone-state` 的 `personnelWorking`、`vehiclePresent`、`distanceMm` 采用以下规则：字段缺省保留旧值，显式 `null` 清空为未知，`false` 和 `0` 是有效读数。`signal` 的 null/空白仍保留原说明。限位开关故障发送 `personnelWorking: null`；位置传感器故障发送 `vehiclePresent: null` 和 `distanceMm: null`。页面显示“状态未知”，不继续显示旧人员、车辆或距离；后续有效读数可恢复显示。

区域 `online` 仍表示曾收到上报，不代表传感器无故障；本次没有增加区域停报自动过期机制。

在仓库根目录运行离线回归（无需启动模拟器、SCS 或联网），CI 也运行此测试：

```bash
python -I SCS/device-sim/verify/test_uwb_bridge.py
```

## 场景种子

| 模拟器 | 场景 | 语义 |
|---|---|---|
| UWB | `patrol` / `sos` / `degraded` / `backfill` | 4 人巡逻（含越界 FENCE-03 事件）/ SOS 事件 / 精度劣化+低电 / 断连补传（backfilled 标志） |
| 雷达 | `approach` / `recede` / `static` / `blind` / `fault` | 20→3m 接近（+0.5m/s）/ 远离 / 8m 恒定 / 超量程（强度<40→DEGRADED）/ RADAR_DOWN |
| 龙门吊 | `idle` / `hoist` / `travel` / `wind` / `fault` | 待机 / 起升作业（载重与起升高度往复）/ 大车往复行走（0~45 m/min，方向随半周期切换）/ 风速 12~18 m/s 超限 / 故障（DOWN + 联锁断开） |
| 翻箱机 | `idle` / `dump` / `blocked` / `fault` | 待机 / 完整作业循环（就位→举升→倾卸→回落，40s 一轮）/ 堵料卡阻（角度停滞 60°、压力≥28MPa、降级）/ 故障（角度保持半途） |
| AI 盒 | `helmet` / `vest` / `intrusion` / `mixed` | 三类违规事件 + 低置信度（0.62）+ 同 UUID 重发（幂等验证） |
| 声光 | 写寄存器即场景 | 黄(bit2)/橙(bit1)/红(bit0)+蜂鸣 pattern+时长；防抖窗口 500ms；回执区 0x1000+ |

## 与网关/底座的衔接（B04 及之后）

- 网关（董博涵）按各资料卡的连接参数读取本模拟器；转换后按 `b.device-event.v1` 提交底座。
- 雷达/声光无源时间戳（或无时间），`source_ts_ms=null` + `timestamp_origin=gateway_observation`。
- UWB/AI 盒事件带源时间；`simulated: true` 贯穿。
- 模拟设备实例编号建议：`SIM-UWB-0001` / `SIM-RADAR-0001` / `SIM-AIBOX-0001` / `SIM-ALARM-0001`。

## 边界声明（不可据此声称的事项）

1. 参考设备均为**类似设备替代**，现场厂家未定；资料卡中寄存器/字段分"原厂公开"与"我方构造扩展"两档标注。
2. UWB 的 JSON 字段名按 S3 清单口径构造（沃旭逐字段格式未公开）；手环下行帧为构造草案，**不是真实 BLE**。
3. 雷达 TCP 封装为联调便利（原厂为 RS485 RTU）；0x0002 起扩展寄存器为构造，选型后替换映射。
4. 声光寄存器布局为"LA6-POE 原厂风格 + 构造扩展（时长/防抖/回执区）"。
5. 本地验证通过 ≠ 端到端联调通过；共享联调入口（B05）启用后再做双端验收。

## 目录

```
device-sim/
  docs/01~04-资料卡.md     # 协议依据、点位映射、构造扩展声明（群内提交格式）
  common/modbus_tcp.py     # 极简 Modbus TCP 从站（FC03/04/06/16）
  uwb/uwb_sim.py           # UDP JSON 输出（标签 + 基站）+ 下行注入回执
  uwb/uwb_to_scs.py        # UDP 收帧 → 人员位置 /api/v1/ingest/person-position
                           #           → 基站状态 /api/v1/ingest/uwb-station（5s 一批）
  uwb/routes.json          # 每人独立路线（形状/速度/起始偏移）
  radar/radar_sim.py       # Modbus TCP 寄存器（距离/速度/健康/配对编码）
  aibox/aibox_sim.py       # Webhook 推送 + JPEG 快照服务
  alarmlight/alarmlight_sim.py  # 灯位/蜂鸣/时长/防抖 + 回执区
  limit_switch/limit_switch_sim.py  # 限位开关 → 区域人员作业状态
  position_sensor/position_sensor_sim.py  # 测距与占用 → 区域车辆状态
  vehicle/vehicle_sim.py   # 转运车辆 JSONL / 可选 UDP、SCS HTTP
  vehicle/profiles.json    # 两台车辆米制路线与速度配置
  camera/camera_sim.py     # JPEG/MJPEG/健康，非真实 RTSP
  edge_node/edge_node_sim.py  # 节点/网关合成遥测，非完整边缘 Agent
  plc/plc_sim.py           # Modbus 命令显式提交与回执
  docs/模拟设备类型与数据格式.md  # 十二类总表、字段、来源与更新方式
  docs/device-catalog.json # 机器可读类型及接入边界索引
  verify/verify_all.py     # 原有八类协议验证，不覆盖 SCS 端到端
  verify/test_*.py         # 可由 unittest discover 自动发现的回归测试
```
