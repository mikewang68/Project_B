# SCS 设备原始协议模拟器（B01~B03 敬昌隆负责范围）

四类设备的数据生成器 + 协议资料卡。对应[接口前置清单"设备范围"]中调研负责人=敬昌隆的 4 个对象：

| 设备 | 调研定位键 | 原始协议形态 | 参考设备（类似替代，未采购） | 端口 |
|---|---|---|---|---|
| UWB 人员定位终端与基站 | EQ-EXT-03 | **UDP JSON**（引擎→网关，1~5Hz+事件） | 沃旭 FB-LS1000 引擎 | 9000（发）/9001（下行注入） |
| 毫米波测距/激光雷达 | IF-IN-RADAR | **Modbus TCP**（FC03/04/06，10~20Hz 轮询） | Benewake TF03-180（寄存器同源） | 15020 |
| AI 视频分析边缘盒 | IF-IN-AI-EVENT | **HTTP Webhook POST + JPEG 快照** | DeepStream 事件 schema | 15030 |
| 现场一体化声光报警器 | IF-OUT-ALARM-LIGHT | **Modbus TCP**（FC03/06/16 灯位/蜂鸣/回执） | PATLATE LA6-POE（寄存器风格） | 15040 |

全部**纯 Python 标准库零依赖**（AI 盒快照图有 PIL 时更精致，无则退化 1×1 JPEG）。

## 快速开始

```powershell
# 一键验证四链路（约 25 秒，产出 verify/evidence-*.json）
cd device-sim
python verify\verify_all.py

# 单独长跑（Ctrl+C 退出）
python uwb\uwb_sim.py --dest 127.0.0.1:9000 --scenario patrol --rate 2
python radar\radar_sim.py --port 15020 --scenario approach
python aibox\aibox_sim.py --webhook http://127.0.0.1:15031/hook --scenario mixed
python alarmlight\alarmlight_sim.py --port 15040
```

## 场景种子

| 模拟器 | 场景 | 语义 |
|---|---|---|
| UWB | `patrol` / `sos` / `degraded` / `backfill` | 4 人巡逻（含越界 FENCE-03 事件）/ SOS 事件 / 精度劣化+低电 / 断连补传（backfilled 标志） |
| 雷达 | `approach` / `recede` / `static` / `blind` / `fault` | 20→3m 接近（+0.5m/s）/ 远离 / 8m 恒定 / 超量程（强度<40→DEGRADED）/ RADAR_DOWN |
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
