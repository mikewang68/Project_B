# S5 装卸区数字孪生可视化系统（DTS V6）× S2 / S3 / S4 真实数据直连接口对接规范

> **版本**：V6.0（Contract v1.0 · 2026-11-21）
> **维护系统**：S5 数字孪生可视化系统（DTS）
> **对接对象**：S2 仓储管理系统（WMS · 周绪敬）、S3 设备健康与维护系统（EHMS · 闫鑫钰）、S4 安全监控与联锁系统（SCS · 敬昌隆）
> **安全与架构红线**：
> 1. **严格只监不控**：S5 仅发起只读 `GET` 请求；S5 对外所有写方法（`POST`/`PUT`/`PATCH`/`DELETE`）一律拦截返回 `HTTP 405 READONLY_SYSTEM`。
> 2. **零改三维代码直连**：当 S2 / S3 / S4 按本规范输出真实数据后，S5 后端只读适配网关（`dts_readonly_server.py`）自动识别并切换数据溯源标记为 `LIVE_S2_WMS` / `LIVE_S3_EHMS` / `LIVE_S4_SCS`，三维场景立即实时驱动，**无需修改 S5 前端三维渲染代码**。
> 3. **三区域严格隔离**：`SHEN_TIE`（沈铁联调基线）、`XIZANG_TIE`（青藏铁路局）、`WUHAN_TIE`（武汉铁路局）严格按 `regionId` 隔离，绝不跨区混用测点或告警。

---

## 一、标准编码字典（对齐权威施工图《给大连大.dwg》）

| 对象类别 | 标准编码范围 | 别名自动兼容（S5 网关内置归一化） | 数量 |
| :--- | :--- | :--- | :---: |
| **水泥筒仓（3000T）** | `SILO-01` ~ `SILO-08` | `A01`~`A08`、`C01`~`C08` | 8 |
| **粉煤灰筒仓（3000T）** | `SILO-09` ~ `SILO-40` | `B01`~`E08`、`F01`~`F32` | 32 |
| **钢筋仓库跨位** | `REBAR-BAY-01` ~ `REBAR-BAY-13` | `STEEL-BAY-01`~`13`、`BAY-01`~`13`、`01跨`~`13跨` | 13 |
| **钢材/散料门吊（40t）** | `CR-01`~`CR-03`、`CR-BULK-01`~`03` | `EQ-CRANE-01`~`06` | 6 |
| **室内桥吊（20t/32t/50t）** | `BC-01`~`BC-13`、`BC-MECH-01`~`02` | `EQ-BRIDGE-01`~`13`、`EQ-BRIDGE-MECH-01`~`02` | 15 |
| **移动式翻箱机** | `TIPPER-01` ~ `TIPPER-04` | `EQ-CONVEYOR-01`~`04` | 4 |
| **斗式提升机 / 散装机** | `ELV-01`~`04`、`LOADER-01`~`04` | `EQ-ELEVATOR-01`~`04`、`EQ-VALVE-01`~`04` | 8 |
| **汽车衡 / 轨道衡（100t）** | `WB-TRUCK-01`、`WB-TRUCK-02`、`WB-RAIL-01` | `EQ-SCALE-01`~`02`、`EQ-SCALE-RAIL-01` | 3 |
| **安防监控摄像机** | `CAM-01` ~ `CAM-06` | 按 `cameraId` 或 `id` 精确匹配 | 6 |
| **立体电子围栏区** | `FENCE-REBAR-HANDOVER`、`FENCE-TIPPER-ZONE`、`FENCE-SILO-LOADING`、`FENCE-EAST-GATE` | 按 `fenceId` / `zoneCode` / `area` 关键词匹配 | 4 |

---

## 二、S2 仓储管理系统（WMS · 协作 X08）直连接口规范

