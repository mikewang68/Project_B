# WMS 设备模拟工具：Android 当前入口与旧 Python 工具归档

## 当前使用 Android 设备模拟终端

2026-10-27 更新：当前交付入口是 **Android C 设备模拟终端0.5.0**，不再使用服务器上的旧Python网页终端发送数据。

- [Android源码、使用和编译说明](android-terminal/README.md)
- [Android 0.5.0安装包](android-terminal/releases/0.5.0/WMS-Android-C-Terminal-0.5.0.apk)
- [安装包SHA256](android-terminal/releases/0.5.0/SHA256SUMS.txt)

Android终端支持摄像头二维码、扫码队列、称重、工艺测点及EE600/VEGA只读协议参考。
扫码只产生设备模拟事件，不会直接增加服务器库存；真实网关、公共底座与WMS业务写入仍须按约定单独联调。

**旧网页终端已按维护计划退役：停止并禁止 `project-b-wms-simulator` 启动，旧 `/simulator/` 入口关闭。**
不再通过旧终端自动采集、建单或确认收货；已有入库单、库存、标签、日志及SQLite队列全部保留，未清库。
不要依据下文历史命令重新启用现网发送。Python核心及参考工具仍保留用于Android本地演练和历史核对，
不能直接删除 `simulator.py`，因为Android参考桥接与本地单测仍依赖它。

## 以下为旧 Python 终端的历史使用说明

下文保留2026-10-19/22批次的功能、测试和数据语义，不代表当前可用的服务器入口。

当前只做**入库收货**：1～3 台虚拟终端，支持多物料选择、扫码计数及散料称重；人工确认收货，或显式开启定时自动确认。包含重复读取、未知标签、无法读取、断网补传、结果不确定核对等场景。

**2026-10-19 已按所有者明确授权，在实验室真实 WMS 验收六类物料落库。** 默认启动仍为本地演练，不会自动连接服务器。服务器路径、有限计划结果、启停、权限、备份和新建批次流程见 [实验室部署记录](deploy/LAB_DEPLOYMENT.md)。

Python 3.11+，仅使用标准库，无第三方依赖。没有 RFID 或地磅硬件，`SIM-*` 是内部模拟标识，不是已验证的 GS1 EPC 编码。尚未实现出库、移库、盘点差异审批和标签损坏补标完整生命周期。

## 历史：2026-10-22 服务器网页使用方式（已停用）

登录原 WMS → **入库管理 → 模拟手持终端**，在新标签页操作。相对入口 `/simulator/`，不需要在自己电脑启动 Python。正式证书名称入口为 `https://bpoc-node6:18444/simulator/`，需信任实验室 CA 并正确解析该名称；Tailscale 转发仍为 `100.65.200.125:18444`，直接使用 IP 可能证书名称不匹配。

- 新建有限任务：六类已批准 SIM 物料，计数 1–100 个单位，称重 1–3 批（毛重、皮重同单位，最多三位小数）。创建真实 WMS 入库单，但尚不增加库存。
- 人工演示：选择任务 → 扫码 → 散料填写毛重/皮重 → 确认排队收货。重复、未知、读失败及模拟断网均可验证。
- 自动发送：打开“定时采集”和“同时自动确认”，每 10 秒处理当前有限计划，完成后停止产生新数据。切换任务或服务重启会关闭自动开关。离开页面不等于停止后台作业；结束演示请关闭开关。
- 每次网页 API 请求都向原 WMS 核验当前登录用户、入库权限和固定公司/仓库/货主。只读用户不能操作；无登录/过期返回 401，CSRF/权限不足返回 403；不会自动换用服务账号绕过用户认证。创建单据还需要原生 `master:read` 权限以核验物料。
- 创建订单使用当前用户会话；已确认队列由专用最小权限模拟账号收货。SQLite 保存队列、原始采集、固定请求编号及 READY 计划，最终收货/库存写入 node4 openGauss `mt_wms_zxj/public`。这不是浏览器假库存，也不是完整 RFID 标签生命周期。
- 多人共享当前任务和自动开关，旧页面任务编号不符会被 409 拒绝。创建响应不确定只按原请求编号查询已有单，不盲目重复创建。刷新会丢失尚未确认的网页输入；不确定时保留页面显示的编号并联系维护者，不能新建编号冒充重试。

