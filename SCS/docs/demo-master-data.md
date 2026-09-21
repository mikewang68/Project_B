# Demo 主数据口径（Users / Teams / Areas）

> **性质声明：本文件描述的是 Backend Demo Master Data，不是正式生产主数据。**
> 当前系统使用内存仓库（InMemory Repository），无数据库持久化。所有人员、班组、区域
> 均为演示种子数据；生产上线前必须与正式组织人员表、站场区域主数据逐项核验。
>
> 唯一权威来源：后端 `com.bproject.safety.support.masterdata.DemoMasterData`
> （`backend/src/main/java/com/bproject/safety/support/masterdata/DemoMasterData.java`）。
> `GET /api/v1/meta/dictionaries` 的 areas / teams / assignees 全部由它生成；
> 各业务模块 Seed 与前端筛选/派单只能引用本文件定义的 code / 规范名称，不得另写中文字符串。

## 1. Users（责任人 / 派单字典）

派单下拉（Alert 派单、AI 派单）唯一来源为 `/meta/dictionaries` 的 `assignees`，
两个弹窗看到的是同一份 7 人名单、同一顺序。

| userId | 姓名 | teamCode | teamName | 核验状态 |
| --- | --- | --- | --- | --- |
| USR-001 | 李娜 | SAFETY_MANAGEMENT | 安全管理组 | Demo 既有种子 |
| USR-002 | 王建国 | SAFETY_MANAGEMENT | 安全管理组 | Demo 既有种子 |
| USR-003 | 赵明 | LOADING_TEAM_1 | 装卸一班 | Demo 既有种子 |
| USR-004 | 陈静 | EQUIPMENT_MAINTENANCE | 设备维保班 | Demo 既有种子 |
| USR-005 | 刘志明 | LOADING_TEAM_2 | 装卸二班 | **demoUnverified=true，待权威人员主数据核验** |
| USR-006 | 陈晓 | SAFETY_MANAGEMENT | 安全管理组 | **demoUnverified=true，待权威人员主数据核验** |
| USR-007 | 周海 | EQUIPMENT_MAINTENANCE | 设备维保班 | **demoUnverified=true，待权威人员主数据核验** |

- USR-005～USR-007 的班组关系没有权威人员名单佐证，仅沿用早期 Demo Seed；
  接口中以 `demoUnverified: true` 显式标记，不得据此外推真实组织架构。
- 现场定位人员（P-* 开头，`InMemoryPersonnelRepository`，如赵磊、王强等）是另一套
  “现场作业人员”演示数据，与 USR-* 责任人不是同一实体，仅李娜同名。
- `/api/v1/auth/me` 返回的当前登录 Demo 用户默认 USR-001（可被环境变量覆盖，
  见 `DemoUserProperties`），与本主数据中的 USR-001 保持一致。

### 已知技术债（本阶段未改）

- Alert 派单 DTO（`AlertRequests.AssignRequest`）虽包含 `assigneeId/assigneeName`，
  但 `AlertService.assign` 当前仅以**姓名**落库，`assigneeId` 尚未持久化；
  AI 派单 DTO（`AiRequests.AiAssignRequest`）只有 `assignee` 姓名字段。
  前端已在 Alert 派单 payload 中同时透传 `assigneeId/assigneeName`，待正式建模时启用。
- 业务 Seed 中历史告警的 assignee 展示串仍含头衔（如“班长 刘志明”“安全员 李娜”），
  属历史记录展示文本；新建派单统一写入字典姓名。

## 2. Teams（班组）

| teamCode | canonicalName（规范名） | 历史别名（已清洗） |
| --- | --- | --- |
| LOADING_TEAM_1 | 装卸一班 | — |
| LOADING_TEAM_2 | 装卸二班 | — |
| SAFETY_MANAGEMENT | 安全管理组 | 安全管理班、安全管理（缩写） |
| EQUIPMENT_MAINTENANCE | 设备维保班 | 设备保障班、设备保障（缩写） |
| CONTRACTOR | 外协单位 | —（人员类别，非内部班组） |
| QUALITY_MANAGEMENT | 质量管理 | —（现场人员 Seed 中出现，保留为独立 Demo 班组） |

- `RiskClassifier.teamOf`（Analytics 班组聚合的唯一来源）原先输出别名
  “安全管理班 / 设备保障班”，已统一为 canonical 名称，Analytics 不再出现同一班组两个聚合桶。
- 人员 Seed（李娜“安全管理”、王强/吴昊“设备保障”）、围栏适用班组文本
  （“装卸一班、设备保障”等）已同步清洗。

## 3. Areas（作业区域）

