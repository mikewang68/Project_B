# SCS 模拟设备类型、数据格式、来源与更新方式

版本日期：2026-11-05。源码基准：`39550d8d5cd1c50125947655bb9d65c001e54409`（本地 `_gh_work` 的 main），并包含 2026-11-05 尚未提交的 UWB/区域状态修复及四项设备模拟补齐；这些修复尚未部署到服务器。

本文面向模拟接入、接口联调与交接，按当前代码说明“是什么设备、生成什么数据、如何进入 SCS、多久更新、从哪里修改”。它是源码级契约说明，不是现场运行设备清单。所有端口、编号和报文示例均为代码默认值或虚构联调值；不包含现场账号、密钥或机器来源登记内容。

配套文件：[设备报文示例.json](设备报文示例.json)（18组可解析数据，含区域故障及新增四项模拟示例）及[前端与边缘AI核查记录](前端与边缘AI核查记录.md)（页面来源、刷新与已确认问题）。

## 阅读约定

- 路径以 `_gh_work/SCS/` 为根；附录给出主要实现位置。
- “原始帧/寄存器”是模拟器对外协议；“SCS JSON”是桥接器转换后请求后端的格式。两者不能直接替代。
- “默认周期”不等于实时性保证，网络、轮询、推理和页面拉取都会增加延迟。
- “可缺省”描述当前程序实际行为，不表示生产数据允许缺失；推荐字段完整上报。
- “模拟场景”由脚本公式、随机数、模板或固定台账产生，不能当作真实传感器精度、模型准确率或 PLC 执行证明。
- 修改模拟器周期、桥接上报周期、前端刷新周期是三个不同操作，互不自动联动。

## 1. 设备与链路总览

当前有 **12 类可运行模拟对象**：原有8类，加转运车辆、摄像头视频源、边缘节点/网关遥测和PLC命令回执。车辆是新增业务设备生成器，摄像头/边缘是支撑角色，PLC是机械执行部件；不代表新增采购四类独立整机。UWB 包含标签、基站及简化手环 ACK；同一脚本可提供多个实例，台账数量不能等同于模拟器进程数量。

| 设备类型 | 脚本 | 原始协议及代码默认端口 | 默认更新 | 当前 SCS 链路 |
|---|---|---|---|---|
| UWB 标签/基站 | `device-sim/uwb/uwb_sim.py` | UDP JSON 发送到 `127.0.0.1:9000`；下行监听 `0.0.0.0:9001` | 标签 1Hz；基站在主循环约每秒检查发送 | 独立 `uwb_to_scs.py` → person-position / uwb-station |
| 测距雷达模拟 | `device-sim/radar/radar_sim.py` | Modbus TCP `15020` | 内部 20Hz | device_state_bridge → radar |
| AI 视频分析盒模拟 | `device-sim/aibox/aibox_sim.py` | HTTP `15030` + 主动 webhook | 有 webhook 才默认 1s/条，共 6 条 | 有原始 webhook；没有匹配当前 SCS DTO 的专用适配器 |
| 声光报警器 | `device-sim/alarmlight/alarmlight_sim.py` | Modbus TCP `15040` | 写入触发，倒计时 0.2s 更新 | 没有 SCS 控制/回执反向桥接 |
| 门式起重机/龙门吊 | `device-sim/crane/crane_sim.py` | Modbus TCP `15050` | 内部 20Hz | device_state_bridge → device-state，固定 CRANE-01 |
| 液压翻箱机 | `device-sim/tipper/tipper_sim.py` | Modbus TCP `15060` | 内部 20Hz | device_state_bridge → device-state，固定 TIP-02 |
| 限位开关 | `device-sim/limit_switch/limit_switch_sim.py` | Modbus TCP `15070` | 内部 20Hz | device_state_bridge → zone-state，固定 LOADING-A |
| 位置传感器 | `device-sim/position_sensor/position_sensor_sim.py` | Modbus TCP `15080` | 内部 20Hz | device_state_bridge → zone-state，固定 LOADING-B |
| 转运车辆 | `device-sim/vehicle/vehicle_sim.py` | JSONL；显式选择UDP/HTTP | 默认1s一批 | 可选直传device-state，默认VEH-07/08 |
| 摄像头视频源 | `device-sim/camera/camera_sim.py` | HTTP `15100` health/JPEG/MJPEG | 按请求；默认30帧2fps | 独立取图/断流替身；无SCS健康ingest |
| 边缘节点/网关遥测 | `device-sim/edge_node/edge_node_sim.py` | HTTP `15110` health | 请求时按单调时间计算 | 独立遥测替身；无外部心跳ingest，不执行自治/重放 |
| PLC命令回执 | `device-sim/plc/plc_sim.py` | Modbus TCP `15120` | 命令显式提交时更新 | 执行部件协议替身；无SCS控制/回执bridge |

六类 Modbus 模拟器默认绑定 `0.0.0.0`，unit 默认 1。主桥接默认约 2s 轮询一轮，只读取雷达、门机、翻箱机、限位、位置五类；UWB 使用独立桥接。外部设备的状态上报、网页按钮触发的后端演示和真实执行器控制应分开验收。

简化链路：`设备脚本 → UDP/Modbus/HTTP → 桥接/适配 → SCS ingest → 仓储/业务规则 → WebSocket 通知 → 页面 REST 重拉`。AI 盒与声光目前在上述链路中存在明确缺口，新增摄像头/边缘/PLC也有明确接入边界，不能表述为十二类已全部端到端接通。

## 2. 公共协议及单位约定

### 2.1 Modbus TCP

- 寄存器表中的地址均为 **零基十六进制地址**，例如 `0x0100`；不是 40001 风格展示编号。
- MBAP：`transactionId:uint16 | protocolId:uint16 | length:uint16 | unitId:uint8`，接 PDU；length 包含 unit 与 PDU，整数字节为大端。TCP 报文没有 RTU CRC。
- FC03 读 holding，FC04 读 input；读数量 1～125。六类设备当前只填充 holding，因此 FC04 的 input 区仍为全零，不能把 FC04 当成同值别名。
- FC06 写单字；FC16（十进制 16，功能码 `0x10`）写多个 holding，数量 1～123。每个寄存器 16 位；雷达速度需按有符号补码解码，其他表项为 uint16。
- 常规异常响应为 `功能码|0x80` 加异常码：1 非法功能，2 地址/部分数量错误，3 数据值或格式错误。
- unit 匹配自身或 `0xFF` 时应答。桥接默认 unit=1，当前 CLI 没有同步修改 unit 的参数。
- 当前底层没有业务寄存器只读白名单，未定义地址也可能返回零；“读到了零”不能证明型号有该传感能力。声光回执区目前也可被写入。
- 原始寄存器没有采集时间戳；模拟器 tick 的运行秒数不是线上寄存器字段。

例如读取 unit=1 的 0x0000 起三个 holding，事务号 1：

```text
请求：00 01 00 00 00 06 01 03 00 00 00 03
若寄存器为 800、300、50，应答：
      00 01 00 00 00 09 01 03 06 03 20 01 2C 00 32
```

这里 800 对雷达表示 8.00m，50 对其速度表示 +0.50m/s；同一数值放在另一设备寄存器中可能代表完全不同物理量。

### 2.2 JSON、时间与坐标

- UDP/HTTP JSON 的数值是 JSON number，不是已实现的 float32 二进制帧。
- UWB 和 AI 盒原始 `ts` 为带 `+08:00` 的 ISO-8601 字符串，毫秒精度；不是 Unix 毫秒整数。
- UWB 原始 x_m/y_m/z_m 用米；UWB 桥接 x/y 为站场百分比。默认 100m×100m 只是联调假设，`routes.json` 的 site 尺寸不会自动传给桥接。
- 门机速度寄存器使用 0.1m/min，桥接转为 km/h；雷达速度为 cm/s，桥接转 m/s；位置传感器距离保持 mm。
- AI 盒原始 objectConfidence 为 0～1；SCS AI confidence 和 edge-ai 输出框通常为百分数。不能直接复制。
- 统一事件概念中的 `source_ts_ms`、`timestamp_origin`、`simulated` 并没有完整出现在现有两份桥接的请求中；以本文实际专用 envelope 为准。

## 3. 十二类模拟对象的原始数据与桥接格式

### 3.1 UWB 标签、基站与手环下行

脚本：`device-sim/uwb/uwb_sim.py`。默认读取同目录 `routes.json`，当前文件有 8 人（WB-1001～1008 / P-1001～1008）；只有默认路线文件不存在且未另指定时，才回退脚本内置 4 人。基站固定 BS-01/02/03。

标签原始报文：

```json
{
  "type": "tag",
  "seq": 1,
  "ts": "2026-11-05T12:00:00.000+08:00",
  "tagId": "WB-1001",
  "personId": "P-1001",
  "x_m": 25.0,
  "y_m": 40.0,
  "z_m": 1.2,
  "accuracy_m": 0.18,
  "battery_pct": 90,
  "sos": false,
  "tamper": false,
  "station_ids": ["BS-01", "BS-02", "BS-03"],
  "backfilled": false
}
```

| 字段 | 类型/单位 | 当前生成行为 |
|---|---|---|
| type / seq / ts | string / integer / ISO string | tag、station、event 共用递增 seq，进程重启归零；ACK 不带 seq |
| tagId / personId | string | 路线中配置，桥接优先用显式 tag→person 映射，否则取 personId；必须对应 SCS 已登记人员 |
| x_m / y_m | number，m | 沿闭合路线运动，保留三位小数；生成器不强制限定在站场边界 |
| z_m | number，m | 固定 1.2 |
| accuracy_m | number，m | 正常 0.18；degraded 0.62 |
| battery_pct | integer，% | 取路线 battery；载入器没有完整 0～100 校验 |
| sos / tamper | boolean | 正常 false；SOS 场景约 5s 后固定标签置 sos=true；tamper 没有对应事件生成实现 |
| station_ids | string[] | 固定三基站 |
| backfilled | boolean | backfill 场景周期性置位；没有真实缓存历史位置重放 |