维护、重建前端、Nginx 路由、权限边界和回退见 [网页部署说明](deploy/WEB_DEPLOYMENT.md)。默认本地演练与服务器队列仍严格分离。

## 1. 默认本地演练

Windows 双击 `start-demo.cmd`，或在本目录运行：

```powershell
py -3 -X utf8 simulator.py serve --open-browser
```

Linux：

```bash
python3 simulator.py serve
```

本机打开 `http://127.0.0.1:18765/`。只监听回环地址，不是服务器共享页面。通过 `--port` 可改变本地端口。

**默认配置 `mode=demo` 不访问实验服务器。** 扫描、称重、待发送队列、演练接收端标签与收货流水真实保存在 SQLite，页面从 Python 后端读取，不依赖浏览器 localStorage。第一任务位于 `runtime/handheld.sqlite3`，其他任务分别位于 `runtime/tasks/<任务标识>/handheld.sqlite3`。它们不是原生 WMS 或 openGauss，不会让正式 WMS 库存自动增加。

### 支持的物料与计量

页面顶部选择物料 / 入库单。示例预置六个独立任务：

| 物料 | 单位 | 采集方式 | 预设规模 |
|---|---|---|---|
| 螺纹钢 | 捆 | 一标签一捆 | 10 捆 |
| 袋装粉煤灰 | 袋 | 一标签一袋 | 6 袋 |
| 袋装水泥 | 袋 | 一标签一袋 | 6 袋 |
| 机械配件 | 件 | 一标签一件 | 6 件 |
| 散装粉煤灰 | 吨 | 批次标签 + 毛重 / 皮重 | 3 批，预设净重合计 59.9 吨 |
| 散装砂石 | 吨 | 批次标签 + 毛重 / 皮重 | 3 批，预设净重合计 67.025 吨 |

计数模式 `measurement_mode=count`（不填时默认 count），支持捆、根、袋、件、箱、个、桶、托；**每标签恰好一个对应单位**。托盘含多袋、多箱含多件、单位换算尚未实现，不能把“一托”默认为“一袋”。

称重模式 `measurement_mode=weight`，支持吨、千克，毛重和皮重必须使用同一单位。先读取批次标签，录入毛重与皮重，点击“保存本批次称重”，再确认收货。毛重必须大于皮重，皮重可为零，数值最多三位小数。净重由后端 Decimal 计算；未称重批次不可确认，已保存称重不可直接覆盖。页面“预设参考净重”只是样例称重合计，手工测量可不同；真实入库单的剩余数量由测试 WMS 再校验。

扫描和称重本身均不增加库存。不同任务独立保存数量、队列、标签和单位，页面不跨单位求总和。切换任务会关闭自动采集和自动确认；已排队收货仍在后台处理，断网开关对所有任务队列生效。

### 五分钟演示

1. 选择 `SIM-PDA-01`、`SIM-STEEL-B001`，点击“模拟读取标签”：有效扫描变成 1，已收仍为 0。
2. 换另一台设备再读同一标签：显示重复读取，但有效扫描仍为 1。
3. 再扫描不同的标签；点击“确认当前有效扫描并排队收货”：后台接收后显示 `DELIVERED`、本地已收数量及收货流水。
4. 点击“模拟未知标签”和“模拟无法读取”：记录异常，不会增加库存。
5. 打开“模拟接收端断网”，扫描其他标签并确认：队列进入 `RETRY`；取消断网后等待退避时间，或点击“立即发送 / 核对一次”，恢复发送且沿用同一请求编号。
6. 切换“袋装水泥”，扫描水泥标签并确认，数量按袋显示，原钢材记录仍保留。
7. 切换“散装粉煤灰”，先扫描批次标签，再填写毛重 `32.625`、皮重 `12.500`，保存称重后确认，只入库 `20.125` 吨，而不是“1 吨”。
8. 刷新页面或停止后重启，数据仍存在。各任务有固定标签计划，不会无限生成物料或订单。