| areaCode | canonicalName（规范名） | 历史别名（已清洗） | 备注 |
| --- | --- | --- | --- |
| LOADING_AREA_A | 装卸区 A | — | |
| LOADING_AREA_B | 装卸区 B | — | |
| GANTRY_CRANE_AREA | 龙门吊作业区 | — | |
| CONTAINER_TIPPER_AREA | 翻箱机区 | 翻箱机作业区 | 同义证据：摄像头 CAM-04 名称为“翻箱机区枪机”、EDGE-03 节点名为“翻箱机区” |
| VEHICLE_LANE | 车辆通道 | — | |
| TEMPORARY_WORK_AREA | 临时施工区域 | 临时施工区、临时围栏区 | 依据：ALM-011 证据位置为“临时施工围栏”，FENCE-003 为唯一临时围栏 |
| CONTAINER_BLOCK_A | 箱区 A | — | **UNRESOLVED**，见第 4 节 |
| CONTAINER_BLOCK_B | 箱区 B | — | **UNRESOLVED**，见第 4 节 |
| CONTAINER_LANE_C | 箱区通道 C | — | **UNRESOLVED**，见第 4 节 |
| MAINTENANCE_LANE | 维修通道 | — | **UNRESOLVED**，见第 4 节 |
| RAILWAY_LOADING_LINE_B | 铁路装卸线 B | — | **UNRESOLVED**，见第 4 节 |
| EQUIPMENT_ROOM | 机房 | — | 系统/边缘节点告警位置（设施位置，非作业区） |

已同步清洗的位置：Alert / AI（摄像头与事件）/ Personnel / Fence / Collision / Rule /
Ops 台账与边缘节点 Seed，以及前端地图标注、筛选常量与 mock 数据。

碰撞设备 `area` 原先为带通道后缀的展示串（“车辆通道 R-06”“装卸区 B 通道”），
已收敛为 canonical 区域名，保证碰撞生成告警时区域口径一致；通道编号等细粒度位置
信息在正式设备主数据中再以独立字段承载。

## 4. UNRESOLVED（未确认项，禁止猜测合并）

以下区域在没有权威站场图/区域主数据之前，**不与任何 canonical 区域合并**，
当前作为独立 Demo 字典项保留；生产前需现场资料确认：

| 待确认关系 | 当前处理 | 确认所需资料 |
| --- | --- | --- |
| 箱区 A / 箱区 B vs 装卸区 A / 装卸区 B | 保留为独立区域（CONTAINER_BLOCK_A/B），不合并 | 正式站场区域平面图、区域编码表 |
| 箱区通道 C vs 车辆通道 | 保留为独立区域（CONTAINER_LANE_C） | 场内道路/通道命名与归属表 |
| 维修通道 vs 车辆通道 | 保留为独立区域（MAINTENANCE_LANE） | 通道功能划分图 |
| 铁路装卸线 B | 保留为独立区域（RAILWAY_LOADING_LINE_B） | 铁路作业线与区域归属表 |
| FENCE-003 适用班组文本中的“检修班” | 保留原文，未并入设备维保班 | 正式班组编制表（检修班是否等同设备维保班无法从代码证据判定） |
| USR-005 刘志明 / USR-006 陈晓 / USR-007 周海 的班组 | 沿用 Demo Seed，标记 demoUnverified | 权威人员主数据（人力/组织架构系统） |

## 5. 前端消费约定

- 统一入口：Pinia `stores/dictionary.ts`（`ensureLoaded / refresh / getOptions /
  getOption / getLabel / hasDictionary`），应用启动加载一次，内存缓存，失败不阻断页面。
- 区域/班组 option 当前 `value` 为规范中文名（现有筛选 REST 按中文名精确匹配），
  稳定 `code` 随 option 携带；待后端筛选接口支持 code 后切换为 `value=code`。
- 责任人 option `value=userId`，展示“姓名 · 班组”，班组名来自用户字典元数据，
  前端不得按姓名/ID 反推班组。
- 未知 code/名称：`getLabel` 返回原值并 `console.warn`，不做相似中文替换。
- 派单弹窗在字典加载失败时显示“人员字典加载失败 + 重试”，**不再有任何本地名单 fallback**。

## 6. 一致性保障

- 后端测试：`MasterDataConsistencyTest` 校验用户唯一性、班组/区域 code 自洽，
  以及 Alert / AI / Personnel / Fence / Collision / Rule / Ops 全部 Seed 的
  area / team 必须落在本主数据集合内（规则“全部区域”通配除外）。
- 这同时覆盖三条运行时建单链路：AI→Alert（取 AI 事件 area）、
  人员越界→Alert（取围栏 area）、碰撞→Alert（取设备 area）。