上述字段由生成器输出；不是厂家原生 UWB 数据协议。站场地图坐标变换由桥接负责。

基站原始报文：

```json
{
  "type": "station",
  "seq": 9,
  "ts": "2026-11-05T12:00:00.000+08:00",
  "stationId": "BS-01",
  "online": true,
  "clockSynced": true,
  "snrDb": 32,
  "powerOk": true,
  "tagCount": 8
}
```

stationId 为 string，online/clockSynced/powerOk 为 boolean，snrDb 为 dB 数值，tagCount 为整数。三基站 SNR 固定 32/29/34，标签数取配置人员总数，不保证等于实际收到的标签数。

事件帧公共字段为 type="event"、seq、ts、tagId、event、value=true；event 实际为 sos、low_battery、geofence_entry，只有 geofence_entry 追加 fenceId。桥接当前只打印事件，不把 SOS 或围栏事件转为 SCS 告警。

手环下行监听接受任意 UDP 字节，返回 `{"type":"wristband_ack","ts":"...","ok":true}`。它没有真正解析/校验资料卡中的二进制命令、CRC 或标签目标，也不改变手环状态；不能作为真实下行闭环证据。

桥接 `device-sim/uwb/uwb_to_scs.py`：

- x=`x_m/site_width×100`，y=`y_m/site_height×100`，保留一位；站场尺寸由桥接参数提供，默认各 100m。
- battery_pct→battery，accuracy_m→accuracy，personId/映射结果→id；按人员 id 留最新帧，多标签映射同一人会覆盖。
- 不转发 tagId、z、sos、tamper、station_ids、源 ts、seq、backfilled。
- 默认位置批次 1s，最小 0.2s；超 10s 的人员帧从收集器删除，但不发送离线通知。
- 基站按 stationId 只保留最新待发帧，默认 5s 独立调度；成功收到 HTTP 200 后移除，不重发旧缓存。失败仅在本地 stale-seconds（默认 10s）内重试，过期删除。只有基站帧或人员周期很长时仍可上报；dry-run 同时打印基站帧。HTTP 请求仍串行执行，不保证严格实时性，也未增加源时间校验。
- 两种 SCS envelope 见第 4 节。

路线文件使用 people 数组：tagId、personId、battery、path（至少两个二维点）、speed_mps、phase_m。path 首尾自动闭合；speed_mps 默认 1.0m/s、下限 0.1；phase_m 为沿路线的米制相位。修改路线或 CLI 需重启；`--rate` 实际限制在 0.2～5Hz。

### 3.2 测距雷达

脚本：`device-sim/radar/radar_sim.py`，默认 approach。虽然目录称毫米波雷达，资料卡参考的是 TF03 激光测距；当前只能视为测距协议模拟，不能据此确定现场毫米波设备型号。

| holding 地址 | 类型 | 换算/含义 |
|---|---|---|
| 0x0000 | uint16 | 距离 cm，÷100 得 m；approach/recede 约 3～20m，static=8m，blind=181m，fault=0 |
| 0x0001 | uint16 | 强度原始值，当前约 300/280/320/35/0，无已标定物理单位 |
| 0x0002 | int16 | cm/s；÷100 得 m/s，正接近；当前场景 +50、−40 或 0 |
| 0x0003 | uint16 | 0 NORMAL、1 DEGRADED、2 DOWN |
| 0x0010～0x0013 | 4×uint16 | 8 字节大端 ASCII、尾零填充；固定配对键 VEH-07，不是雷达 ID |
| 0x0100 | uint16 | 0 approach / 1 recede / 2 static / 3 blind / 4 fault |

approach 约 34s 锯齿循环 20→3m，recede 约 42.5s 循环 3→20m；速度字段是场景设定值，不是完整轨迹差分测速实现。切换场景不重置运行相位。

主桥接转为 readings：pairDevice 从 ASCII 读取；distance=cm÷100；relSpeed=int16÷100；radarQuality=强度÷5 四舍五入并钳制 0～100；health 用英文枚举。不发送雷达 ID 或原始强度，blind/fault 仍可能提交 181m/0m，必须结合 health。

默认 VEH-07 配 TIP-02，VEH-08 才配 CRANE-01；一条默认雷达流不会同时更新全部设备配对。

### 3.3 AI 视频分析盒协议模拟

脚本：`device-sim/aibox/aibox_sim.py`。默认 HTTP 15030；`--webhook` 为空时只提供 HTTP 健康/快照，不主动产生推送事件。有 webhook 才默认每 1s 发 6 条；默认发完退出，`--hold` 可延长存活。

```json
{
  "@schema": "nvidia/deepstream/eventmsg/v1",
  "eventUuid": "00000000-0000-4000-8000-000000000001",
  "eventType": "entry",
  "objectType": "person",
  "objectConfidence": 0.94,
  "cameraCode": "CAM-01",
  "violationType": "NO-HELMET",
  "snapshotUrl": "http://127.0.0.1:15030/snapshots/00000000-0000-4000-8000-000000000001.jpg",
  "zoneId": "FENCE-03",
  "ts": "2026-11-05T12:00:00.000+08:00",
  "simulated": true
}
```

| 字段 | 类型/语义 |
|---|---|
| @schema | string，模拟器声明，不表示通过 NVIDIA 原生 schema 认证 |
| eventUuid | string UUID4，重复分支复用上一事件 UUID |
| eventType / objectType | string，固定 entry / person |
| objectConfidence | number，0～1 语义；场景值 0.94/0.88/0.91/0.62 |
| cameraCode | string，CAM-01～04 按序轮转 |
| violationType | string，NO-HELMET / NO-VEST / ZONE-INTRUSION |
| snapshotUrl | string，固定构造回环地址；跨主机消费者通常无法直接使用 |
| zoneId | string，固定 FENCE-03，需另映射为业务区域，不应直接认定为 areaCode |
| ts | ISO string，+08:00 毫秒精度 |
| simulated | boolean，固定 true；SCS DTO 不自动保留此字段 |
| replayed | boolean，仅重复分支追加 true |

快照在进程内字典，有 PIL 时合成 320×240 JPEG，否则用 1×1 常量 JPEG；退出后不可视为持久证据。GET `/health` 返回 UP 与 PPE-Detection-sim；无 RTSP、真实推理、MQTT 或控制接口。

该原始 JSON **不能原样替代 SCS AI ingest**：需 violationType→业务 eventType、objectConfidence×100→confidence、ts→occurredAt、zoneId→已登记区域，并提供鉴权/幂等头及可达截图地址。eventUuid 应映射到幂等契约；只把它放在 JSON 中不等于自动去重。目录内目前没有这层专用桥接。

场景 CLI：helmet/vest/intrusion/mixed，默认 mixed；`--count=6 --interval=1 --duplicate-at=3 --hold=0`。duplicate-at 为零基索引，因此默认第四条替代原本的 intrusion，默认六条不保证覆盖三类违规；验证器另用 duplicate-at=5。

### 3.4 声光报警器

脚本：`device-sim/alarmlight/alarmlight_sim.py`。没有设备 ID 参数，unit 不是业务 ID。

| holding 地址 | 类型 | 含义 |
|---|---|---|
| 0x0000 | uint16 | 灯位图 0～63；bit0 红、bit1 橙、bit2 黄；active_color 优先红＞橙＞黄；bit3～5 可写但无行为 |
| 0x0001 | uint16 | 蜂鸣 pattern 0～11，仅存值 |
| 0x0002 | uint16 | 持续秒数，0 无限；软件校验写到 86400，但单字实际最大 65535 |
| 0x0003 | uint16 | 防抖 ms，默认 500；同样受单字上限限制 |
| 0x0010 | uint16 | 灯 pattern 0～11，仅存值 |
| 0x1000 | uint16 | 当前色 0 无 / 1 黄 / 2 橙 / 3 红 |
| 0x1001 | uint16 | 剩余秒；无限持续时也为 0，不能仅据 0 判断空闲 |
| 0x1002 | uint16 | 有效触发序号，16 位输出 |
| 0x1003 | uint16 | 防抖合并计数，16 位输出 |

建议一次 FC16 从 0x0000 写灯、蜂鸣、时长、防抖。只有写起址 0x0000 才触发颜色/防抖；只改时长不重算既有到期时间。写灯 0 或超时熄灯不等于蜂鸣寄存器也归零。底层没有回执只读保护；灯 pattern、蜂鸣寄存器不等于真实声响/闪烁模型。

当前没有 SCS→该 Modbus 模拟器的控制桥接，也没有把这些回执读回 SCS；verify 脚本直接写灯不能作为 SCS 联动成功证明。

### 3.5 门式起重机/龙门吊

脚本：`device-sim/crane/crane_sim.py`，默认 travel；桥接业务 ID 固定 CRANE-01。

| holding 地址 | 原始类型/换算 | 含义/场景实际范围 |
|---|---|---|
| 0x0000 | uint16 | 状态 0 待机 / 1 作业 / 2 故障 / 3 检修；当前场景不生成 3 |
| 0x0001 | uint16 ÷10 | m/min，实际 0～45 |
| 0x0002 | uint16 | 0 停止 / 1 东 / 2 西 |
| 0x0003 | uint16 ÷10 | 载重 t，实际 0～32 |
| 0x0004 | uint16 ÷10 | 起升高度 m，实际 0～12 |
| 0x0005 | uint16 ÷10 | 风速 m/s，实际 0～18 |
| 0x0006 | uint16 | 联锁 0 不可用 / 1 可用 |
| 0x0007 | uint16 | 健康 0 正常 / 1 降级 / 2 故障 |
| 0x0008 | uint16 ÷10 | 小车位置 m，实际 0～35 |
| 0x0100 | uint16 | 0 idle / 1 hoist / 2 travel / 3 wind / 4 fault |

