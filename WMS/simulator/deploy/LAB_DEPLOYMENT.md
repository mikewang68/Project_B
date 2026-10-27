# 实验室 WMS 模拟采集部署与验收（原始批次 2026-10-19）

> 2026-10-27 状态更新：旧Python网页/自动发送服务已停止并屏蔽启动，旧网页入口关闭，已有库存、入库单和队列未删除。当前设备模拟入口为[Android终端](../android-terminal/README.md)。下文为原始批次记录，不再作为现网启用指令。

**当前更新：2026-10-22 已新增服务器网页入口。** “入库管理 → 模拟手持终端”，相对路径 `/simulator/`；运行版本改为 `/opt/project-b/wms-simulator/releases/20261021-web-v1`，WMS 前端现由 node6 独立静态目录发布，node4 JAR 和业务 API 沿用。动态网页任务及重建/回退详见 [WEB_DEPLOYMENT.md](WEB_DEPLOYMENT.md)。下文六单数量、旧运行版本和轮询行为是 10 月 19 日历史快照，不是当前任务总量。

本文件只描述 WMS 的模拟手持终端 / 称重程序，不是其他模块的部署脚本。没有安装真实 RFID 或地磅驱动，没有改统一 IAM、Portal、共享消息队列或其他模块。

## 2026-10-19 部署快照（当前增量见上方链接）

| 节点 | 路径 / 服务 | 用途 |
|---|---|---|
| node6 | `/home/bpoc-data/projects/Project_B/WMS/simulator` | 公共 WMS 源码目录（2026-10-27 已扁平化）；原部署批次不变 |
| node6 | `/opt/project-b/wms-simulator/releases/20261019-v2`；`current` 链接 | 独立 Python 运行代码，非 Java JAR |
| node6 | `/etc/systemd/system/project-b-wms-simulator.service` | 以专用 Linux 用户 `wms-sim` 运行；开机自启 |
| node6 | `/etc/project-b/WMS/simulator/config.local.json` | 私有服务器配置：实际订单 / 明细 ID、数据域、CA、批准计划 |
| node6 | `/etc/project-b/WMS/simulator/credentials.env` | 模拟收货专用账号秘密，root:root 0600；不得复制进源码或 Git |
| node6 | `/var/lib/project-b-wms-simulator/SIM-20261019` | SQLite outbox、扫描 / 称重事件与轮转日志；不是最终库存库 |
| node4 | `/opt/project-b/WMS/mt-wms.jar`；`project-b-wms` | 既有 WMS API 与页面，此次没有替换 JAR、重启此服务或改变端口 |
| node4 | openGauss `mt_wms_zxj` / `public` | 真实库存余额 `inv_balance`、收货 `stockin_receipt`、唯一标签 `inv_serial` 等 |
| node5 | 无新增文件或服务 | 直接使用既有 WMS 收货 API，不使用 / 冒充统一 MQ 协议 |

数据链路：node6 模拟采集 → node6 既有 HTTPS WMS 代理 → node4 WMS 收货 API → node4 openGauss。

本程序没有新增公共网页端口。**服务器结果请进入既有 WMS 的“库存查询”“入库管理”“唯一标签”查看**，不是去本机 `127.0.0.1:18765` 找服务器数据。后者仍为默认本地演练面板，互不混用。

- Tailscale 入口：`https://100.65.200.125:18444/`。
- 证书规范入口：`https://bpoc-node6:18444/`；本机需信任实验室 CA 并让该名称解析至 `100.65.200.125`。
- 内网入口：node6 `https://192.168.101.74:18444/`，IP 名称匹配仍须按实际证书处理。
- 查找编码 `SIM-` 或外部单号 `SIM-20261019-`，物料名称以“模拟采集·”开头，供应商为“模拟采集供应商（虚构）”。

## 已落库的有限计划

| 物料编码 | 数量 | 单位 | 入库单 ID | 收货笔数 | 唯一标签数 |
|---|---:|---|---:|---:|---:|
| SIM-STEEL-001 | 10 | 捆 | 22 | 4 | 10 |
| SIM-FLYASH-BAG | 6 | 袋 | 23 | 2 | 6 |
| SIM-CEMENT-001 | 6 | 袋 | 24 | 2 | 6 |
| SIM-PARTS-001 | 6 | 件 | 25 | 2 | 6 |
| SIM-FLYASH-BULK | 59.900 | 吨 | 26 | 1 | 0 |
| SIM-GRAVEL-001 | 67.025 | 吨 | 27 | 1 | 0 |

六张单均 COMPLETED，12 个 outbox 请求均 DELIVERED，6 条专属模拟库存，28 个有效唯一包装标签。重量批次仅用于身份关联，不当作“一标签一吨”写入唯一单位标签。毛重、皮重和原始读事件仍保留在模拟器 SQLite；WMS 已有 API 接收净重和收货标记，未新增称重明细表。