### 2.1 S5 网关读取路径（环境变量可配）
- 默认读取：`GET ${WMS_BASE_URL}/api/v1/inventory/balances?page=1&pageSize=200`（环境变量 `WMS_BALANCES_API_PATH`）
- 预留专口：`GET ${WMS_BASE_URL}/api/v1/silos/levels`（环境变量 `WMS_SILO_API_PATH`，如 S2 提供筒仓雷达料位专口将自动合并）

### 2.2 S2 推荐返回 JSON 结构（支持数组或 `{ "data": { "items": [...] } }`）
```json
{
  "data": {
    "items": [
      {
        "locationCode": "SILO-01",
        "materialName": "P·O 42.5 散装水泥",
        "batchNo": "LOT-20261121-A01",
        "availableTon": 2640.5,
        "capacityTon": 3000.0,
        "levelMeters": 27.7,
        "highLevelWarning": true,
        "updatedAt": "2026-11-21 10:15:00"
      },
      {
        "locationCode": "REBAR-BAY-07",
        "materialName": "HRB400E 螺纹钢",
        "batchNo": "LOT-20261121-B07",
        "availableTon": 1580.0,
        "capacityTon": 1800.0,
        "stackLayers": 5,
        "highLevelWarning": true,
        "updatedAt": "2026-11-21 10:15:00"
      }
    ]
  }
}
```
> **字段容错**：吨数字段自动兼容 `availableTon` / `availableQty` / `quantity` / `weightTons`；未传 `levelMeters` 或 `stackLayers` 时 S5 按 `availableTon / capacityTon` 自动换算。

### 2.3 S5 聚合输出与三维表现（`GET /api/v1/dts/storage?regionId=SHEN_TIE`）
- 稳定输出 **53 个全量库位**（40 座筒仓 + 13 跨钢筋库），每项含 `fillRatio`（`0~1`）、`dataSource`（`LIVE_S2_WMS` | `FALLBACK_SIM` | `REGION_ISOLATED`）。
- 三维效果：勾选左侧 **`筒仓料位剖视 & 钢筋跨库存体 (S2·X08)`** 后，40 座筒仓内部动态升降彩色料柱（≥85% 高料位红闪），13 跨钢筋库按实时库存渲染动态库容包络体块。

---

## 三、S3 设备健康与维护系统（EHMS · 协作 X03 / X09）直连接口规范

### 3.1 S5 网关读取路径
- `GET ${EHMS_BASE_URL}/api/ehm/v1/devices?projectId=PROJECT_B`
- `GET ${EHMS_BASE_URL}/api/ehm/v1/alarms?projectId=PROJECT_B`

### 3.2 S3 推荐返回 JSON 结构（支持三级设备匹配）
S5 网关按 **`deviceId` → `assetCode` (`EQ-*`) → 兼容旧 `code` (`MQ-SIM-*`)** 三级优先级直连：
```json
[
  {
    "deviceId": "CR-02",
    "assetCode": "EQ-CRANE-02",
    "healthScore": 72,
    "riskClass": "attention",
    "riskSubsystem": "起升机构钢丝绳与制动器",
    "condition": "偏磨关注",
    "risk": "钢丝绳断丝率趋近预警阈值",
    "predictedRulDays": 28,
    "sensorMetrics": {
      "motorCurrentA": 184.5,
      "vibrationRms": 4.2,
      "bearingTempC": 68.5
    }
  }
]
```
### 3.3 S5 聚合输出与三维表现（`GET /api/v1/dts/devices?regionId=SHEN_TIE`）
- 扩展返回 `ehms_asset_code`、`health_level`（`healthy`/`attention`/`risk`/`critical`）、`active_alarm_count`、`latest_alarm_no`、`sensor_metrics`、`data_source`（`LIVE_S3_EHMS`）。
- 三维效果：勾选 **`设备健康态势光环 (S3·X03)`** 后，36 台设备同步渲染底座/顶部健康状态环，故障/高风险设备自动呈现红色脉冲光柱并与告警列表双向定位。