travel 约 40s 东西往复；hoist 的起升、载重、小车分别有周期；wind 场景 12～18m/s、健康降级，但联锁仍可用；fault 将主要量清零并令联锁不可用。切场景不重置相位。

桥接只传状态、km/h 速度、方向、controlStatus、radarStatus、communication、x/y。速度换算：`raw÷10×0.06`；载重、起升、风速、小车位置当前均丢弃。地图 x 是桥接速度积分的构造位置，轨道 42～57、y=20、初值 49.5，dt 最多 5s，重启复位；它不是原始 PLC 大车位置实测值。

### 3.6 液压翻箱机

脚本：`device-sim/tipper/tipper_sim.py`，默认 dump；桥接业务 ID 固定 TIP-02。

| holding 地址 | 原始类型/换算 | 含义/场景实际范围 |
|---|---|---|
| 0x0000 | uint16 | 0 待机 / 1 就位 / 2 举升 / 3 倾卸 / 4 回落 / 5 故障 |
| 0x0001 / 0x0002 | uint16 | 当前速度/方向均固定 0 |
| 0x0003 | uint16 ÷10 | 箱重 t，当前 0/12/24 |
| 0x0004 | uint16 ÷10 | 角度 °，正常 0～135；故障固定 60 |
| 0x0005 | uint16 ÷10 | 压力 MPa，实际 0～32 |
| 0x0006 | uint16 | 联锁 0/1 |
| 0x0007 | uint16 | 健康 0/1/2 |
| 0x0100 | uint16 | 0 idle / 1 dump / 2 blocked / 3 fault |

dump 为 40s 循环：就位 6s、举升 14s、倾卸 8s、回落 10s、待机 2s。blocked 固定举升阶段、60°、28～32MPa；fault 固定 60°，并非保存切换瞬间角度。切场景不重置相位。

桥接仅传设备状态、speed=0、direction=固定工位、联锁和健康文本；箱重、角度、压力均未进入现有 device-state JSON，不会因这些量变化自动产生完整堵料诊断。

### 3.7 限位开关

脚本：`device-sim/limit_switch/limit_switch_sim.py`，默认 intermittent；桥接为 LOADING-A。

| holding 地址 | 类型 | 含义 |
|---|---|---|
| 0x0000 | uint16 | 0 未压合 / 1 压合；“压合=有人作业”是项目模拟语义，不是通用限位器能力 |
| 0x0001 | uint16 | 上升沿累计次数，线上 16 位回绕，内部整数继续累加 |
| 0x0002 | uint16 | 健康 0/1/2；当前场景生成 0 或 2 |
| 0x0100 | uint16 | 0 idle / 1 working / 2 intermittent / 3 fault |

intermittent 为 20s 中前 12s 压合；fault 压合=0、健康=2。切场景不清计数、不重置相位。

桥接把压合转 boolean personnelWorking 和 signal；故障时传 personnelWorking=null，丢弃次数及原始健康码。没有机械去抖。后端收到显式 null 后清空旧人员状态，页面显示“状态未知”；字段缺省才保留旧值。

### 3.8 位置传感器

脚本：`device-sim/position_sensor/position_sensor_sim.py`，默认 arrive；桥接为 LOADING-B。

| holding 地址 | 类型 | 含义 |
|---|---|---|
| 0x0000 | uint16 | 距离 mm，生成时钳制 0～10000 |
| 0x0001 | uint16 | 占用 0 无车 / 1 有车 / 2 故障 |
| 0x0002 | uint16 | 健康 0/1/2，当前场景生成 0 或 2 |
| 0x0100 | uint16 | 0 empty / 1 arrive / 2 occupied / 3 depart / 4 fault |

正常距离≤3000mm 判有车；empty=8500mm，occupied=2400mm。arrive 为 30s 循环，前 15s 9000→2400，再保持；depart 前 15s 逐步离开，之后直接 8500。fault 为距离 0、占用 2、健康 2，不能只根据零距离判有车。此设备在线切场景会重置相位。

桥接传 vehiclePresent、distanceMm、signal；故障传两个数值字段 null，不转发原始占用/健康枚举，没有独立进车/出车事件状态机。

### 3.9 在线场景更新的共同限制

雷达、门机、翻箱机、限位、位置传感器支持在起址恰好为 0x0100 的 FC06/FC16 写操作后切场景。若从更低地址批写、仅在中途覆盖到 0x0100，回调不一定切场景。场景寄存器也未初始化为启动 CLI 所选值，因此读取 0x0100=0 不保证当前运行场景 0。业务状态量可能被下一次 tick 覆盖，不能用任意写寄存器替代正式控制 API。

### 3.9 转运车辆

默认读取 `vehicle/profiles.json` 两台车辆。每个车辆、每批次输出一行JSON；seq从0按批次递增，默认1s一批。轨迹自动首尾闭合、按单调运行时间沿轨迹前进；不是GNSS、CAN或真实底盘反馈。

| 字段 | 类型/单位 | 含义 |
|---|---|---|
| schema/type/simulated | string/string/boolean | scs.sim.vehicle.v1 / vehicle / true |
| seq/ts/deviceCode | integer/ISO string/string | 批次序号、+08:00生成时间、默认VEH-07/08 |
| scenario | string | travel/idle/slow/stop/fault |
| x_m/y_m | number，m | 本地二维路线坐标 |
| speed_mps | number，m/s | travel使用配置速度，slow为1/4，idle/stop/fault为0 |
| heading_deg | number，0..360 | 数学平面方向角；0沿+x，90沿+y |
| battery_pct | number，0..100% | 配置值，不模拟电化学放电 |
| health | NORMAL/DOWN | fault为DOWN |
| brakeApplied/interlockOk | boolean | 模拟stop才置制动，fault令联锁不可用；不是实际执行确认 |

原始示例：

```json
{"schema":"scs.sim.vehicle.v1","simulated":true,"type":"vehicle","seq":0,"ts":"2026-11-05T12:00:00+08:00","deviceCode":"VEH-07","scenario":"travel","x_m":30,"y_m":50,"speed_mps":2,"heading_deg":0,"battery_pct":86,"health":"NORMAL","brakeApplied":false,"interlockOk":true}
```

转换为SCS：`code=deviceCode`；`speed=speed_mps×3.6`（沿用现有bridge/UI的km/h口径）；`x=x_m/site_width×100`、`y=y_m/site_height×100`，后端再钳制到0..100；status/direction/controlStatus/communication为展示文本。原始电量/制动不新增写入后端列，也不覆盖雷达健康和距离。

```json
{"source":"device-sim/vehicle","devices":[{"code":"VEH-07","status":"行驶中","speed":7.2,"direction":"向东","x":30,"y":50,"controlStatus":"联锁可用（模拟输入）","communication":"模拟上报"}]}
```

`--scs`才启用POST `/api/v1/ingest/device-state`，从`--api-key-env`（默认SCS_API_KEY）读凭据；可加`--source-id`，同时用该ID作body.source及X-SCS-Source-ID，须预先登记来源、归属和精确车辆白名单。脚本检查HTTP200之外的applied/ignored；拒绝/忽略返回失败，不创建设备。该接口不更新碰撞距离、不产生告警，不执行PLC。

默认仅打印；`--dest host:port`可选发原始UDP（接收端由调用方另备）；`--format raw|scs`选择打印格式；`--once`强制仅预览、不联网。`--count N`有限运行，0持续，Ctrl+C退出；`--interval`最小0.2s。故障/停止场景在本次进程的路线起点静止，不保存上次进程停止位置。

更新：profiles的code/path_m/speed_mps/battery_pct在启动读取，修改后重启；`--only`只选一台；站场宽高默认各100m，可用`--site-width-m/--site-height-m`修改。业务速度设计SQL中另有m/s注释差异，正式协议对齐须统一单位，不能据本替身倒推厂家单位。


### 3.10 摄像头视频源

摄像头替身默认CAM-01。GET `/health`返回UTF-8 JSON；GET `/snapshot.jpg`返回`image/jpeg`；GET `/stream.mjpg`返回`multipart/x-mixed-replace; boundary=camera-sim-frame`。每一部分有Content-Type与Content-Length，使用CRLF分隔，最后有关闭boundary。默认每次请求30帧、2fps，可用`--frames`（1..300）、`--fps`（0.1..30）修改，最多8个并发worker；流结束后消费者须重新请求。

| health字段 | 类型/单位 | 含义 |
|---|---|---|
| simulated/scsIngestConnected | boolean | 固定true/false |
| cameraCode/scenario/status | string | CAM-01；online/offline/fault；ONLINE/OFFLINE/FAULT |
| processAlive/online/mediaAvailable | boolean | 模拟器存活、模拟设备在线、媒体可取；fault仍在线但媒体不可取 |
| uptimeSec/lastHeartbeat | integer秒/带时区ISO string | 单调运行时间及本地模拟进程心跳，非服务器已接受的心跳 |
| imageSource | synthetic/user-jpeg | 内置8×8灰度JPEG或用户JPEG |
| lastError | string/null | 正常null；离线SIMULATED_OFFLINE；故障SIMULATED_CAPTURE_FAILURE |
| capabilities/limitations | string[]/string | 支持项及模拟边界 |

online健康/媒体均200；offline健康/媒体均503；fault健康诊断200、媒体503。HTTP200的健康诊断不等于视频可用。媒体是内置固定图或`--image`指定的JPEG，重复帧不是实际视频采样，也不会自动产生AI事件。用户图启动读取，上限8MiB，结构校验不能代替完整JPEG解码。

更新：修改camera-code、scenario、image、端口、帧率及帧数后重启；新请求生成新的运行时间，图片本身不随心跳变化。没有在线改场景接口。未实现RTSP/ONVIF/PTZ或SCS摄像头健康写入；真实视频接入仍需选定厂家协议和取流链。


JSON诊断示例（直接由模拟类生成，时间固定用于文档）：