“定时采集”只针对当前选中任务，每轮每台设备最多读一个尚未扫描标签；称重任务额外读取配置 `weighings` 中的毛重 / 皮重样例。没有预设的已扫描批次等待人工称重，不凭空生成重量。“同时自动确认收货”必须显式勾选，默认关闭。全部标签完成后不再产生新采集，但后台仍处理各任务的待发送队列。

新增品种：复制配置为 `config.local.json`，在 `additional_orders` 增加完整任务，设置唯一 `id / external_order_code / tags`、货品名称和编码、单位、库位、计量模式。计数无需 `weighings`；称重可配置每个标签的 `{gross, tare}`。修改已有任务的物料、单位、标签或称重预设时使用新 `state_dir`，以免旧队列被改义。页面目前只选择配置任务，不会在线新建服务器主数据或订单。

更换订单、标签清单、设备集、模式、API 入口或作用域时使用新的 `state_dir`。状态目录带配置指纹，不能把上一测试范围的队列发送到另一实例。不要直接删正式数据来“重置演示”；需要新演示可复制配置为 `config.local.json`，设置另一个状态目录。

## 2. 无页面的手动命令与守护模式

```bash
python3 simulator.py scan --device SIM-PDA-01 --epc SIM-STEEL-B001
python3 simulator.py confirm
python3 simulator.py flush
python3 simulator.py status

# 选择水泥任务（每条独立 CLI 命令都要带相同 --task）
python3 simulator.py scan --task SIM-INBOUND-CEMENT --epc SIM-CEMENT-BAG-001
python3 simulator.py confirm --task SIM-INBOUND-CEMENT
python3 simulator.py flush --task SIM-INBOUND-CEMENT

# 散装粉煤灰：识别批次后称重，再确认
python3 simulator.py scan --task SIM-INBOUND-FLYASH-BULK --epc SIM-ASH-LOAD-001
python3 simulator.py weigh --task SIM-INBOUND-FLYASH-BULK --epc SIM-ASH-LOAD-001 --gross 32.625 --tare 12.500
python3 simulator.py confirm --task SIM-INBOUND-FLYASH-BULK
python3 simulator.py flush --task SIM-INBOUND-FLYASH-BULK
```

单次 `scan / weigh` 仅记录，不收货。`confirm` 固定当前任务待确认数量并生成 outbox；`flush` 发送/核对当前任务一次；`status` 显示当前任务及所有任务概况。未指定 `--task` 时始终是配置的第一任务，不记住上一次 CLI 的选择。

```bash
# 持续定时扫描，默认不确认收货，Ctrl+C / SIGTERM 停止
python3 simulator.py run

# 明确开启演练自动收货
python3 simulator.py run --auto-confirm

# 指定散料任务，定时模拟批次识别、预设称重和确认
python3 simulator.py run --task SIM-INBOUND-FLYASH-BULK --auto-confirm

# 本地演练轮询全部物料；每轮总计最多 3 台设备采集，不是每类开 3 台
python3 simulator.py run --all-tasks --auto-confirm

# 可重复的有限轮次演示（建议复制配置设置短间隔及全新状态目录）
python3 simulator.py run --auto-confirm --cycles 4 --config config.local.json
```

运行日志 `runtime/simulator.log` 每份最大 1 MiB，保留 3 份轮转文件。SQLite 事件记录用于追溯，当前不会自动清理；长期运行需按团队留存策略归档独立演练状态目录。

`deploy/wms-handheld-simulator.service` 是**独立演练环境模板，未安装到服务器**，默认 `PrivateNetwork=true`。需经批准准备专用低权限账号、上述代码目录、配置目录及状态目录；把配置 `state_dir` 设为 `/var/lib/project-b-test/wms-simulator`。模板不包含生产凭据，不适合未经审查直接改成真实 API 网络服务。

