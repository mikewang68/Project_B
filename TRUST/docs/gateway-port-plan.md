# B项目网关与应用端口规划

本规划区分公网导航、TRUST、内部导航和应用节点端口。公网访问通过 Cloudflare Tunnel 进入网关，不直接开放服务器入站端口。

## 网关端口

| 节点 | 端口 | 用途 | 访问范围 |
|---|---:|---|---|
| gateway-node | 80 | 公网应用导航 | Cloudflare Tunnel；只展示已发布的公网应用 |
| gateway-node | 18080 | TRUST 独立入口 | 内网/VPN，完成公网安全加固前不建立公网 Tunnel |
| gateway-node | 18081 | 内部应用导航 | 内网/VPN；包含业务系统、公共能力和运维工具 |

## 应用前端端口

| 节点 | 端口 | 系统 | 状态 |
|---|---:|---|---|
| app-node | 18443 | PSMS | 已运行 |
| app-node | 18444 | WMS | 已运行 |
| app-node | 18445 | SCS | 已运行 |
| app-node | 18446 | WMS 独立实例 | 预留 |
| app-node | 18447 | DTS | 预留 |
| app-node | 18448 | EHMS 独立入口 | 迁移预留；当前使用 18090/ehm/ |
| app-node | 18449 | EMCS | 预留 |
| app-node | 18450 | 后续业务系统或灰度实例 | 通用预留 |
| app-node | 18091 | IAM | 已运行，仅内部导航 |
| app-node | 18092 | SYS | 已运行，仅内部导航 |

预留项保存在私有部署配置中并设置 `enabled=false`，因此不会生成导航卡片。部署和验收完成后再启用，并按需填写独立的 `publicUrl`。

## 非导航端口

后端服务端口包括 DTS 13009、EHMS 18083、EMCS 18103、PSMS 3100、SCS 18080、WMS 18081、IAM 8081、SYS 8082 和 TRUST 28182。相同端口位于不同节点时不构成冲突。

Nightingale 17000 和 Cockpit 9090 只出现在内部导航。IPFS、Fabric、数据库、openGemini、Redis、MongoDB、监控采集和回环代理端口不得生成导航入口。SCS 的旧入口 18088、跳转入口 18093 也不重复生成卡片。

## 公网清单

公网导航只从启用且配置了 `publicUrl` 的服务生成。目前为：

- PSMS：`https://psms.project.example.com/production-dispatch/`
- WMS：`https://wms.project.example.com/`
- SCS：`https://scs.project.example.com/`

公网卡片不显示内网地址、端口、开发状态、运维工具或内部访问说明。