```json
{
  "simulated": true,
  "scsIngestConnected": false,
  "cameraCode": "CAM-01",
  "scenario": "online",
  "status": "ONLINE",
  "processAlive": true,
  "online": true,
  "mediaAvailable": true,
  "uptimeSec": 0,
  "lastHeartbeat": "2026-11-05T12:00:00+00:00",
  "imageSource": "synthetic",
  "lastError": null,
  "capabilities": [
    "http-health",
    "jpeg-snapshot",
    "finite-mjpeg"
  ],
  "limitations": "Synthetic/local media only; no RTSP, ONVIF, PPE inference or SCS writes."
}
```


### 3.11 边缘节点/采集网关遥测

默认EDGE-01、role=edge，`--role gateway`仅更改角色标签，不实际采集/转发。GET `/health`始终200；模拟云链路断开时本地进程仍能回答诊断。

| 字段组 | 类型/单位 | 说明 |
|---|---|---|
| id/role/scenario/status | string | 节点、edge/gateway、场景、ONLINE/DEGRADED/RECOVERING |
| simulated/scsIngestConnected/processAlive | boolean | true/false/true |
| cloudConnected/autonomyActive | boolean | 云链路与自治指示，仅合成状态，不执行自治 |
| lastHeartbeat/lastCloudHeartbeat/nodeTime/lastSyncAt | ISO string或null | 本地心跳、云心跳、带模拟时偏的节点时间、同步时间；UTC偏移明确，断网云心跳/同步为null |
| heartbeatScope | string | 固定local-simulator-process，不代表SCS收到心跳 |
| cpuUsage/memoryUsage/diskUsage | integer，% | 确定性合成资源占用 |
| temperature/latencyMs | integer°C / integer或null，ms | 断网latencyMs=null |
| queueDepth/uptimeSec | integer事件数/秒 | 合成队列数量、单调运行时长，不是持久队列 |
| activeRuleVersion/expectedRuleVersion | string | 规则当前/期望版本，rule-v1/v2 |
| activeFenceVersion/expectedFenceVersion | string | 围栏当前/期望版本，fence-v1/v2 |
| clockOffsetMs/clockDrift/ruleMismatch | integer ms/boolean/boolean | 时偏超过1000ms指示漂移；版本不一致指示 |
| recoveryPhase/agentVersion/lastError | string或null | 恢复阶段、模拟版本与错误说明 |
| units/limitations | object/string | 单位表及边界 |

场景：online正常；disconnected云断开、DEGRADED，资源与本地心跳继续更新，queueDepth按时间增长到上限10000；recovering前5s重连、5..10s版本核对、10..20s合成队列下降，20s后ONLINE；clock-drift从2500ms起漂移；rule-mismatch规则及围栏版本不一致。所有曲线按单调时间在请求时惰性计算，不是固定周期向云发送。

更新：`--node-id`、role、scenario、host/port修改后重启，资源曲线及版本常量修改代码；当前无热更新。队列、恢复及自治均为生成值，没有真实缓存、重放、规则下发、NTP或外部SCS心跳写入，不会改变运维页面原有同进程演示数据。


JSON诊断示例（直接由模拟类生成，时间固定用于文档）：

```json
{
  "simulated": true,
  "scsIngestConnected": false,
  "id": "EDGE-01",
  "role": "edge",
  "scenario": "online",
  "status": "ONLINE",
  "processAlive": true,
  "cloudConnected": true,
  "autonomyActive": false,
  "lastHeartbeat": "2026-11-05T12:00:00+00:00",
  "heartbeatScope": "local-simulator-process",
  "lastCloudHeartbeat": "2026-11-05T12:00:00+00:00",
  "latencyMs": 12,
  "cpuUsage": 30,
  "memoryUsage": 45,
  "diskUsage": 25,
  "temperature": 44,
  "queueDepth": 0,
  "activeRuleVersion": "rule-v2",
  "expectedRuleVersion": "rule-v2",
  "activeFenceVersion": "fence-v2",
  "expectedFenceVersion": "fence-v2",
  "clockOffsetMs": 25,
  "clockDrift": false,
  "ruleMismatch": false,
  "uptimeSec": 0,
  "nodeTime": "2026-11-05T12:00:00.025000+00:00",
  "lastSyncAt": "2026-11-05T12:00:00+00:00",
  "recoveryPhase": null,
  "agentVersion": "python-stdlib-sim-1",
  "lastError": null,
  "units": {
    "cpuUsage": "%",
    "memoryUsage": "%",
    "diskUsage": "%",
    "temperature": "degC",
    "latencyMs": "ms",
    "clockOffsetMs": "ms",
    "uptimeSec": "s",
    "queueDepth": "events"
  },
  "limitations": "Generated telemetry only; no actual caching/replay, local actuation, NTP or SCS writes."
}
```


### 3.12 PLC 命令与反馈

这是独立启动的执行部件替身，默认PLC-01绑定VEH-07；也可指定其他已有机械编号，绑定文字本身不会驱动那些模拟器。初始running=true，所有状态仅在内存。

| 地址 | 读写/单位 | 含义 |
|---|---|---|
| 0x0010 | holding读写，uint16 | commandId，本次进程内唯一0..65535 |
| 0x0011 | holding读写，uint16 | action：0无操作、1告警、2减速、3停机、4清除模拟告警 |
| 0x0012 | holding读写，uint16 | 减速上限1..100%；其他action参数必须0 |
| 0x0013 | holding读写 | submit写1执行，随后自动归0；写0不执行 |
| 0x0020..0x0023 | holding/input只读 | ackValid、ackCommandId、ackAction、result |
| 0x0024..0x002B | holding/input只读 | running、slowed、stopped、alarm、interlock、health、speedLimitPct、执行次数16位 |
| 0x0100 | holding读写 | 场景0 normal /1 timeout /2 rejected /3 interlock-open |

result：0无回执、1成功、2拒绝、3同ID冲突、4联锁断开、5无效命令。反馈必须同时核对ackValid、ID、动作和result；不能因Modbus写成功就当停机成功。

一次FC16从0x0010写`[7,2,30,1]`表示ID7减速到30%模拟速度上限；写`[8,3,0,1]`表示ID8停机。分次写入须最后写submit，不会因提前改action而执行。多客户端应使用单个FC16或串行调度，避免共享暂存字段串单。

同ID同动作同参数只重放原回执，不重复执行、不倒退当前状态；同ID不同内容返回冲突。失败/超时也占用ID，恢复场景后须新ID。timeout中ackValid=0，不会自动补成功。清除告警不解除停机/减速，停止锁存只能重启该模拟器回初始状态；不等同人工复位或真实停机确认。ID历史保留到进程结束，全部16位ID用尽后重启。

更新：写0x0100可在线切场景；host/port/unit/device-code/target改CLI后重启；点表及动作语义修改脚本并同步采集端。此PLC只保护自己的反馈写入，未补改其他旧Modbus模拟器。SCS当前没有外部PLC回执接入口，不能宣称其联动页面已由本脚本驱动。


JSON诊断示例（直接由模拟类生成，时间固定用于文档）：

```json
{
  "protocol": "project-defined-modbus-plc-v1",
  "simulated": true,
  "scsConnected": false,
  "backendReceiptRoute": false,
  "deviceCode": "PLC-01",
  "target": "VEH-07",
  "host": "127.0.0.1",
  "port": 15120,
  "unit": 1,
  "scenario": "normal",
  "command": {
    "commandId": 8,
    "action": 3,
    "parameter": 0,
    "submit": 0
  },
  "ack": {
    "valid": true,
    "commandId": 8,
    "action": 3,
    "result": 1,
    "resultName": "success"
  },
  "pendingCommandId": null,
  "state": {
    "running": false,
    "slowed": false,
    "stopped": true,
    "alarm": false,
    "interlock": 1,
    "health": 0,
    "speedLimitPct": 0,
    "executions": 1
  },
  "limitations": "Local volatile simulation only; reset clears simulated alarm only; no physical safety or manual reset confirmation; no SCS integration."
}
```


## 4. SCS 接入接口与约束

### 4.1 接口总表与成功判据

以下五路均为 POST JSON；成功响应直接为业务对象，不包 `code/data`：

| 接口 | 数组字段 | 对象标识 | 主要作用 |
|---|---|---|---|
| `/api/v1/ingest/person-position` | positions | id=人员 personnel_code | 更新实时位置内存覆盖层 |
| `/api/v1/ingest/uwb-station` | stations | stationId | 更新基站内存健康；查询时判定 10s 过期 |
| `/api/v1/ingest/device-state` | devices | code=设备 device_code | 更新已登记设备状态；server 使用 JDBC 保存 |
| `/api/v1/ingest/radar` | readings | pairDevice=配对主键 | 更新内存距离、趋势、风险及相关告警 |
| `/api/v1/ingest/zone-state` | zones | code=区域 area_code | 更新装卸区内存状态 |

典型成功响应：

```json
{"applied": 1, "ignored": [], "source": "device-sim/bridge"}
```

HTTP 200 不等于全批更新成功，必须检查 applied/ignored 和后续授权查询。applied 为条目数，不一定是不同 ID 数量。普通 controller 模式中，缺 body/数组按空批次处理，缺 source 返回 unknown；命名来源另有非空数组、唯一 ID、白名单、整批校验规则。UWB 某些被归属 guard 忽略的非空 ID 不进入 ignored，故 applied=0/ignored=[] 也可能发生。

错误为 `{code,message,traceId,details,timestamp}`，HTTP 400 常为反序列化/校验问题，命名来源拒绝为 403。下表“必填”表示成功应用该条所需；五路 DTO 本身没有统一的逐字段 Bean Validation 保证。

### 4.2 人员位置

当前桥接的 envelope 形状如下。示例使用已登记人员编号；默认 UWB routes 的 P-1001 等与后端演示 seed 不同，需要 `--map` 或权威人员登记，不能靠上报创建人员。

```json
{
  "source": "device-sim/uwb",
  "positions": [
    {"id": "P-ZHAO", "x": 25.0, "y": 40.0, "battery": 90, "accuracy": 0.18}
  ]
}
```

