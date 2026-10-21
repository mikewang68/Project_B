# WMS 网页模拟终端部署与维护

核实日期：2026-10-22（北京时间）。发布标识 `20261021-web-v1` 沿用任务启动日期，不代表验收日期。仅修改 WMS，不改 IAM、Portal、其他模块、共享数据库基础服务或 MQ。

## 入口及路径

登录 WMS 后：**入库管理 → 模拟手持终端**（新标签页）。相对入口 `/simulator/` 复用原站点会话，返回 WMS 的链接为 `/#/stockin` 和 `/#/inventory`。

| 节点 | 文件 / 路径 | 用途 |
|---|---|---|
| node6 | `/home/bpoc-data/projects/Project_B/WMS/WMS/frontend/src/views/StockinView.vue` | 入库页入口按钮 |
| node6 | `/home/bpoc-data/projects/Project_B/WMS/WMS/simulator` | Python / HTML / 测试公共源码 |
| node6 | `/opt/project-b/wms-simulator/releases/20261021-web-v1`；`current` 指向此目录 | 实际运行版本，`web_gateway.py` 启动 |
| node6 | `/opt/project-b/wms-frontend/releases/20261021-web-v1` | 新 WMS 静态前端，包括既有换肤、智能助手和登录提示 |
| node6 | `/etc/nginx/conf.d/bdemo-44e32a9.conf` 的 **18444 WMS server 块** | 静态前端、`/simulator/` 代理，其余仍走 node4 |
| node6 | `/etc/systemd/system/project-b-wms-simulator.service` | 模板来自 `project-b-wms-simulator-web.service`，账号 `wms-sim` |
| node6 | `/etc/project-b/WMS/simulator/config.local.json`、`credentials.env`、`lab-ca.crt` | 私有获准计划、专用模拟账号、信任 CA；不得放 Git |
| node6 | `/var/lib/project-b-wms-simulator/SIM-20261019` | 原有队列保留；首任务 SQLite 增加 `web_batches`；新任务在 `tasks/SIM-WEB-<编号>/` |
| node4 | `/opt/project-b/WMS/mt-wms.jar`；`project-b-wms`；`192.168.101.57:18081` | 既有业务 API，本次未更换/重启 |
| node4 | openGauss `mt_wms_zxj/public` | `stockin_order/stockin_receipt/inv_balance/inv_serial` 最终持久化 |
| node5 | 本次无新增文件、进程、端口 | 不依赖新 MQ 协议 |

访问地址：证书规范入口 `https://bpoc-node6:18444/simulator/`；Tailscale 传输经 node1 `100.65.200.125:18444` → node6 `192.168.101.74:18444`。本机应安装实验室 CA，并将 `bpoc-node6` 解析到 node1 Tailscale 地址。直接访问 `https://100.65.200.125:18444/simulator/` 或内网 IP 可能证书名称不匹配；不是服务未运行，也不要全局关闭 TLS 校验。Python 仅监听 `127.0.0.1:18766`，不得直接对外开放。

## 权限、作用域和数据语义

1. 每次面板 API 调用均转发浏览器 Cookie 到原 WMS `/api/v1/auth/me`，不保存密码、不使用模拟账号代替无效浏览器身份。
2. 查看需要 `stockin:read`；写操作还需 `stockin:write`。创建有限计划还通过原生 `master:read` 核验物料单位与状态。公司、当前仓库、货主必须与私有配置完全相同（当前均 `default`）；这不是公共 IAM / SSO / DomainZone 验收结果。
3. 写请求需要原 WMS `X-XSRF-TOKEN` 与 Cookie 一致、同源 Origin、JSON、有效共享任务编号。过期/匿名 401、CSRF/权限/作用域 403、过期任务 409。只读/跨域权限边界已做隔离 fixture 测试；本次没有新增只读业务用户或完成 IAM SSO 验收。
4. 入库单由当前登录用户创建；收货后台继续使用受保护的专用模拟账号（`master:read/stockin:read/stockin:write`）。自动任务开启后即使关闭网页，已批准的有限作业仍可继续；结束演示需关闭自动开关，紧急停止用 systemctl stop。
5. 只允许获准六类 SIM 物料/库位。计数 1–100；称重 1–3 批，毛重高于皮重且最多 1000 单位、三位小数。单价 0，显式标记虚构数据；无任意外部 URL、任意货品或运行命令功能。数量与标签由原 WMS 再校验。
6. 创建请求 UUID 在首次 POST 前固定；SQLite 保存 actor/spec/状态。网络结果不确定仅根据同一外部单号核对；不存在确定结果时不重新 POST。READY 计划重启恢复；不得删除去重数据库、重用标签或更换 UUID 盲目重发。页面未确认输入不持久化；不确定时保存页面编号，联系维护者核对。
7. 扫码/称重只形成待确认记录，不直接加库存；确认或显式自动确认后入 WMS，称重用净重，批次标签不当作吨数序列标签。SQLite 是事件/计划/outbox，不是最终库存；库存与收货在 openGauss。
8. 网页最多累计 100 个任务（含原6个），达到上限联系维护者归档，不能清空去重记录继续灌数据。多用户共享当前任务，切换会暂停自动采集；非完整设备多租户调度平台。