## 3. 接入原生 WMS 测试实例

代码提供原生 API 适配器。本节介绍默认的**隔离测试模式**；只有另行明确批准的实验室真实写入才使用第 6 节的专门配置。单元测试中的 HTTP 服务是本机契约夹具，不冒充 Java/openGauss；实验室端到端结果另见部署记录。

### 必须先准备

- 独立测试 base URL 和可信 CA（HTTPS 校验证书，不提供跳过验证选项）。只允许显式配置的本机 HTTP 用于开发夹具。
- 测试账号的 `stockin:read / stockin:write`，公司、当前仓库和当前货主必须与批准配置精确一致，程序不自动回退 `default` 或自动切换范围。
- WMS 中已准备好的测试货品、库位及入库单/明细。此模拟器**不会自动创建账号、主数据、入库单或修改数据库结构**。
- 测试入库单 `externalOrderCode` 必须使用约定的 `SIM-` 前缀并与配置完全一致。计数按每标签一个主计量单位；称重按净重。单位与真实货品主数据的一致性需测试负责人核实：当前订单读取契约未提供可用于自动校验的单位字段，程序不会自动换算吨 / 千克。

复制 `config.example.json` 为 `config.local.json`，修改：

- `mode: "wms"`，指定全新的 `state_dir`，移除 `additional_orders`；真实测试模式每次只配置一张明确批准的测试单，不把六个本地示例自动发送给服务器。
- `order.id / line_id / good_code / location_code / external_order_code / tags` 对应那张批准的测试入库单。
- `target.environment: "isolated"`、`write_enabled: true`、`base_url` 与 `approved_base_url` 精确相同，设置真实批准的三维数据域和 CA 路径。
- 使用配置指定的环境变量名传入测试账号。**不要将密码写入 JSON、Git、启动命令参数或共享文档。** 独立服务环境文件应由管理员通过批准的私密渠道配置并限制权限。

最后仍需显式启动开关：

```bash
python3 simulator.py serve --config config.local.json --enable-test-writes
```

默认隔离模式拒绝已知正式地址 `100.65.200.125:18444`、`192.168.101.57:18081`、`192.168.101.74:18444` 及对应常用节点名；仅开启 `--enable-test-writes` 不能解锁现网。防误配清单不能识别所有代理 / 别名，仍须确保 URL 确实获批。实验室真实写入采用另行批准、绑定有限计划的模式，见第 6 节。

### 使用的接口

1. `GET /api/v1/auth/csrf` + `POST /api/v1/auth/login`，原生测试账号与内存 CookieJar。
2. `GET /api/v1/auth/me`，每次处理前检查身份权限和公司/仓库/货主，拒绝越范围。
3. `GET /api/v1/stockin/{id}`，校验测试单标记、明细货品、剩余数量和状态。
4. `GET /api/v1/stockin/{id}/receipts`，先按不可变 `SIMULATOR:<jobId>` 标记核对既有结果。
5. `POST /api/v1/stockin/{id}/receipts`，遵守现有 `ReceiveRequest` 和 CSRF，不直接写 openGauss。

出站收货报文包含固定 `idempotencyKey`、`allowOverReceipt=false`，一条明细的 `lineId / quantity / locationCode / lpn / serialCodes / remark`。计数任务 quantity 等于本次不同标签数，标签写入 serialCodes；称重任务 quantity 为各批净重合计，serialCodes 为空，避免把一车 20.125 吨错误当成一个单位序列库存。批次标签、毛重和皮重保存在本地模拟器，现有 WMS Receipt 接口未接收这些称重明细，未来统一设备接口需另行适配。服务器用户身份来自认证会话，设备号不冒充后端人员。

成功后去相应测试 WMS 的“入库管理”看收货流水、“库存查询”看余额及唯一标签。原生 WMS 的现有页面不会自动展示此模拟器的全部原始扫描事件；这些事件显示在模拟器面板并留在本地 SQLite。未来统一数据底座对接时扩展适配器，不伪造现有“设备采集接口”。