| 字段 | 类型 | 约束与缺省 |
|---|---|---|
| id | string，成功应用必填 | trim 后查已登记 personnel_code；不是手环号；未知忽略，不创建身份 |
| x/y | number，均必填 | 地图百分比，钳制 0～100，保留一位 |
| battery | integer，可缺省 | 0～100%；缺省时本次缓存不覆盖台账电量，不是保留上一帧实时电量 |
| accuracy | number，可缺省，m | 大于 0 时≤0.30 优秀，≤0.80 良好，其余较低；无有效值则回退台账质量 |

只存内存最新点；返回列表/详情/live 时叠加。位置上报不自动把台账手环状态改在线、不改变台账风险、不直接触发电子围栏告警。0.62m 在这条链路中仍是“良好”，不是资料卡所述大于 0.5m 自动抑制越界。没有位置过期转离线；后端 lastUpdated/查询 ts 不能当设备采样时间。一批成功发 person.moved。

### 4.3 UWB 基站健康

```json
{
  "source": "device-sim/uwb",
  "stations": [
    {"stationId": "BS-01", "online": true, "clockSynced": true, "snrDb": 32, "powerOk": true, "tagCount": 8}
  ]
}
```

stationId 为必需非空 string；其余可缺省：online/clockSynced/powerOk 缺省或 null→true，snrDb（integer，dB）和 tagCount（integer）缺省→0，未检查非负/合理范围。每条是完整替换，不是局部 patch。

服务器记收到 HTTP 的时间，查询时超 10000ms 才把 online 视为 false；保留其他字段与 lastSeen，不定时删除，不发业务 WebSocket。命名归属键为 `UWB:BS-01`。

查询 GET `/api/v1/personnel/uwb-stations`：

```json
{
  "summary": {"total": 1, "online": 1, "minSnrDb": 32, "allClockSynced": true},
  "stations": [
    {"stationId": "BS-01", "online": true, "clockSynced": true, "snrDb": 32, "powerOk": true, "tagCount": 8, "lastSeen": "2026-11-05T12:00:00+08:00"}
  ]
}
```

最小 SNR 与同步摘要只统计在线站；无在线站时 minSnrDb=null、allClockSynced=false。重启缓存清空。

### 4.4 设备状态

```json
{
  "source": "device-sim/bridge",
  "devices": [
    {"code": "CRANE-01", "status": "作业中", "speed": 1.8, "direction": "东行", "controlStatus": "联锁可用", "communication": "在线·1ms", "radarStatus": "正常", "x": 49.5, "y": 20.0},
    {"code": "TIP-02", "status": "举升", "speed": 0.0, "direction": "固定工位"}
  ]
}
```

以上文字值是格式示例，不是严格状态枚举，也不是实测网络时延。

| 字段 | 类型 | 当前处理 |
|---|---|---|
| code | string，成功应用必填 | 查已登记设备；未知忽略，不新增 |
| status/direction/controlStatus/communication/radarStatus | string，可缺省 | 非空 trim 后最多 48 字；空白/null 保留旧值；没有严格枚举 |
| speed | number，可缺省 | service 按 MAX_SPEED_KMH=60 钳制 0～60，不在后端转换单位；主桥接使用 km/h |
| x/y | number，可分别缺省 | 地图百分比，各自钳制 0～100，未统一做一位小数化 |

仅传 code 也可更新服务器 lastUpdated 并计 applied。radarStatus 文本含“中断”设断数、含“异常”设不确定，其他非空设正常。接口不更新距离或直接建告警；配对装饰后的查询显示还可能覆盖保存的风险/控制文字。

**单位待统一处：** service 常量按 km/h，仓库内设计 SQL 的 speed 注释为 m/s，DTO 没有权威单位声明。本说明忠实记录现桥接输出 km/h；正式设备接入须统一服务、schema 与文档，不能只依赖字段名。

### 4.5 雷达距离

```json
{
  "source": "device-sim/bridge",
  "readings": [
    {"pairDevice": "VEH-07", "distance": 7.0, "relSpeed": 0.5, "radarQuality": 60.0, "health": "NORMAL"}
  ]
}
```

| 字段 | 类型 | 当前处理 |
|---|---|---|
| pairDevice | string，成功应用必填 | 配对 map key，当前 VEH-07、VEH-08；不是任意一端 ID |
| distance | number，成功应用必填，m | 钳制 0～500 |
| relSpeed | number，可缺省 | 主桥接用 m/s；缺省保留；>0.05 接近，<−0.05 远离，其他保持；后端未显式声明单位或限定范围 |
| radarQuality | number，可缺省，% | 钳制 0～100、四舍五入成整数；缺省保留 |
| health | string，可缺省 | trim 转大写；DOWN/RADAR_DOWN 断数，DEGRADED/UNCERTAIN 不确定，其他含缺省→正常 |

初始两对为 VEH-07↔TIP-02、VEH-08↔CRANE-01；距离、趋势、健康、关联告警 ID 是内存态，重启重置，无采样过期机制。关闭 demo seed 不会自动消除构造器中的初始配对/趋势。GET pair 的 ts 是查询时刻。

风险显示：d≥10 安全；6≤d<10 预警；3≤d<6 严重；d<3 紧急。新建告警的实际距离阈值是 d≤5.4，并不覆盖整个“严重”区间。恢复安全不自动关闭既有告警。

当前先执行距离/风险逻辑，再处理 health；因此 DOWN+0m 也可能先建/升级告警。读取失败时主桥接只跳过，不主动上报 DOWN。这些不能作为生产失效安全策略已完成的证明。

### 4.6 装卸区状态

```json
{
  "source": "device-sim/bridge",
  "zones": [
    {"code": "LOADING-A", "personnelWorking": true, "signal": "压合"},
    {"code": "LOADING-B", "vehiclePresent": true, "distanceMm": 2400, "signal": "有车"}
  ]
}
```

| 字段 | 类型 | 当前处理 |
|---|---|---|
| code | string，成功应用必填 | 默认缓存 LOADING-A/B；命名来源可访问另行登记且归属匹配的区域 |
| personnelWorking | boolean/null，可缺省 | 缺省保留旧值；显式 null 清空为未知；false 有效 |
| vehiclePresent | boolean/null，可缺省 | 缺省保留；显式 null 清空；false 有效；不从 distanceMm 自动推导 |
| distanceMm | integer/null，可缺省，mm | 缺省保留；显式 null 清空；0 有效；负值写 null；>10000 钳制 10000 |
| signal | string，可缺省 | trim、最多 64 字；null/空白保留 |

任一成功条目（包括只含 code）都会置 online=true、记更新时间并发 zone.changed。初始字段 null、signal=未接入、online=false；没有过期转离线。online 表示接入状态，不表示传感器无故障。桥接故障显式传 null 后，后端清空此前人员/车辆/距离，页面显示“状态未知”并去除旧距离；后续有效读数恢复。既有 Java 五参数 apply 调用仍保持 null=缺省的兼容语义，HTTP presence 标记为内部字段，不能由客户端覆盖。

区域故障请求示例（不执行请求）：

```json
{
  "source": "device-sim/bridge",
  "zones": [
    {"code": "LOADING-A", "personnelWorking": null, "signal": "限位开关故障"},
    {"code": "LOADING-B", "vehiclePresent": null, "distanceMm": null, "signal": "位置传感器故障"}
  ]
}
```

GET `/api/v1/zones` 返回 `{"list":[...]}`，每项含 code/name/sensorType/personnelWorking/vehiclePresent/distanceMm/signal/updatedAt/online。LOADING-A/B 与 DemoMasterData 的 LOADING_AREA_A/B 是不同编码，代码没有统一自动别名转换。

### 4.7 身份、来源和对象归属

**source 字段只是业务标签，不是身份认证。** 当前两份 device-sim 桥接只发送共享 `X-API-Key`，没有 `X-SCS-Source-ID`。命名来源格式与当前桥接现状应分开描述：

| 模式 | 凭据与路由 | 对象前提 |
|---|---|---|
| 命名机器 | X-SCS-Source-ID + 独立 X-API-Key；仅 POST person-position/device-state/zone-state/uwb-station | 来源登记、有效期、project/region/SCS tuple、精确对象白名单、数据库根归属均匹配 |
| 旧共享机器 | 不带来源头，X-API-Key；可经旧路径访问 ingest 与 AI ingest | server 的既有对象需三属性全部 null；有归属/部分归属不能靠共享 key 更新；区域/UWB guard 仍需数据库核查 |
| 中央范围用户 | 走用户权限/范围路由策略 | 当前没有放行这些设备 ingest 或 AI 新根创建；不能用浏览器可见区域反推上报权限 |

命名机器的 root 映射：person-position→PERSONNEL/personnel_code；device-state→COLLISION/device_code；zone-state→AREA/area_code；uwb-station→RUNTIME/`UWB:<stationId>`。

命名批次还要求：body≤256KiB；数组 1～1000 条；ID 不重复；全部对象允许；如写 source，必须精确等于 source ID；不能在 body 递归注入 project/region/subsystem/userContext 等归属信息。失败可整批拒绝，不是逐条 ignored。带命名来源头失败不会回落共享 key。

**当前不支持命名来源的路由是 radar 与 AI ingest。** 不能只给桥接新增头就声称这两条已完成中央范围接入。现有任意旧 SSO/JWT 配置下是否能调用接口还取决于 SecurityInterceptor 的实际分支，不能把 controller 的“机器接口”注释写成所有配置下的绝对限制。

启用中央归属前按 v21 README 执行/核实 001→002 迁移，再按权威依据登记已有业务根对象与 UWB 运行归属、机器来源。迁移不会自动登记来源或给对象/账号授权；未知归属不能按演示名称批量推定。

## 5. 数据来源与更新机制

### 5.1 数据来源分层

