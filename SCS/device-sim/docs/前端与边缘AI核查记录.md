# SCS 前端数据来源与边缘 AI 核查记录

日期：2026-11-05。源码基准：`39550d8d5cd1c50125947655bb9d65c001e54409`；另核对 2026-11-05 本地未提交的 UWB/区域状态修复，尚未部署。相对路径均以 `_gh_work/SCS/` 为起点。

本文为源码审查记录，说明当前行为和已发现的限制；未启动模拟器、未上传图片、未写入服务器或数据库，也未据此确认现场设备在线数量。

## 一、前端读取与更新方式

| 页面/功能 | 读取来源 | 更新触发 | 说明 |
|---|---|---|---|
| 人员定位列表 | GET `/api/v1/personnel` | 首次加载；`person.*` 事件；配置驱动的轮询 | 初始周期 1000ms；读取 `/personnel/config` 成功后重设定时器。初始化读取配置失败时，代码保留数值 1000，但不会额外启动该轮询定时器。列表请求有互斥及补拉控制 |
| 人员定位刷新配置 | GET/PUT `/api/v1/personnel/config` | 用户修改；`person.config.changed` / `person.config_changed` | 这是页面拉取频率；不是 UWB 标签的采样或发送频率 |
| UWB 基站健康 | GET `/api/v1/personnel/uwb-stations` | 首次加载，之后固定每 5000ms | 独立于人员列表刷新周期；请求不重叠，失败清空健康结果，卸载后停止轮询并忽略迟到响应 |
| 首页地图 | GET `/api/v1/overview/map` | 首次；`person.*` 合并 300ms；`alert.*` / `ai.*` 的全量刷新；重连 | 人员坐标和设备坐标来自后端投影。首页未单独订阅 `collision.*` 更新地图，只有设备上报时不保证立即刷新 |
| 首页统计/告警/趋势 | `/overview` 下对应聚合接口 | 首次；`alert.*` / `ai.*` 合并 300ms；重连 | 聚合接口和示例展示可能混合存在，应看返回的 demo 标识 |
| 装卸区 A/B 状态 | GET `/api/v1/zones` | 首次；`zone.*` 合并 300ms；重连 | 无上报显示未接入；人员/车辆信号为 null 时 A/B 均显示状态未知；故障报文同时清空 distanceMm 后不显示旧距离；false 显示空闲/无车。请求失败保留上一帧；页面没有独立的定时过期刷新 |
| 防碰撞 | `/collision/devices`、`/collision/pair`、`/collision/devices/{id}/distance-trend` | 首次；用户切换；`collision.*` 合并 250ms | 当前页面没有独立周期轮询及显式重连后刷新回调。模拟接近等按钮调用后端模拟接口，与外部设备采集是两条链路 |
| AI 事件 | `/ai-events` 等 | 首次；`ai.*`；重连；写操作后重拉 | AI 边缘在线状态单独每 15s 拉取 `/ai-events/edge-status` |
| 运维设备/边缘台账 | `/ops`、`/edge` 接口 | 首次；`ops.*` 合并 300ms；操作后加载 | 代码注释明确属于 Backend Demo / simulated edge autonomy；不能因字段名是 heartbeat/cpu 就认定来自真实探针 |
| 大屏地图 | GET `/overview/map` | 首次；识别出的业务事件合并 260ms；风险展示变化 | 大屏地图和首页地图订阅范围不同 |
| 大屏/移动端告警聚合 | 告警、screen overview、mobile home REST | 首次；业务事件合并 280ms；重连 | REST 为读取权威来源，WebSocket 负责通知 |

WebSocket 为全局单例 `/ws/live`，20s 心跳；断线按约 1s 起、30s 上限加抖动重连。中央授权用户只有允许 scopedRealtime 时启用相应实时通道，授权版本变化会清理旧状态并重新载入；不能绕过 REST 授权直接把 WebSocket 当设备网关。