## 4. 防重、超时与重启

- 同一事件 ID 的相同内容重复提交只返回原记录；事件 ID 对应不同内容会拒绝。
- 同一配置入库任务内，相同 EPC 即使来自另一台设备也只暂存一次；新任务应使用新的配置/状态目录，不按 EPC 永久全局去重。
- 确认后请求体和幂等 key 不变，持久化后才发送；没有“重试时换 UUID”的逻辑。
- 明确在发送前的断网和读取暂不可用：有限指数退避，最大间隔 60 秒，默认 8 次后 `FAILED`，不继续冲击服务。
- **POST 超时、400/409 可能已处理、5xx 或异常成功响应：进入 `REVIEW`。** 只读核对单据收货标记、货品、库位、明细行号、数量和标签数量；有确证才标记 `DELIVERED`，没有确证绝不盲目重发。
- 正式 WMS 重复请求可能返回“已处理”或“单据已完成”，本程序不会把这些错误直接当作成功。
- outbox 有原子领取和 10 分钟租约；状态查询/另一进程不会重置在途任务。崩溃后的过期租约按 `REVIEW` 核对，旧工作线程不能覆盖新领取者的结果。
- 页面“立即发送 / 核对一次”可提前处理退避中的请求，但不会把 `REVIEW` 变成重新收货。`FAILED / REJECTED` 不自动重发，不应通过删数据库或换 key 规避；先人工核对测试实例和日志。

API 目标配置及认证秘密不会通过页面状态接口暴露；页面会明确显示 demo / wms 模式。面板限制本机 Host，写操作带随机本机 CSRF 令牌，拒绝跨站 Origin；多任务请求携带当前任务标识，旧标签页任务已切换时拒绝写入，防止串料。不对外提供该简易面板，不将其当生产服务器。

## 5. 测试与 Git 提交范围

```bash
python3 -X utf8 -m unittest discover -s tests -v
```

覆盖：六类物料、单位与模式校验、称重正负值 / 小数校验、称重不入库、未称重拒绝确认、精确累计、任务切换 / 串料保护、历史收货表兼容迁移、CLI 指定任务守护、跨设备防重、离线补传、失去响应只读核对、有限重试、配置指纹、租约互斥、正式入口拒绝、HTTP/CSRF/Cookie/作用域契约与称重收货报文、面板 Host/Origin/CSRF。

本目录 `.gitignore` 排除 `runtime/`、`*.local.json`、`.private/`、`*.env`、备份和缓存。只提交本 `simulator/` 代码，不覆盖其他模块，不提交 SQLite、日志、真实账号秘密或数据库 dump。统一格式到来后可保留入库核心，替换出站适配器。

## 6. 已明确批准的实验室真实 WMS

与隔离测试模式区分：`mode=wms`、`target.environment=authorized_lab`、`write_enabled=true`、`lab_write_ack=WRITE_SIM_DATA_TO_LAB_WMS`、每张任务的批准指纹，以及命令行 **`--enable-lab-writes`** 必须全部满足。只允许 SIM- 物料 / 库位 / 标签，收货前从真实主数据核对计量单位，且仍校验公司 / 仓库 / 货主、外部单号、状态、明细与剩余数量。

`deploy/prepare_lab.py` 是一次性显式准备工具：管理员凭据只读 stdin，生成受限专用收货账号和私有配置，遇到已存在凭据或已收货计划停止；不会覆盖旧业务。`deploy/project-b-wms-simulator.service` 是已审查的实验室守护模板，只有专用服务重启，不要求重编或重启 WMS Java 后端。`deploy/check_lab.py` 只读核对真实 API 记录和 outbox。

有限计划完成后仍待命，重启不重复增加库存；下一批必须重新准备新单号、标签与状态目录。详细命令和回退边界见 [LAB_DEPLOYMENT.md](deploy/LAB_DEPLOYMENT.md)。不要仅因想看到更多数据就删除去重库或放宽权限。
