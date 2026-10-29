# SCS 设备原始协议模拟器（B01~B03 敬昌隆负责范围）

六类设备的数据生成器 + 协议资料卡。对应[接口前置清单"设备范围"]及第十七次组会要求：

| 设备 | 调研定位键 | 原始协议形态 | 参考设备（类似替代，未采购） | 端口 |
|---|---|---|---|---|
| UWB 人员定位终端与基站 | EQ-EXT-03 | **UDP JSON**（引擎→网关，1~5Hz+事件） | 沃旭 FB-LS1000 引擎 | 9000（发）/9001（下行注入） |
| 毫米波测距/激光雷达 | IF-IN-RADAR | **Modbus TCP**（FC03/04/06，10~20Hz 轮询） | Benewake TF03-180（寄存器同源） | 15020 |
| AI 视频分析边缘盒 | IF-IN-AI-EVENT | **HTTP Webhook POST + JPEG 快照** | DeepStream 事件 schema | 15030 |
| 现场一体化声光报警器 | IF-OUT-ALARM-LIGHT | **Modbus TCP**（FC03/06/16 灯位/蜂鸣/回执） | PATLATE LA6-POE（寄存器风格） | 15040 |
| **门式起重机（龙门吊）** | 组会指定 | **Modbus TCP**（FC03/06，状态/速度/方向/载重/起升/风速/联锁） | 通用门式起重机 PLC，**寄存器全部我方构造** | 15050 |
| **液压翻箱机** | 组会指定 | **Modbus TCP**（FC03/06，作业阶段/举升角度/液压压力/联锁） | 液压翻转机 PLC，**寄存器全部我方构造** | 15060 |

全部**纯 Python 标准库零依赖**（AI 盒快照图有 PIL 时更精致，无则退化 1×1 JPEG）。

> **龙门吊与翻箱机没有实物、没有厂家手册**：资料卡 `docs/05`、`docs/06` 里的寄存器地址与换算
> 全部是我方构造的预案，**不得作为现场接线或 PLC 点表依据**；设备到位后必须按实际手册替换。

## 快速开始

```powershell
# 一键验证六链路（约 40 秒，产出 verify/evidence-*.json）
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
轮询龙门吊/翻箱机 Modbus 从站 → 换算成 SCS 设备状态字段 → `POST /api/v1/ingest/device-state`（X-API-Key）。

```powershell
$env:SCS_API_KEY = "<机器凭据>"   # 不要写进脚本
python bridge\device_state_bridge.py --scs http://127.0.0.1:20986 --api-key-env SCS_API_KEY
python bridge\device_state_bridge.py --scs http://127.0.0.1:20986 --api-key-env SCS_API_KEY --once
```

SCS 侧只更新已登记设备（`CRANE-01` / `TIP-02` 等）的展示字段并广播 `collision.changed`，
防碰撞页已订阅该事件，**无需刷新自动更新**。未知设备编码会被忽略，不会新建设备。

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
  uwb/uwb_sim.py           # UDP JSON 输出 + 下行注入回执
  radar/radar_sim.py       # Modbus TCP 寄存器（距离/速度/健康/配对编码）
  aibox/aibox_sim.py       # Webhook 推送 + JPEG 快照服务
  alarmlight/alarmlight_sim.py  # 灯位/蜂鸣/时长/防抖 + 回执区
  verify/verify_all.py     # 四链路本地验证 + 证据 JSON
```