| 层次 | 来源 | 应如何理解 |
|---|---|---|
| 协议模拟 | `device-sim/` 的周期公式、随机扰动、脚本场景、寄存器状态 | 模拟设备报文；是否被 SCS 接收还取决于桥接、身份和对象登记 |
| 视觉场景 | `edge-ai/` 的图片模板/程序绘图、模型输出、场景真值补入 | 具有图片证据的模拟推理，不是持续采集的现场摄像头视频 |
| 后端台账与演示 | 主数据、seed、内存/数据库仓储、模拟接口及定时任务 | 对象存在不代表对应传感器正在联网；持久化与 profile 需分别核对 |
| 后端动态更新 | ingest 接口写入的已登记对象及事件/告警投影 | 才是采集数据进入业务后的结果；存在授权、过滤和字段转换 |
| 前端展示 | REST 响应、WebSocket 触发的重拉、部分组件的固定示例 | 页面数字不一定都来自设备采集；须看具体组件与 demo 标识 |

### 5.2 前端刷新不是设备采样

| 功能 | 当前更新方式 |
|---|---|
| 人员列表 | 首次读取 `/personnel`，收到 `person.*` 重拉；配置成功读取后按 `/personnel/config` 周期轮询。页面初始显示 1000ms。配置初次获取失败时，不会因此自动启动该定时器 |
| UWB 健康 | 首次读取 `/personnel/uwb-stations`，之后每 5s；请求不重叠，失败清空该卡片结果 |
| 首页地图 | 首次读取 `/overview/map`；人员事件合并 300ms 后只重拉地图；告警/AI 事件和重连会触发更完整刷新 |
| A/B 装卸区 | 首次读取 `/zones`，`zone.*` 合并 300ms、重连后重拉；无上报显示“未接入”，不是“无人/无车” |
| 防碰撞 | 首次读取设备/配对/趋势；`collision.*` 合并 250ms 重拉；无独立周期轮询 |
| AI 事件/边缘状态 | `ai.*`、重连和操作后重拉；边缘状态每 15s 读取一次 |
| 运维台账 | 首次和 `ops.*` 合并 300ms 重拉，含 Backend Demo 状态 |
| 大屏地图 | 识别出的业务事件合并 260ms 后重拉；大屏/移动端告警聚合另用约 280ms 合并 |

`/ws/live` 是应用内业务通知，不是设备原始上报口。浏览器约 20s 心跳，断线按指数退避重连；中央范围下的消息应只通知当前授权范围可刷新，由 REST 重新执行权威查询。不要把 WebSocket 通知间隔当成设备采样间隔。

当前首页 DeviceHealth 默认 12/12 基站、22/24 摄像头、8/8 雷达、4/4 网关；防碰撞顶部 36/38 等仍含固定展示，大屏也有示例回退。这些数字不能作为当前模拟器进程数或现场在线数量。详见配套《前端与边缘AI核查记录》。

### 5.3 修改、更新与生效边界

| 想更新什么 | 修改位置/方式 | 生效与注意事项 |
|---|---|---|
| UWB 人员路线、速度、相位、电量、ID | routes.json 的 people；或 `--routes`、`--only` | 仅启动读取，改文件后重启 UWB 模拟器；路线中的 P-1001 等不会自动成为 SCS 人员 |
| UWB 发送周期/场景 | `--rate`、`--scenario patrol/sos/degraded/backfill` | 重启模拟器；rate 钳制 0.2～5Hz；低频也拖慢主循环中的基站发送 |
| UWB 接收与坐标转换 | 桥接 `--listen`、`--map`、`--site-width-m`、`--site-height-m` | 重启桥接；默认 listen=127.0.0.1:9000、宽高各100；routes 顶层 site 不会自动同步 |
| UWB HTTP 周期/过期 | `--interval` 默认1、`--stale-seconds` 默认10、`--station-interval` 默认5 | 重启桥接；人员/基站分别判断发送周期，基站不依赖人员批次；dry-run 打印两类帧，不发 HTTP；HTTP 仍串行 |
| Modbus 场景 | 各设备 0x0100；声光用控制寄存器 | 对支持的设备可在线切换；启动 CLI 的 scenario、port、unit 变化需重启。桥接默认 unit=1，不能仅改单侧 |
| Modbus 读取地址与周期 | 主桥接 `--crane` / `--tipper` / `--switch` / `--position` / `--radar` / `--interval` | 默认均回环及表中端口，interval=2s；需重启桥接。固定业务 ID 或寄存器映射变化须改桥接代码 |
| SCS 目标与机器凭据 | 两桥接 `--scs`；凭据建议通过 `--api-key-env` 指定的环境变量（默认SCS_API_KEY） | 重启桥接读取；现实现不支持命名来源头，不能仅更换共享 key 就更新已有中央归属对象 |
| AI 盒事件场景/频率/回调 | `--scenario`、`--count`、`--interval`、`--duplicate-at`、`--webhook`、`--hold` | 重启 AI 盒模拟器；截图仅进程内存；没有动态注册回调或热改场景 API |
| 页面人员刷新频率 | GET/PUT `/api/v1/personnel/config` 的 refreshIntervalMs | 后端内存配置默认1000ms、允许200～60000ms；发布配置通知，页面重设拉取定时器。不是后端定时采样或原始 UWB 频率 |
| 业务身份/区域/归属 | 权威人员、设备、区域根台账及中央三属性登记；UWB用运行归属表 | ingest 不应创建第二套身份来源；需依据现场清单完成登记，不能靠模拟数据名称推定 |
| 命名机器来源/对象白名单 | 受保护 machine-bindings-file | 每次请求重读/复核 revision，可影响在途绑定；需保持合法文件权限/有效期/精确对象清单，不把真实值写进本说明 |
| 设备新增字段/新协议 | 原始模拟器、桥接映射、后端DTO/service/持久化、事件/页面与资料卡一起更新 | 只改 README 或只添一个寄存器不会贯通业务链路，应更新示例及独立、集成验证 |

### 5.4 后端持久性和演示来源

| 数据 | 来源及存储 | 重启/停报行为 |
|---|---|---|
| 人员身份 | Demo seed（7人：P-ZHAO、P-002～007）或server数据库；手环号另列 | 不是默认 routes 的8个人；需映射到现有人员。关 seed 不等于设备自动登记 |
| 人员实时点 | person-position → ConcurrentHashMap覆盖层 | 重启清空，停报不自动变离线；省略电量/精度会回退台账值 |
| 人员历史轨迹 | track 接口按台账位置构造固定9点 | 不是已存储 UWB 历史，不能作为回放验收证据 |
| 设备台账 | DemoDeviceMasterData/seed 或 server 的 collision_device | 门机、翻箱机状态在 server 用 JDBC 保存；输入不会新增设备 |
| 雷达配对/趋势 | CollisionService 构造的两对及内存更新 | 重启回初始距离/趋势，无采样过期；与告警持久化分开 |
| A/B区域状态 | ZoneStateService 内存缓存 | 重启回未接入；停报无过期；信号字段缺省保留、显式null清空 |
| UWB基站健康 | UwbStationService 内存快照 | 重启清空；最后接受报告超过10s时查询判离线；桥接不再重复转发已成功发送的旧帧，页面另有5s轮询延迟 |
| AI事件/时间线 | 内存仓储或server的ai_event及timeline | 入库与模型/截图存活期分开；原始AI盒快照不持久化 |
| AI摄像头健康视图 | DemoDeviceMasterData + 服务构造的固定信息，再按范围过滤 | ingest AI事件不会自动更新摄像头健康；新增数据库摄像头不等于静态列表自动新增 |
| 运维节点/指标 | Backend Demo；部分GET惰性构造心跳、运行时长和波动 | 字段叫heartbeat/cpu不等于真实探针上报；中央范围GET行为又不同 |

基础 demo seed/simulator 开关默认关闭，dev profile 默认开启；server 使用 JDBC，不能把非server内存 seed 当作数据库迁移。也不能认为关 seed 会消除所有构造器内静态视图。当前没有发现负责按周期生成上述设备采样的业务 scheduler；业务设备更新主要来自请求/模拟调用。openGemini 客户端目前仅有 ping，不能声称人员/雷达采集已写入完整时序历史。

### 5.5 维护文档的更新顺序

1. 确认实际设备型号/厂家协议版本，记录点表出处及尚未确定的单位。
2. 对齐原始 ID、权威业务 ID、坐标原点/尺寸、速度和置信度单位、时间与补传语义。
3. 同步修改模拟器、桥接、后端接收/持久化及页面，不把被桥接丢弃的字段写成已支持。
4. 分别验证原始协议、转换输出、认证/归属、applied/ignored、查询结果、告警和页面刷新；涉及执行器再单独验证下行/回执。
5. 更新资料卡、本文及示例数据的日期/提交基准。现有资料卡为手工 Markdown，没有自动生成或自动一致性校验机制。

## 6. 独立 edge-ai 视觉模拟服务

该服务与 `device-sim/aibox/aibox_sim.py` 是不同实现，不能混用原始事件契约。

### 6.1 触发与来源

边缘服务的 `POST /api/v1/simulate/trigger` 使用以下 snake_case JSON：

```json
{
  "scenario": "no_helmet",
  "camera_code": "CAM-01",
  "report_to_scs": true,
  "custom_person_name": "示例作业人员"
}
```

约定场景为 `no_helmet`、`no_vest`、`intrusion`、`compliant`。优先读取对应 jpg 模板，否则程序绘图；默认 1280×720。模型没识别出模拟场景预期违规时，会按场景真值补入固定结果和置信度，因此模拟成功不能证明模型的现场准确率。

`POST /api/v1/detect/image` 接受 multipart 图片；当前没有完整 RTSP/ONVIF 摄像头持续采集链路。`POST /api/v1/stream/start?interval_sec=10` 默认每轮工作完成后等待 10s，轮换合成场景；不能理解为严格固定 10s 出一帧。前端按钮请求的 5s 是另一层传入参数。

