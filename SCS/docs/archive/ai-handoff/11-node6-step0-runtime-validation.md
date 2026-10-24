# 11 - node6 Step 0 前端部署与运行时验收报告

> **归档时间**：2026-09-21 21:55:33 CST  
> **执行节点**：`bpoc-node6` (`192.168.101.74`)  
> **反向代理端口**：`18088` (Nginx 1.24.0)  
> **静态资源路径**：`/data/app/scs-20260921`  
> **后端上游**：`http://192.168.101.57:18080` (`bpoc-node4`)  
> **当前阶段**：Phase 1 / Step 0 (NODE4: PASS, NODE6: PASS, Overall Step 0: COMPLETE)

---

## 一、验收结果汇总表

| 验证维度 | 目标 / 测试 URL | 响应代码 / 预期结果 | 实测响应 | 状态 |
| :--- | :--- | :--- | :--- | :---: |
| **SELinux 端口授权** | TCP 18088 加入 `http_port_t` | `semanage port -l` 含 18088 | 已授权，Nginx 成功 bind | **PASS** |
| **SELinux 文件上下文** | `/data/app/scs-20260921` | `httpd_sys_content_t` | `unconfined_u:object_r:httpd_sys_content_t:s0` | **PASS** |
| **Nginx 服务重载** | `sudo systemctl reload nginx` | exit 0, 生成新 worker | 21 个 worker 进程平滑替换，监听 18088 | **PASS** |
| **前端健康探针** | `GET http://127.0.0.1:18088/health/frontend` | HTTP 200 JSON | `{"status":"UP","service":"scs-frontend"}` | **PASS** |
| **后端探针反代** | `GET http://127.0.0.1:18088/health/ready` | HTTP 200 JSON (node4 转发) | `{"status":"UP","components":{"application":"UP","cache":"UP","database":"UP",...}}` | **PASS** |
| **当前用户接口** | `GET http://127.0.0.1:18088/api/v1/auth/me` | HTTP 200 (USR-001 李娜) | `{"id":"USR-001","name":"李娜","role":"安全员","team":"安全管理组","shift":"夜班","online":true}` | **PASS** |
| **主数据字典接口** | `GET http://127.0.0.1:18088/api/v1/meta/dictionaries` | HTTP 200 (12区域/6班组/7责任人) | 包含规范字典完整列表，与 DemoMasterData 100% 对齐 | **PASS** |
| **态势总览接口** | `GET http://127.0.0.1:18088/api/v1/overview/summary` | HTTP 200 指标聚合 | `{"onDuty":128,"deviceOnline":36,"deviceTotal":38,...}` | **PASS** |
| **静态资源服务** | `GET http://127.0.0.1:18088/` | HTTP 200 HTML | `<!doctype html>...<title>B项目 · 装卸作业安全卡控</title>` | **PASS** |
| **JS/CSS 静态加载** | `GET http://127.0.0.1:18088/assets/index-CJInmU-l.js` | HTTP 200 (442 KB) | `Content-Type: application/javascript, Length: 442197` | **PASS** |
| **路由 History 回退** | `GET http://127.0.0.1:18088/fences` | HTTP 200 (try_files 生效) | 正确返回 `index.html`，支持前端路由直接刷新 | **PASS** |
| **实时 WebSocket 反代** | `ws://127.0.0.1:18088/ws/live` | HTTP 101 Switching Protocols | `Upgrade: websocket`, `Connection: upgrade`, node4 响应 101 | **PASS** |
| **红线保护隔离** | 端口 18445 / 18082 及现有业务进程 | 运行正常且未触碰 | `18445` 独立运行，`18082` 未触碰，旧进程零干扰 | **PASS** |

---

## 二、详细技术实测记录

### 1. Nginx 监听与进程状态
- **监听端口**：`0.0.0.0:18088` (TCP LISTEN, Recv-Q 0, Send-Q 511)
- **管理命令**：`sudo /usr/bin/systemctl reload nginx`
- **配置文件**：`/etc/nginx/conf.d/scs-18088.conf`
- **工作进程**：PID 1056893 ~ 1056912 平滑上线服务。

### 2. WebSocket 协议握手回执
通过模拟原生 WebSocket 协议握手（含 `Upgrade: websocket`, `Connection: Upgrade`, `Sec-WebSocket-Key`），Nginx 18088 经反向代理直通 node4 18080，实测响应回执：
```http
HTTP/1.1 101 
Server: nginx/1.24.0
Date: Mon, 21 Sep 2026 13:55:33 GMT
Connection: upgrade
X-Trace-Id: 8e791c0c-8d1d-4ea7-a504-1585ec0ece6a
Upgrade: websocket
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
```

### 3. 主机内网防火墙（Firewalld）放通说明
在 `bpoc-node6` 本地与本机 IP（`192.168.101.74:18088`）请求完全畅通。
若需要从局域网其它节点（或跳板机/办公网络）通过浏览器访问 `http://192.168.101.74:18088`，需由管理员在 `bpoc-node6` 执行防火墙放行：
```bash
sudo firewall-cmd --permanent --add-port=18088/tcp && sudo firewall-cmd --reload
```

---

## 三、Phase 1 / Step 0 总体就绪状态判定

```
==================================================
SCS DEPLOYMENT STEP 0 (NODE4 + NODE6)
==================================================
NODE4 (Backend 18080 + openGauss 5432 + Kvrocks 6666): PASS
NODE6 (Frontend + Nginx 18088 + WS 101 Proxy):         PASS
OVERALL STEP 0 STATUS:                                 COMPLETE
READY FOR PHASE 1 STEP 1 (JDBC Migration):            YES
==================================================
```
