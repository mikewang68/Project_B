# 应用导航与单层 HTTP 网关

2026-09-22 更新：三端口已正式启用，实际登录、资源、业务计数和公网隔离检查通过，见 [上线验收](test-results/three-port-release-20260922.md)。下文“本地准备”是当日切换前记录。

网页链路拆分为：

```text
公网浏览器 → Cloudflare Tunnel → 网关 Nginx:80 → 公网导航
内网/VPN浏览器 → 网关 Nginx:18080 → 应用节点 Java:28182（TRUST）
内网/VPN浏览器 → 网关 Nginx:18081 → 内部导航
```

网关按监听 IP 和端口接收访问，通过内网 HTTP 直连 TRUST 应用。应用节点不增加 Nginx。数据库、IPFS、Fabric 的独立分工和连接方式不变。公网只通过 Cloudflare Tunnel 发布导航，不直接开放网关防火墙入站范围。

80 端口只显示配置了 `publicUrl` 的已发布业务应用，卡片直接打开对应公网 HTTPS 域名，不包含 TRUST、公共能力、运维工具、内网地址和端口说明。18081 端口显示内部完整清单，卡片沿用应用节点现有地址和协议。TRUST 在 18080 端口独立提供前端、API 和文件下载。三端口共享外观偏好，但不提供统一登录。详细分配见 [端口规划](gateway-port-plan.md)。

## 在已运行的网关上增加导航

配置示例中的 `httpAccess.portal.services` 是统一入口清单。每项填写 `id`、`title`、`description`、`badge`、`code`、`category`；类别为“业务系统”“公共能力”“运维工具”。内部清单必须保留 `id=trust`。其他内部入口填写 `scheme`、`port`、`path`；公网应用额外填写绝对 HTTPS `publicUrl` 和精简的 `publicDescription`。`enabled=false` 仅预留端口，不生成卡片。真实内网地址只保存在私有配置和内部清单中。

首次从旧单端口配置切换到三端口时，网络范围和防火墙规则会变化，不能使用只更新页面的 `update`。维护窗口内先回退本项目旧网关配置，再应用新配置：

回退前必须检查 SELinux HTTP 端口类型，确保 18080 和 18081 已登记为 `http_port_t`；否则 Nginx 会被拒绝绑定新端口。具体检查与本次修正见上线验收记录。

```bash
sudo python3 deploy/http-access-admin.py rollback --role gateway
sudo python3 deploy/http-access-admin.py apply --role gateway
```

三端口配置安装完成后，后续只修改卡片和页面资源仍使用 `update --role gateway`。工具将公网与内部静态文件分别放入内容寻址目录，执行 `nginx -t`、平滑重载，并逐项验证两个导航和 TRUST 独立入口。

这一操作无需在应用节点执行管理员命令。不要为一次导航更新运行下面的全量 `rollback`；全量回退会移除整个 TRUST 网关接入。新增或修改导航入口后，重新同步并执行相同 `update` 即可。

静态页面源文件位于 `deploy/portal/`，无需重新构建 Java 或 Vue 应用。本地可执行 `python tests/portal-preview.py --audience public --port 18280` 预览公网导航，执行 `python tests/portal-preview.py --audience internal --port 18281` 预览内部导航；内部预览需要可访问现有 TRUST 网关，也可通过 `--trust-origin http://127.0.0.1:18181` 指向本地 TRUST。结束后关闭预览进程。这些预览不是正式 Nginx 部署。`node tests/portal-browser.cjs` 检查导航分类、搜索、跳转与手机布局；已有 `tests/browser-smoke.cjs` 可用 `TRUST_BASE_URL=http://网关地址:18080` 回归真实 TRUST 页面。

2026-09-22 三端口导航已完成本地生成、前端构建和浏览器检查，尚未修改 Cloudflare 或正式网关。首次切换须按上文在维护窗口执行旧配置回退和新配置应用；完成后才能把结果记为线上验收。2026-09-16 的 [导航准备验证](test-results/portal-preparation-20260916.md) 是旧单端口方案的历史记录，不代表当前三端口方案已上线。

## 环境依据与修正

2026-09-16 正式启用旧方案时，系统服务不能在用户目录写入日志和 PID，应用接入服务反复启动失败。旧脚本的单次服务状态检查误报成功；普通账号的回环预演未覆盖正式 systemd 权限环境。这些结果不能视为上线验收。