### 6.2 转为 SCS 事件

`edge-ai/client/scs_client.py` 转成 camelCase 的 `/api/v1/ai-events/ingest` 请求，例如：

```json
{
  "eventType": "未佩戴安全帽",
  "cameraCode": "CAM-01",
  "cameraName": "示例摄像头",
  "areaCode": "AREA-A",
  "confidence": 96.8,
  "threshold": 80.0,
  "durationSec": 1.0,
  "modelCode": "YOLOv8-Edge-v1.0",
  "riskCode": "HIGH",
  "sceneType": "示例作业面",
  "ruleCode": "RULE-PPE-HELMET",
  "judgeText": "模拟场景：人员未佩戴安全帽",
  "snapshotUrl": "/api/v1/ai-events/snapshots/example.jpg",
  "boxes": [
    {
      "id": "person-1",
      "label": "PERSON",
      "score": 98.0,
      "x": 20.0,
      "y": 30.0,
      "w": 10.0,
      "h": 40.0,
      "tone": "person"
    }
  ],
  "relatedPerson": "示例作业人员",
  "relatedDevice": "示例监控点位",
  "occurredAt": "2026-11-05T12:00:00+08:00"
}
```

- confidence、threshold、框的 score 是百分数语义；框的 x/y/w/h 为 0～100 百分比，不是像素。危险区配置的多边形另为 0～1；设备平面坐标又是另一套体系。
- 客户端提供 `X-API-Key` 与 `Idempotency-Key`，没有命名来源头。默认幂等键以摄像头、事件类型、规则、10s 时间桶组合；同桶内相同组合可能重复使用键。
- `status: SUCCESS` 不等于上报成功。模拟接口记录上报异常后仍可返回 SUCCESS，应核对非空 `scs_reports` 和对应 SCS 事件。
- `SCS_BACKEND_URL`、API key、存储与模型配置在进程中读取/缓存；修改后需重启边缘进程。模板在生成时查找，没有统一版本化热更新接口。

### 6.3 SCS AI DTO、状态与限制

此路成功直接返回 AI 事件对象，没有 source/数组 envelope。DTO 字段可缺省，但有以下校验/默认：

| 字段 | 类型/DTO上限 | 缺省行为 |
|---|---|---|
| eventType | string≤48 | AI视觉异常 |
| cameraCode / cameraName | string≤32 / ≤96 | CAM-01 / 最终cameraCode |
| areaCode | string≤48 | 现场作业区 |
| confidence / threshold | number 0～100 | 90 / 80 |
| durationSec | number 0～999999.99，s | 1 |
| modelCode | string≤64 | YOLOv8-Edge |
| riskCode | string≤32 | confidence≥90→HIGH，否则MEDIUM；应使用大写LOW/MEDIUM/HIGH或中文低/中/高，非严格枚举 |
| sceneType / ruleCode | string≤128 / ≤40 | 按类型派生 / 作业区安全规范 |
| judgeText | string≤4096 | 自动构造 |
| snapshotUrl | string≤2048 | 原样保留；没有自动下载、持久化和可达性证明 |
| boxes | array≤128 | 缺省空数组；框内无完整嵌套约束 |
| relatedPerson / relatedDevice | string≤32 / ≤32 | 现场作业人员 / 最终cameraCode |
| occurredAt | OffsetDateTime | 缺省用服务器上海时间；无完整新旧/未来时间检查 |

AiBox 接收 id、label、score、x、y、w、h、tone；score 可空，x/y/w/h 为数值，语义为截图百分比，tone 约定 person/violation/zone。但这些框内约定没有范围/枚举的嵌套校验，不能把“接收成功”当作格式完全正确。edge-ai 某些框额外含 confidence，这不是后端权威框字段。

返回对象主要映射：eventType→type、cameraCode→camera、areaCode→area、modelCode→model、sceneType→scene、ruleCode→rule，并增加 id、statusCode/status、riskCode/risk、health、timeline、occurredAt/updatedAt 等。

AI ingest 建立 PENDING/待复核事件并发布 ai.new，**不会仅因 confidence 超过 threshold 就自动建告警，也不会仅因低置信度自动变不确定**。人工确认或派单等后续动作才可建关联告警。后端 `/ai-events/simulate` 的 low-confidence 场景是另行构造 UNCERTAIN，不能据此推定真实 ingest 同样处理。

幂等优先使用显式 Idempotency-Key；无显式键时按摄像头/类型/DTO摘要构造，默认TTL600s，Kvrocks不可用时可降级直接执行。不同独立事件如载荷完全相同且没有源时间，也可能合并；不能宣称无条件 exactly-once。

**中央归属缺口：** 命名机器未支持 AI ingest，中央用户策略也未放行 AI 新根入口。旧共享 key 创建的新 AI 根不能从 cameraCode/areaCode 自动继承可信 tuple。需要专门完成来源、根归属和事件创建链路后，才能把它写成中央范围下已接通。

**场景不匹配：** 前端危险区按钮传 `danger_zone`，后端 trigger-edge 原样转发，边缘生成器期待 `intrusion`，当前没有转换。后端调用失败还可能 fallback 到内置 new 模拟事件，不能把成功响应当作所选危险区场景已运行。

**持久化口径提醒：** 现有 JDBC 兼容逻辑对 0<confidence/threshold<1 的输入存在比例/百分比歧义；设计 SQL 与部分 DTO 字符串上限也不一致。实际数据库未核查，本说明不把这些定义差异当成当前服务器已复现的故障。正式接入应按声明单位和实际 schema 验证往返结果。

## 7. 审查发现与适用边界

本次已区分实现事实、接入缺口和未实测事项。下表中的基站和区域状态问题已在本地修复，尚未提交或部署；其他接入缺口保持原状。

| 项目 | 已确认的行为及影响 |
|---|---|
| UWB 基站新鲜度（本地已修） | 成功转发后移除待发帧；失败重试有本地期限。后端仍以收到HTTP的时间记lastSeen，超过10s查询判离线；未新增源时间校验 |
| UWB 基站独立性（本地已修） | station调度在人员早退分支之前；仅station流、长人员周期、dry-run均覆盖；HTTP串行，仍非严格实时定时器 |
| UWB 身份映射 | 默认routes八人P-1001等与后端演示七人P-ZHAO/P-002等不同；不配置映射/权威身份时会忽略，HTTP200不代表位置已更新 |
| 区域故障清空（本地已修） | 显式null清空旧信号和距离，缺省保留，false/0有效；A/B未知显示一致。online沿用原含义，区域停报过期未增加 |
| 命名来源适配 | 两份桥接均只带共享key；radar与AI ingest更不在命名来源路由集合，不能认定已完成中央归属接入 |
| 雷达失效处理 | 距离/告警先处理、health后处理；fault=0m/DOWN可能先建告警，读取失败又只跳过，没有完整停报过期策略 |
| AI盒转换 | 原始entry/objectConfidence/UUID与SCS业务DTO/百分比/幂等头不同，缺专用转换与截图可达性处理 |
| edge-ai 图片检测 | PERSON框产score不产confidence，图片入口却读confidence；随后还把百分比坐标交给需要像素的入侵检测，可能报错/判定失真 |
| AI危险区场景 | danger_zone未转intrusion，调用成功不证明运行的是用户选择的越界场景；失败还可能降级为内部模拟 |
| 位置/区域/雷达停报 | 后端没有统一超时离线；UWB人员 collector删除也不发送离线；需要区分最后值、当前值与未知值 |
| 真实字段贯通 | 门机载重/起升/风速/小车位置、翻箱角度/压力等在桥接中丢弃；UWB事件仅日志，声光无SCS反向桥接 |
| 展示与资料偏差 | 固定设备计数、构造历史轨迹、README/卡片旧参数及统一事件声明不能写成已采集/已接通事实 |

UWB backfill 当前只置标，不是真实断线缓存补传；degraded 只改变精度/停某标签，并非完整电量与基站故障模型。SOS 独立event发送采用极窄时刻条件，可能漏发，tag.sos字段与事件是否发出应分别理解。

配套核查记录详述前端固定数据、页面刷新与边缘AI字段问题。`device-sim/verify/verify_all.py` 虽覆盖八类协议的若干场景，会启动监听/子进程和写寄存器，但没有调用两份 SCS 桥接：AI使用自建hook回200，声光直接由测试写入。因此它是协议模拟验证器，不能命名为八类设备SCS端到端验收。本轮只读核对其覆盖，未执行，也未沿用历史evidence宣称通过。

后续联调仍需明确身份/归属及单位，并验证实际设备链路；基站源时间校验、区域停报过期、雷达断数门控与AI格式适配尚未实现。本次只修复已选定的 UWB 缓存/独立调度和区域故障未知状态。

### 7.1 本地状态修复验证（2026-11-05）

| 验证 | 本次结果 | 范围 |
|---|---|---|
| UWB 桥接离线测试 | 11 项通过 | 停报不重放、待发帧到期、重新收帧恢复、逐站过期、只有基站流、长人员周期、失败重试、dry-run、人员坐标转换 |
| 后端针对性测试 | 18 项通过，失败/错误/跳过均为 0 | ZoneStateIngestTest 10 项、UwbStationFreshnessTest 1 项、DeviceIngestPreservationTest 7 项；含 null 清空、缺省保留、false/0、非法输入整批无副作用及内部调用兼容 |
| 前端完整单元测试 | 37 个文件、158 项通过 | 新增装卸区组件 4 项，覆盖 A/B 未知、旧距离消除、恢复、零距离、未接入 |
| 前端类型检查及生产构建 | 通过 | Node 24.18.0、pnpm 10.34.5；vue-tsc -b 与 Vite 构建；仍有构建分包体积提示 |
| 文档示例与引用 | 本轮扩展后18组JSON可解析并与配套文件一致；48个本地引用有效 | 包含区域故障及新增四项模拟报文，验证行号在文件范围内，不代表现场协议验证 |

