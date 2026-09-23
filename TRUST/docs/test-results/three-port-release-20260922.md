# 三端口网关上线验收 · 2026-09-22

正式网关已切换为公网导航 80、TRUST 18080、内部导航 18081。公网清单为三个业务入口，内部清单为九个入口。已按用户要求保留额外 VPN 客户端权限。公网导航不再代理 TRUST、EHMS 或后端 API。

## 实际检查

- `check-http-access.py` 返回 PASS：两个导航资源与生成结果一致，TRUST 登录与 CSRF 通过，29 条事件、29 个任务，数据库、IPFS、Fabric 均为 UP，无业务写入。
- 公网主导航、PSMS 完整入口、WMS、SCS 均返回 200。
- 公网 `/trust/`、`/ehm/`、`/api/v1/status` 均返回 404。
- Nginx 为 active/enabled；未进行整机重启演练。
- TRUST 实际部署位置为 `/data/app/trust`，此次网关切换没有升级其应用制品。

## 迁移中的修正

旧配置存在未登记的 VPN 放行和 EHMS 代理。精确移除这两段后与历史摘要一致，确认无其他改动后归档原状态并登记差异，才执行回退。

首次应用时 SELinux 拒绝 Nginx 绑定 18080，脚本撤回新配置。登记 18080、18081 为 `http_port_t` 后重新应用成功。期间网关短暂停止。SELinux 保持启用，未调整其他端口类型。

## 后续部署前置检查

在回退旧网关之前检查 `semanage port -l`，确认所有新监听端口已属于 `http_port_t`。只有端口尚无专用登记时才使用 `semanage port -a -t http_port_t -p tcp <端口>`；已有其他类型时先核查，不直接覆盖。

正式配置和状态的切换前备份保存在网关 `/var/lib/b-project-trust-http/before-three-port-20260922.tar.gz`。脚本、旧私有配置及页面备份保存在工程 `runtime/gateway-backups`。详细验收 JSON 保存在本机 `.local/test-results/http-access/online-check.json`。