当前实现复用网关的 `nginx.service`，只添加 `/etc/nginx/conf.d/b-project-trust.conf`；沿用系统 PID、缓存目录，项目日志在 `/var/log/nginx/`。不安装额外 Nginx，不新增网关 systemd 单元，也不关闭 SELinux。网关需已允许 Nginx 对上游建立网络连接；工具检查 `httpd_can_network_connect`，不自行放宽该设置。

应用由原 Java 进程直接监听 IPv4 `0.0.0.0:28182`，保留原回环健康检查和 SSH 调试入口。管理员先在外网接口对应的防火墙区域设置“仅网关来源可访问 28182”，再重启本工程应用。启动参数强制 IPv4；默认未启用该选项时仍只监听回环。工具拒绝未审查的额外 IPv4 接口。网络拓扑或防火墙区域变化后需重新审查。

## 配置与准备

将 [配置示例](../deploy/http-access.example.json) 的 `httpAccess` 合并到私有部署配置。`gateway.address` 为应用看到的网关来源；`listenAddresses` 为网关实际拥有的 IP；`accessAddress` 为浏览器入口；`clientCidrs` 为允许的客户端网段或单机地址。真实配置不进入 Git。

从开发电脑同步：

```powershell
.\scripts\sync.ps1 -Roles application,gateway
```

在各节点工程目录生成无须 sudo 的审查材料：

```bash
# 应用节点
python3 deploy/http-access-admin.py plan --role application
# 网关节点
python3 deploy/http-access-admin.py plan --role gateway
```

计划保存在 `runtime/http-access/<角色>/single-plan.json`。应用计划只包含监听设置和防火墙规则；网关计划附带可加入系统 Nginx 的配置片段。

## 启用及旧方案迁移

先在应用节点执行：

```bash
sudo python3 deploy/http-access-admin.py apply --role application
```

工具按旧状态记录核对摘要，停用并移除本项目旧接入服务和旧规则，设置新规则后重启本工程 Java 应用。短暂重启期间页面不可用。原有网站、数据库、IPFS、Fabric 和业务数据不在此操作范围。历史状态归档保留；被人工修改的旧文件会中止迁移。

待应用显示 `Single-proxy HTTP verified and applied: application`，再在网关节点执行：

```bash
sudo python3 deploy/http-access-admin.py apply --role gateway
```

工具先从网关访问应用健康接口，再检查系统 Nginx 配置，添加本项目配置及来源规则，然后启动现有 Nginx（若已运行则平滑重载）。使用标准日志与配置标签，执行 `restorecon` 和 `nginx -t`，不改主配置。21 MiB 请求上限匹配应用的单文件 20 MiB 限制。

两侧均需同一实际进程连续三次返回 HTTP `UP` 才报告成功；应用侧还核对端口所属 Java PID。启动或检查失败会尝试回退本次变更，并返回非零退出码；回退本身失败时保留状态和具体错误。网关服务具备系统自启；此操作不等于为原四个业务组件实现了整机重启恢复。

## 验证与状态

在开发电脑执行：

```powershell
python scripts/check-http-access.py
```

它直接访问网关，检查页面资源、登录、CSRF、事件台账和三组件状态，不增加业务数据。结果写入 `.local/test-results/http-access/online-check.json`。

各节点可检查实际健康：

```bash
python3 deploy/http-access-admin.py status --role application
python3 deploy/http-access-admin.py status --role gateway
```

以上两条分别在各自角色节点运行。原单层入口已完成跨机来源拒绝和浏览器检查，仍未演练重启恢复；新增三端口导航仍需正式入口验证。普通账号在应用节点运行 `python3 tests/http-access-preview.py` 验证单层 Nginx 与真实应用的回环交互及已配置导航；不能替代系统服务权限、80、18080、18081 端口和防火墙验收。

## 回退

先在网关节点、再在应用节点分别执行：

```bash
sudo python3 deploy/http-access-admin.py rollback --role gateway
sudo python3 deploy/http-access-admin.py rollback --role application
```

网关仅移除本项目配置和新增规则，恢复原先 Nginx 运行与自启状态。应用先恢复回环监听，确认健康后才移除新规则。旧的故障代理不会重新启用。新状态保存在 `/var/lib/b-project-trust-http/single-<角色>.json`，应用监听设置在 `/etc/b-project-trust-http/application-listen-address`。

参考：[Nginx 按 IP 选择虚拟服务器](https://nginx.org/en/docs/http/request_processing.html)、[Nginx 反向代理](https://docs.nginx.com/nginx/admin-guide/web-server/reverse-proxy/)、[SELinux 对用户目录的限制](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/7/htmlsingle/selinux_users_and_administrators_guide/sect-security-enhanced_linux-introduction-additional_resources)。实际状态以本项目探针与验收记录为准。