新增 UWB 测试已接入 `.github/workflows/scs-ci.yml`，远端 CI 尚未执行。本次未运行后端全量测试，未启动完整应用进行浏览器联调，也未更新生产环境；本地修改尚未提交或推送。后端认证及对象归属守卫保持既有实现。

## 8. 实现与参考资料索引

### 8.1 本地代码依据

以下链接以本文所在文件夹为起点，源码版本以首页基准为准；行号可能随未来修改移动。

| 内容 | 实现依据 |
|---|---|
| UWB帧、场景与路线 | [uwb_sim.py:104](../../_gh_work/SCS/device-sim/uwb/uwb_sim.py:104)、[routes.json](../../_gh_work/SCS/device-sim/uwb/routes.json) |
| UWB转换与station调度 | [uwb_to_scs.py:69](../../_gh_work/SCS/device-sim/uwb/uwb_to_scs.py:69)、同文件213起 |
| Modbus TCP公共实现 | [modbus_tcp.py:26](../../_gh_work/SCS/device-sim/common/modbus_tcp.py:26) |
| 雷达/AI盒/声光 | [radar_sim.py:23](../../_gh_work/SCS/device-sim/radar/radar_sim.py:23)、[aibox_sim.py:60](../../_gh_work/SCS/device-sim/aibox/aibox_sim.py:60)、[alarmlight_sim.py:18](../../_gh_work/SCS/device-sim/alarmlight/alarmlight_sim.py:18) |
| 门机/翻箱机 | [crane_sim.py:22](../../_gh_work/SCS/device-sim/crane/crane_sim.py:22)、[tipper_sim.py:23](../../_gh_work/SCS/device-sim/tipper/tipper_sim.py:23) |
| 限位/位置 | [limit_switch_sim.py:22](../../_gh_work/SCS/device-sim/limit_switch/limit_switch_sim.py:22)、[position_sensor_sim.py:21](../../_gh_work/SCS/device-sim/position_sensor/position_sensor_sim.py:21) |
| 五类设备主桥接 | [device_state_bridge.py:79](../../_gh_work/SCS/device-sim/bridge/device_state_bridge.py:79)、113起雷达、148起区域、190起CLI |
| 协议验证器 | [verify_all.py:80](../../_gh_work/SCS/device-sim/verify/verify_all.py:80) |
| 人员实时覆盖与轨迹 | [PersonnelPositionService.java:89](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/module/personnel/service/PersonnelPositionService.java:89)、[PersonnelService.java:144](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/module/personnel/service/PersonnelService.java:144) |
| 基站/区域内存状态 | [UwbStationService.java:26](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/module/personnel/service/UwbStationService.java:26)、[ZoneStateService.java:68](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/module/zone/service/ZoneStateService.java:68) |
| 设备状态/雷达风险 | [CollisionService.java:268](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/module/collision/service/CollisionService.java:268)、同文件334起雷达，529起距离逻辑 |
| AI DTO/处理/持久化 | [AiRequests.java:45](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/module/ai/dto/AiRequests.java:45)、[AiBox.java:5](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/module/ai/model/AiBox.java:5)、[AiEventService.java:341](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/module/ai/service/AiEventService.java:341)、[JdbcAiEventRepository.java:321](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/module/ai/repository/JdbcAiEventRepository.java:321) |
| 模拟身份与seed | [DemoDeviceMasterData.java:42](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/support/masterdata/DemoDeviceMasterData.java:42)、[InMemoryPersonnelRepository.java:39](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/module/personnel/repository/InMemoryPersonnelRepository.java:39)、[DemoSeedInitializer.java:53](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/support/demo/DemoSeedInitializer.java:53) |
| 命名来源与批次保护 | [ScsMachineRegistry.java:22](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/config/ScsMachineRegistry.java:22)、[ScsMachineBodyAdvice.java:20](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/config/ScsMachineBodyAdvice.java:20)、[SecurityInterceptor.java:178](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/config/SecurityInterceptor.java:178) |
| 中央路由/事件授权 | [ScsCentralScope.java:97](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/config/ScsCentralScope.java:97)、[ScopedLiveEventPolicy.java:55](../../_gh_work/SCS/backend/src/main/java/com/bproject/safety/common/realtime/ScopedLiveEventPolicy.java:55) |
| 迁移说明/机器契约 | [v21 README](../../_gh_work/SCS/backend/db/v21/README.md)、[scoped-realtime-machine-contract.md](../../_gh_work/SCS/docs/scoped-realtime-machine-contract.md) |
| edge-ai原始帧/上报 | [main.py:120](../../_gh_work/SCS/edge-ai/main.py:120)、[scs_client.py:57](../../_gh_work/SCS/edge-ai/client/scs_client.py:57)、[synthetic_stream.py:32](../../_gh_work/SCS/edge-ai/simulator/synthetic_stream.py:32) |
| 页面来源/刷新/固定示例 | [前端与边缘AI核查记录](前端与边缘AI核查记录.md)，内含组件与行号索引 |

### 8.2 设备资料卡与参考来源的可信边界

资料卡01～04自述版本为2026-10-24 v0.1，05～08为2026-10-28 v0.1。本轮读取本地资料卡，**未重新联网逐页核验外部手册**；外链是现有资料卡引用，不代表现场选型/采购或协议认证。

| 类别 | 资料卡与参考 | 哪部分已是参考，哪部分仍为构造 |
|---|---|---|
| UWB | [资料卡01](../../_gh_work/SCS/device-sim/docs/01-UWB定位系统资料卡.md)；[FB-LS1000快速手册](https://store.west-hn.com/wp-content/uploads/2022/04/FB-LS1000%E5%AE%9A%E4%BD%8D%E5%BC%95%E6%93%8E%E7%B3%BB%E7%BB%9F%E5%BF%AB%E9%80%9F%E4%BD%BF%E7%94%A8%E6%89%8B%E5%86%8C.pdf)、[WOXU产品页](https://uwb.woxuwireless.com/Software) | UDP/TCP/HTTP输出形态参考；逐字段JSON未由公开厂家协议证明，手环下行也是构造 |
| 雷达 | [资料卡02](../../_gh_work/SCS/device-sim/docs/02-毫米波测距雷达资料卡.md)；[北醒TF03手册](https://www.benewake.com/uploadfiles/2025/12/20251216145457230.pdf) | 参考器件是激光测距，原厂形态RS485/RTU；TCP、速度/健康/配对扩展为项目联调构造，cm换算仍需实距标定 |
| AI盒 | [资料卡03](../../_gh_work/SCS/device-sim/docs/03-AI视频分析边缘盒资料卡.md)；[NVIDIA事件结构参考](https://docs.nvidia.com/metropolis/deepstream/9.1/sdk-api/structNvDsEventMsgMeta.html)、[nvmsgconv](https://docs.nvidia.com/deepstream/text/DS_plugin_gst-nvmsgconv) | 事件体系参考，不是选定整机的完整回调协议或原生schema认证；SCS适配未完成 |
| 声光 | [资料卡04](../../_gh_work/SCS/device-sim/docs/04-声光报警器资料卡.md)；[LA6-POE手册（经销商托管）](https://docs.rs-online.com/ff50/0900766b8167c578.pdf)、[PATLITE官方示例](https://github.com/PATLITE-Corporation/LA6-POE_windows_vb_example) | 参考灯控/蜂鸣思路；本项目黄灯bit2与卡片所引原厂Green不同，时长/防抖/回执是扩展，不是原厂点表 |
| 门机 | [资料卡05](../../_gh_work/SCS/device-sim/docs/05-门式起重机资料卡.md) | 无实物/具体厂家手册，PLC点表、范围、联锁与场景均为项目预案 |
| 翻箱机 | [资料卡06](../../_gh_work/SCS/device-sim/docs/06-翻箱机资料卡.md) | 无厂家点表，阶段、角度/压力/重量范围是模拟假设 |
| 限位 | [资料卡07](../../_gh_work/SCS/device-sim/docs/07-限位开关资料卡.md) | 干接点→PLC寄存器是假设；“压合=有人”须现场验证，未实现完整去抖/断线诊断 |
| 位置 | [资料卡08](../../_gh_work/SCS/device-sim/docs/08-位置传感器资料卡.md) | 10m量程、3m有车阈值及寄存器为构造；正式需按接口、安装角度、波束、失效值标定 |

实际设备交付后，应替换为厂家版本化协议/PLC点表，并记录具体页码与现场验证结论。本轮已更新device-sim README的类别、目录及验证范围。其他历史资料中的AI `--webhook-url`（实际 `--webhook`）、UWB `--backfill`（实际 `--scenario backfill`）等仍应以当前代码为准，未统一改写全部旧资料。

当前文档来自本地源码核对；未新跑现场设备、服务器写入、图片模型推理或 PLC 实际停机验收。历史 verify/evidence 文件只说明历史验证记录存在，不视为本次测试结果。源码修改后应重新核对字段、单位、默认参数、身份路由、对象归属和刷新方式，并更新本文版本日期及提交基准。

## 9. 本轮补齐的运行方式与验证

新增四项入口都支持`--once`无网络预览；完整通用字段/场景/来源和启动说明见[仓库设备总册](../../_gh_work/SCS/device-sim/docs/模拟设备类型与数据格式.md)，机器可读分类见[device-catalog.json](../../_gh_work/SCS/device-sim/docs/device-catalog.json)。

本轮设备回归共55项通过：UWB11、车辆11、摄像头/边缘16、PLC17。摄像头测试短暂创建受控的回环临时HTTP服务并关闭，其他测试不建立监听；没有向SCS或生产设备写入。四项CLI实际`--once`均生成可解析JSON；CI已改为自动发现`verify/test_*.py`，远端CI尚未运行。

本轮未修改SCS后端/前端契约；此前的状态修复仍在本地未提交工作区。没有提交、推送、部署或重启服务器。原有后端18项与前端158项测试记录见7.1，属于前一轮状态修复验证，本轮没有将它们重复计入55项设备测试。