依据：`frontend/src/views/PersonnelLocationView.vue:47`、`:95`、`:118`、`:207`；`components/overview/SafetyOverview.vue:69`、`:150`；`components/overview/LoadingAreaPanel.vue:20`；`views/CollisionOverviewView.vue:81`、`:251`；`views/AiReviewView.vue:248`、`:301`；`views/OperationsView.vue:55`；`components/screen/BigScreenMap.vue:31`；`stores/incident.ts:60`；`api/live.ts:22`；`App.vue:21`。

## 二、页面中不可当作实际采集结果的内容

1. **首页“设备健康”仍直接使用固定示例。** `DeviceHealth.vue` 默认定位基站 12/12、摄像头 22/24、雷达 8/8、网关 4/4；首页以 `<DeviceHealth />` 引用，没有传入真实健康列表。组件标注 demo，但它不等于人员页的 UWB 健康接口。
2. **防碰撞页顶部在线设备 36/38、雷达 8/8 等为固定展示。** `CollisionOverviewView.vue:55`，仅某些前端模拟状态会改其中的文字；不是对当前 devices 列表实时计数。
3. **大屏健康列表有示例回退。** `BigScreenDeviceHealth.vue:15` 优先取 overview.deviceHealth，缺失时回退固定 12/12、22/24、8/8、4/4。不能据此宣称网络失败时设备仍在线。
4. **运维展示含固定说明。** 例如边缘节点分母 4、部分摄像头/雷达描述在页面直接编写，需与后端台账来源分开理解。
5. **README 中“后端不可用时切换内置演示快照”与当前旧 store 的行为不一致。** `stores/safety.ts:7`、`:37` 在失败时构造空快照，`api/http.ts` 抛出接口错误；不应沿用该说法解释当前采集链路。

依据：`components/overview/DeviceHealth.vue:7`；`components/overview/SafetyOverview.vue:188`；`views/CollisionOverviewView.vue:55`；`components/screen/BigScreenDeviceHealth.vue:15`；`views/OperationsView.vue:127`；`README.md:65`；`stores/safety.ts:7`、`:37`；`api/http.ts:70`。

## 三、两种 AI 模拟来源必须区分

### 3.1 device-sim 下的 AI 盒协议模拟

它是设备协议层的模拟脚本，具体原始事件字段和桥接状态见主数据格式文档。不能把该脚本产生的事件直接视为边缘 YOLO 图片推理输出。

### 3.2 edge-ai 服务

`edge-ai` 是独立的 FastAPI 图像检测与合成场景服务，通过 `client/scs_client.py` 转换为 SCS `/api/v1/ai-events/ingest` 数据：

- `/api/v1/simulate/trigger` 的 JSON 使用 snake_case：`scenario`、`camera_code`、`report_to_scs`、`custom_person_name`。默认 `no_helmet`、`CAM-01`、true、示例作业人员。
- 场景枚举约定为 `no_helmet`、`no_vest`、`intrusion`、`compliant`；字段本身为普通 str，并非严格枚举校验。
- 优先读取场景 jpg 模板，没有模板时程序绘图；默认 1280×720。
- 运行检测后，如未识别出场景预期违规，会根据模拟场景真值补入违规和固定置信度（例如 96.8、94.5、98.0）。所以模拟场景的成功不等于模型在真实视频上达到该准确率。
- `/api/v1/detect/image` 接收 multipart 图片，是另一条路径；当前代码没有完整的真实 RTSP/ONVIF 摄像头持续采集实现。
- `/api/v1/stream/start?interval_sec=10` 默认 10s 等待间隔轮换四种合成场景。每轮耗时还包含推理、上报时间，并非固定 10s 采样保证；前端“每5秒分析1帧”的按钮通过后端传递自己的周期。
- 检测框给前端的是 0～100 的百分比 x/y/w/h；原始图片像素、0～1 危险区多边形、百分比框是三种不同坐标体系。
- SCS 事件侧 confidence、threshold 使用百分数；模型内部置信度常使用 0～1，须在转换边界明确。
- 客户端只发送 `X-API-Key`，不发送 `X-SCS-Source-ID`；默认幂等键按摄像头、事件类型、规则和 10s 时间桶组合，同桶内相同组合会共用键。
- 上报异常被模拟接口记录后继续返回，`status: SUCCESS` 本身不能证明入库成功；应检查非空 `scs_reports` 及对应 SCS 事件。

