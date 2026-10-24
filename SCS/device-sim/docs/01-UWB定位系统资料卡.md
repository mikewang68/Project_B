# 【设备协议资料】EQ-EXT-03｜UWB 人员定位终端与基站｜敬昌隆

版本：v0.1，2026-10-24。调研负责人：敬昌隆（S1/S3/S4 协同，本卡一份覆盖标签/基站/手环，对应接口 IF-SRC-EXT-03、IF-IN-UWB-TAG、IF-IN-UWB-STATION、IF-OUT-WRISTBAND）。

## 1. 对应原表

- 设备范围 第 21 行（S1/S3/S4 协同）与第 37 行（S2 消费引用），设备编号 **EQ-EXT-03**，同编号合并调研。
- 接口：IF-SRC-EXT-03（设备→边缘网关）、IF-IN-UWB-TAG（手环/标签，UDP/TCP→JSON，1~5Hz+事件）、IF-IN-UWB-STATION（基站状态，1Hz）、IF-OUT-WRISTBAND（下行示警，BLE/UWB 广播）。
- 现场厂家：**待中标确认**。本卡为类似设备参考，不是现场已确认型号。

## 2. 设备身份与参考依据

| 项 | 内容 |
|---|---|
| 参考系统 | 沃旭（WOXU）FB-LS1000 UWB 定位引擎 + i3/i7 系列标签基站（类似设备替代，**未采购**） |
| 公开依据 | [FB-LS1000 快速使用手册](https://store.west-hn.com/wp-content/uploads/2022/04/FB-LS1000%E5%AE%9A%E4%BD%8D%E5%BC%95%E6%93%8E%E7%B3%BB%E7%BB%9F%E5%BF%AB%E9%80%9F%E4%BD%BF%E7%94%A8%E6%89%8B%E5%86%8C.pdf)：引擎提供 **UDP/TCP/HTTP API** 输出定位结果；[LS-1000 产品页](https://uwb.woxuwireless.com/Software) |
| 字段格式现状 | 公开手册确认"UDP JSON 输出"存在，但**逐字段 JSON 结构未公开**（通常向客户交付）→ 本卡字段集按 S3 清单 IF-IN-UWB-TAG 定义构造，**字段名待厂家协议证实** |

## 3. 连接与协议（模拟器口径，均可配置）

- 定位输出：引擎 → 网关方向，**UDP**（默认 127.0.0.1:9000，JSON 每包一帧）；频率 1~5Hz（默认 1Hz，S3 页面默认 3s 刷新兼容）。
- 基站状态：同一 UDP 通道 1Hz 状态帧 + 告警事件帧。
- 坐标系：站场统一坐标（米），与 S3 字段字典 x_m/y_m/z_m 一致；原点/轴向待现场坐标系文件确认。

## 4. 点位与报文

### 4.1 标签/心跳帧（IF-IN-UWB-TAG，1~5Hz）

```json
{"type":"tag","seq":123,"ts":"2026-10-24T21:00:00.123+08:00",
 "tagId":"WB-1003","personId":"P-1003",
 "x_m":61.0,"y_m":33.5,"z_m":1.2,"accuracy_m":0.18,
 "battery_pct":78,"sos":false,"tamper":false,
 "station_ids":["BS-01","BS-02","BS-03"]}
```

映射到 S3 字段：x_m/y_m/z_m（必填）、accuracy_m（float32，与实测误差相关，用于抑制低质量越界）、电量/防拆/SOS 对应清单"电量、防拆/SOS报警"点位。

### 4.2 基站状态帧（IF-IN-UWB-STATION，1Hz）

```json
{"type":"station","ts":"...","stationId":"BS-01","online":true,
 "clockSynced":true,"snrDb":32,"powerOk":true,"tagCount":6}
```

### 4.3 事件帧（实时）

```json
{"type":"event","ts":"...","tagId":"WB-1003","event":"sos"|"tamper"|"low_battery"|"geofence_entry","value":true,"fenceId":"FENCE-03"}
```

### 4.4 手环下行（IF-OUT-WRISTBAND）——**能力未证实，仅有草案**

真实链路为 BLE/UWB 下行广播（二进制帧）。模拟器提供 UDP 9001 下行注入端口记录触发并回执（震动/蜂鸣/屏显文字"已进入高危禁区，请立即撤离"），帧格式为我方构造草案（`AA 55 | cmd(1) | tagIdLen(1) | tagId | pattern(1) | duration(2, BE) | crc8`），**不是任何厂家已证实协议**；手环与定位标签是否同型号、是否具备下行能力，待厂家资料证实。

## 5. 时间与异常

- 每帧携带引擎源时间（ISO-8601 带时区）+ 递增 seq（幂等键组成：设备实例+点位+源时间+源序号）。
- 异常场景：标签离线（停止发帧，网关侧按新鲜度判 STALE）、低精度（accuracy_m 抬升至 >0.5 时 S3 应抑制越界事件）、低电量/防拆/SOS 事件帧。
- 断连语义：引擎缓存后按源时间补传（模拟器 `--backfill` 场景可演示），补传帧带 `"backfilled":true`。

## 6. 替代差异声明

参考设备仅覆盖"UWB 引擎 UDP JSON 输出"这一接口形态；现场厂家、标签具体型号（是否防拆/SOS/震动）、下行能力均未确认。**不能把本卡字段名当成厂家协议原文**；正式对接以厂家协议文档为准，届时仅替换字段映射层。

## 7. 模拟与联调

- 模拟器：`device-sim/uwb/uwb_sim.py`（纯标准库 Python 3.9+），启动/场景/验证见 `device-sim/README.md`。
- 场景种子：`patrol`（4 人巡逻含越界进入 FENCE-03）、`sos`（WB-1003 触发 SOS）、`degraded`（精度劣化+低电量）、`backfill`（断连补传）。
- 已覆盖：UDP JSON 输出、1~5Hz、事件帧、基站状态、下行注入回执。未覆盖：真实 BLE 广播、多基站 TDOA 解算、UWB 空口。