## 构建与发布（维护者审查后逐步执行）

- 先核对当前源码与运行 hash，备份 Nginx、服务、私有配置、队列及 WMS 数据库。不要整份覆盖共享 Nginx，也不要把服务器私有凭据打包进 Git。
- `python3 -B -m unittest discover -s simulator/tests -q`：当前共 60 项；Python 3.11+，标准库。
- 前端须在服务器当前源码副本构建，避免覆盖此前未完整归档的登录提示等设置；在 `frontend` 用 Node 24 / 已安装 pnpm 执行 typecheck、test:unit（16项）、build。Vite 默认产物在 `../target/classes/static`，**不是 frontend/dist**。新静态发布目录必须可供 nginx 读取。
- 登录提示通过已有 `VITE_DEMO_LOGIN_HINT` 构建环境私密注入；不要在源码或发布命令中写真实密码。未设置时沿用该源码的安全默认文案。
- 在新版本运行目录复制 `simulator.py`、`web_gateway.py`、`web.html`、`deploy/`、`tests/`，保留旧版本。不要复制本机 runtime 到实验服务器。原有私有配置和队列路径保持不变。
- 安装本目录 `project-b-wms-simulator-web.service` 为实际 `project-b-wms-simulator.service`，发布链接后 `daemon-reload`、仅重启模拟服务。正常启动自动采集/自动确认均关闭。
- 在 **WMS 18444 的 server 块内**增加以下路由；`/`、`/index.html` 静态发布，其余业务 URL 沿用原 `location /` 到 node4。`FRONTEND_ROOT` 用新版本绝对目录替换，不是可以原样粘贴的变量：

```nginx
location = /simulator { return 302 /simulator/; }
location /simulator/ {
    proxy_pass http://127.0.0.1:18766/;
    proxy_set_header Host $http_host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 120s;
}
location = / { root FRONTEND_ROOT; try_files /index.html =404; add_header Cache-Control "no-store"; }
location = /index.html { root FRONTEND_ROOT; add_header Cache-Control "no-store"; }
location /assets/ { root FRONTEND_ROOT; try_files $uri @wms_legacy_assets; }
location @wms_legacy_assets {
    proxy_pass http://192.168.101.57:18081;
    proxy_set_header Host $http_host;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

仅 `nginx -t` 成功后 reload；失败恢复本次改动，不能重启其他模块。旧 asset hash 回退到旧 JAR，减少旧页面仍打开时的影响。本次没有修改 node1 转发端口、node4 JAR 或 node5。

## 核对与本次结果

```bash
sudo systemctl status project-b-wms-simulator --no-pager
sudo journalctl -u project-b-wms-simulator -n 50 --no-pager
sudo python3 -B /opt/project-b/wms-simulator/current/deploy/check_lab.py \
  --config /etc/project-b/WMS/simulator/config.local.json \
  --credentials /etc/project-b/WMS/simulator/credentials.env --include-web
```

2026-10-22 浏览器真实测试：WMS 入库按钮打开新标签页；扫码、重复/未知/读失败、断网队列恢复、定时自动入库、称重、刷新、退出会话均验证。匿名/退出 401、无 CSRF 403。没有 JS 页面错误。TLS 用 CA+名称校验后，对独立测试浏览器固定服务端 SPKI；不是全局忽略证书。

| 新增演示单 ID | 物料 | 计划 / 实收 | 收货 | 包装唯一标签 |
|---|---|---|---|---|
| 28 | SIM-STEEL-001 | 3 / 3 捆 | 2 笔（2+1） | 3 |
| 29 | SIM-GRAVEL-001 | 2.055 / 2.055 吨 | 1 笔 | 0 |

两单 COMPLETED；数据库只读核对 `stockin_receipt`、`inv_balance` 和 `inv_serial` 一致。重启模拟服务后复查仍为8任务、两新增单共3笔收货/3标签，未重复。原非 SIM 货品15、原入库单21保持不变。原六单22–27保留。以后业务量会变化，不要把上述固定计数当长期健康阈值。

node4 JAR SHA-256 保持 `2032c035ad993f93f32ab5acd890789ec44398467140c662d5d977c6f577696d`。原 WMS 后端未替换/重启。

## 备份与回退

- node6 修改前：`/var/backups/wms-web-handheld-20261021-TbeXiv`（源码、队列一致性副本、私有配置、服务和 Nginx）。含秘密的备份仅供 root 维护，禁止分享到 Git。
- node4 写入前：`/var/backups/wms-web-handheld-20261021-YU91S3/mt_wms_zxj.dump`。
- node6 激活前配置：`/var/backups/wms-web-activate-20261021-5vjfgt1e`；旧运行版本 `/opt/project-b/wms-simulator/releases/20261019-v2` 保留。
- 要撤下网页：先停止模拟服务，恢复激活前 Nginx / service / current 链接，语法校验后 reload；**不能不检查就启动旧自动轮询 unit**，它含 `--all-tasks --auto-confirm`，未来旧配置如有未完成任务会继续写入。可保持服务停止直到维护者确认。
- 代码回退不会撤销已经入库的模拟数据；不得整体恢复数据库覆盖组员后续工作，也不得删去重库重发。数据清理走经批准的业务冲销流程。