更新方式：环境变量在服务进程导入时读取（如 `SCS_BACKEND_URL`、`SCS_API_KEY`、`AI_STORAGE_BASE`、`AI_CONF_THRESHOLD` 等）；变更后需重启该边缘进程。摄像头/危险区/模型路径位于 `edge-ai/config.py`；模型对象会缓存，修改权重或配置后不应假定已热加载。模板按场景在生成时寻找，但没有统一的版本化热更新 API。不要通过导入 config 做文档验证，因为其顶层代码会创建目录。

依据：`edge-ai/main.py:120`、`:145`、`:229`、`:261`、`:364`、`:398`；`edge-ai/simulator/synthetic_stream.py:14`、`:19`、`:32`；`edge-ai/client/scs_client.py:19`、`:51`、`:57`；`edge-ai/config.py:33`、`:47`、`:63`。

## 四、已确认需要单独处理的接入缺口

### F-01：图片上传检测路径存在字段不匹配，可能在检测到人员后报错

`PPEDetector.detect()` 输出 PERSON 框时提供 `score`，不提供 `confidence`（`edge-ai/detector/ppe_detector.py:98`）。`detect_image()` 却对带 tone 的框直接读取 `b['confidence']`（`edge-ai/main.py:175`），因此检测到 PERSON 框时可能抛出 KeyError，无法完成后续入侵判断和上报。

即便补齐该字段，当前调用还把已经转成 0～100 百分比的 x/y/w/h 直接交给期望像素坐标的 `detect_intrusions()`；后者再次除以图片宽高（`detector/intrusion_detector.py:101`）。该坐标契约也必须同时修正。

验证方式：静态对照生产者字段、消费者读取及坐标运算，未加载模型或向服务上传图片。结论针对图片上传路径；不能据此声称合成场景路径必然报同样错误。

### F-02：实时展示与设备健康统计尚未完全贯通

上文固定设备计数、健康卡片示例回退、首页不订阅 collision.* 的事实，说明“设备已上报”不等于所有页面立即呈现一致的设备在线统计。后续应按页面核对来源和过期策略。

### F-03：旧演示脚本不能直接作为当前受保护接口的验收工具

`scripts/test_ingest.py` 不发送 API key；`scripts/trigger_sim.py` 会创建事件、确认、派单，却没有当前会话和 CSRF 处理。它们是历史辅助脚本，不能直接运行来证明当前版本链路正常。本次未运行。

### F-04：危险区按钮场景名没有转换

前端触发边缘推理的场景包含 `danger_zone`，而边缘合成器以 `intrusion` 区分入侵场景。后端 `AiEventService.java:690` 起将 scenario 原样转发，没有做这两个名称的映射。可能返回检测成功却没有运行所选的危险区场景；后端请求异常还会降级到内部 new 模拟事件。

依据：`frontend/src/api/aiEvents.ts:85` 及前端场景类型、`backend/src/main/java/com/bproject/safety/module/ai/service/AiEventService.java:690`、`edge-ai/simulator/synthetic_stream.py:54`。该结论来自源码字段追踪，未向服务器触发场景。

## 五、本次验证边界

以上结论来自当前源码和静态字段/订阅关系核查。没有验证实际模型权重、设备网络、现场寄存器、摄像头协议、服务器机器来源登记或真实 PLC 动作。模拟的“停机已确认”与现场执行器的可靠停机回执是不同证据。

## 六、补充设备模拟与页面接入边界

本轮新增转运车辆、摄像头视频源、边缘节点/网关遥测、PLC命令回执。车辆仅在显式配置SCS地址及凭据后通过现有device-state更新展示状态；不更新雷达距离或创建告警。摄像头的HTTP JPEG/MJPEG、边缘health及PLC Modbus回执是独立协议替身，没有新增SCS写入路由，故原有摄像头/运维/联动页面不会自动改为读取它们。摄像头模拟没有PPE推理；既有edge-ai字段和坐标问题仍未修改。