计划按 10 秒轮询，每轮由同一组 3 台虚拟设备处理一个任务、最多采集 3 个新标签，再轮到下一物料；不是每个物料另开 3 台。**当前批次已完成，服务保持待命，重启不会再次增加库存。** 若需持续演示，应显式准备新的有限批次和新的状态目录，不能删 outbox、重用标签或给旧请求换 UUID。

## 启停与核对

以下命令在 node6 使用经授权的 sudo：

```bash
sudo systemctl status project-b-wms-simulator --no-pager
sudo journalctl -u project-b-wms-simulator -n 50 --no-pager
sudo systemctl stop project-b-wms-simulator
sudo systemctl start project-b-wms-simulator

# 只读核对 API 收货量与本地队列；不输出密码
sudo python3 -B /opt/project-b/wms-simulator/current/deploy/check_lab.py \
  --config /etc/project-b/WMS/simulator/config.local.json \
  --credentials /etc/project-b/WMS/simulator/credentials.env --include-web
```

`check_lab.py` 同时核对模拟账号仅有 `master:read / stockin:read / stockin:write`，对用户管理、出库、库存余额读取应返回 403；管理员仍可在正常页面查看库存。这不是错误，也不要因此给模拟账号加 ADMIN。

## 再次部署或新建批次

1. 先备份 WMS 数据库、当前服务/配置与运行代码；不要清空旧业务表，不要重启共享数据库。
2. 在代码副本执行单元测试，核对唯一修改范围 `WMS/simulator/`。发布到新的独立运行目录，维护 node6 公共源码副本与运行副本的对应 SHA-256。
3. 首次安装时由 WMS 维护者在 **WMS 数据库** 审查运行 `simulator-role.sql`，仅新增专用角色及三项权限；它不是公共 IAM 权限变更。此角色拥有对应数据域的收货权限，SIM 前缀 / 有限计划由客户端保护，**不是服务器行级安全策略**，因此专用凭据仍必须受限。
4. 由获准管理员通过 stdin 私密通道调用 `prepare_lab.py --authorize-lab-writes`，传入 JSON `{username,password}`，不得把真实值写进命令行、仓库或共享文档。参数需明确批次、HTTPS URL、CA、公司、仓库、货主、私有输出目录、新状态目录。
5. 准备器通过原生 API 创建 / 检查独立 SIM 主数据和待收货单；订单来源使用现有枚举 `CUSTOM`（不能自造 `SIMULATOR` 来源）。不改现有真实货品单位、不覆盖已有收货、不自动重试结果不确定的 setup POST。发现既有凭据或已收货订单会停止，先核对再继续。
6. 私有配置使用 `mode=wms`、`target.environment=authorized_lab`、`write_enabled=true`、`lab_write_ack=WRITE_SIM_DATA_TO_LAB_WMS`，每张计划绑定 `lab_approval_sha256`；服务启动还必须显式 `--enable-lab-writes`。旧的 `--enable-test-writes` **不能**解锁实验室现网。摘要只是防误改指纹，不是授权签名；私有配置的受控权限和维护者批准才是授权来源。
7. `--all-tasks --auto-confirm` 才会轮询六任务并自动确认。默认 `mode=demo`、普通本地一键启动和隔离测试开关保持不变，不能顺手把旧本地队列转发到服务器。
8. 先确认模拟账号范围、权限和物料真实单位，再启用专用服务，核对 WMS API、数据库只读查询及页面。

修改物料、单位、标签、批次、目标域、订单或预设重量后，必须重新审查计划并用新状态目录；不要手改旧计划 hash 来重发已经完成的单。

## 本次证据与回退边界

- node4 备份：`/var/backups/wms-simulator-20261019-dtAIli/mt_wms_zxj.dump`（355152 bytes）及同目录 `mt-wms.jar`，均在写入模拟数据前生成。
- node6 发布前备份目录：`/var/backups/wms-simulator-code-20261019-sKDzpb`；首次发布前没有模拟器目录。最终版本前的模拟源码备份在该目录 `source-before-final.tgz`，第一运行版本也保留在 `releases/20261019-v1`。原 WMS 目录未覆盖。
- 原 JAR SHA-256 前后一致：`2032c035ad993f93f32ab5acd890789ec44398467140c662d5d977c6f577696d`。
- 原非 SIM 物料仍为 15 个、原入库单仍为 21 张；本次新增 6 种模拟物料、6 张模拟单、12 笔收货。读取 openGauss 表确认数量，不仅是浏览器或 SQLite 数据。
- 真实服务重启防重验收通过：仅重启 `project-b-wms-simulator`，6 单 / 12 笔 / 28 标签及数量不变。
- 浏览器验收使用独立无头 Edge：先用 CA + 名称校验确认入口，再用服务端 SPKI 精确固定解决临时浏览器缺少实验室 CA 的问题；未全局关闭 TLS 校验。正常用户仍需安装受信 CA。
- 停止模拟服务会停止新采集，但不会删除已经入账的库存。**不要整体恢复数据库来撤销模拟数据**，否则可能覆盖别人的后续工作；需清理时先核对 SIM 单据，用经批准的业务冲销 / 逻辑删除流程处理。