---

## 四、S4 安全监控与联锁系统（SCS · 协作 X05）直连接口规范

### 4.1 S5 网关读取路径（新增聚合端点 `GET /api/v1/dts/safety?regionId=...`）
- 电子围栏：`GET ${SCS_BASE_URL}/api/v1/fences`
- AI 违规事件：`GET ${SCS_BASE_URL}/api/v1/ai-events`
- 摄像机与视频流（预留）：`GET ${SCS_BASE_URL}/api/v1/cameras`（环境变量 `SCS_CAMERAS_API_PATH`）
- 吊机防碰撞测距（预留）：`GET ${SCS_BASE_URL}/api/v1/interlocks`（环境变量 `SCS_INTERLOCKS_API_PATH`）

### 4.2 S4 推荐返回 JSON 结构
1. **电子围栏（`/api/v1/fences`）**：
```json
{
  "list": [
    {
      "fenceId": "FENCE-REBAR-HANDOVER",
      "name": "钢筋库 07 跨交接区防撞电子围栏",
      "area": "钢筋库07跨交接区",
      "statusCode": "BREACHED",
      "bufferDistance": 15.0,
      "breachTarget": "未授权人员闯入",
      "polygonCad": [[3716, 1656], [3786, 1656], [3786, 1676], [3716, 1676]]
    }
  ]
}
```
> 若 S4 暂不返回 `polygonCad`，S5 自动沿用《给大连大.dwg》实测 4 处标准围栏坐标轮廓；`statusCode` 自动归一化为 `ARMED` / `WARN` / `BREACHED` / `BYPASSED`。

2. **监控摄像机（`/api/v1/cameras`）**：
```json
{
  "list": [
    {
      "cameraId": "CAM-04",
      "name": "钢筋库 07 跨装车通道高清监控",
      "status": "ONLINE",
      "streamProtocol": "HLS",
      "streamUrl": "https://scs.bproject.de5.net/streams/cam-04/index.m3u8"
    }
  ]
}
```
> 前端视频核查弹窗原生支持 `HLS` / `MP4` / `MJPEG` / `SNAPSHOT` / `IFRAME` / `UNBOUND`（未接入流地址时自动显示最新 AI 识别事件与一键跳 S4 入口）。

3. **防碰撞测距对（`/api/v1/interlocks`）**：
```json
{
  "list": [
    {
      "pairId": "ILK-CR02-BC07",
      "deviceA": "CR-02",
      "deviceB": "BC-07",
      "distanceM": 11.4,
      "warnThresholdM": 15.0,
      "stopThresholdM": 6.0,
      "state": "WARN"
    }
  ]
}
```

---

## 五、联调验收快速自检命令

上线后，各系统负责人可用以下命令核验数据已真实接通（重点检查 `dataSource` / `data_source` 是否从 `FALLBACK_SIM` 变为 `LIVE_S2_WMS` / `LIVE_S3_EHMS` / `LIVE_S4_SCS`）：

```bash
# 1. 验证只读健康与各上游连通计数
curl -s "https://bproject.de5.net/api/v1/dts/health?regionId=SHEN_TIE"

# 2. 验证 S2 全量 53 库位实测接入数 (liveMatchedCount > 0)
curl -s "https://bproject.de5.net/api/v1/dts/storage?regionId=SHEN_TIE"

# 3. 验证 S3 36 台设备实测健康接入 (data_source == LIVE_S3_EHMS)
curl -s "https://bproject.de5.net/api/v1/dts/devices?regionId=SHEN_TIE"

# 4. 验证 S4 电子围栏、摄像机流地址与防碰撞测距
curl -s "https://bproject.de5.net/api/v1/dts/safety?regionId=SHEN_TIE"

# 5. 验证只监不控红线 (必须返回 HTTP 405 READONLY_SYSTEM)
curl -i -X POST "https://bproject.de5.net/api/v1/dts/safety"
```
