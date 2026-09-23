# 三节点服务器部署说明

## 推荐拓扑

```text
浏览器
  -> bpoc-node6 Nginx（静态前端、同源反向代理）
  -> bpoc-node4 / bpoc-node5 Easegress（API 与 WebSocket 入口）
  -> bpoc-node4 Java 17 单实例（Demo 内存状态）
```

当前 Demo 的状态保存在 Java 进程内存中，因此第一阶段只部署一个后端实例。直接在 node4、node5 各启动一个实例会造成 REST 请求和 WebSocket 看到的状态不一致；需要双实例前，应先把状态迁移到共享存储并设计消息同步。

## 部署前必须确认的值

| 占位符 | 含义 |
|---|---|
| `__PUBLIC_SERVER_NAME__` | 浏览器访问 node6 使用的域名或 IP |
| `__NODE4_BACKEND_HOST__` | node4 可被 node4/node5 Easegress 访问的地址 |
| `__BACKEND_PORT__` | Java 服务端口，模板建议 `18080` |
| `__NODE4_EASEGRESS_HOST__` | node4 Easegress 地址 |
| `__NODE5_EASEGRESS_HOST__` | node5 Easegress 地址 |
| `__EASEGRESS_LISTEN_PORT__` | 为本项目分配的 Easegress HTTPServer 端口 |

不要直接使用模板中的占位符启动服务。还需确认现有 Easegress 对象名、监听端口和 Nginx `server_name` 不与服务器当前 Demo 冲突。

## 1. 构建

将源码上传到任一已安装统一构建工具的节点，在项目根目录运行：

```bash
bash deploy/scripts/build-server.sh
```

脚本会严格核对服务器清单中的 Java 17、Maven 3.9.16、Node.js 24.18.0、npm 11.16.0 和 pnpm 10.34.5，然后执行前端类型检查、测试、生产构建和后端测试打包。产物写入 `deploy/generated/<时间戳>/`，不会覆盖以前的发布包。

## 2. node4 安装后端

1. 创建专用账号和目录：`bproject:bproject`、`/opt/b-project/backend`、`/etc/b-project`。
2. 将构建产物 `backend/safety-gate-service.jar` 放到 `/opt/b-project/backend/`。
3. 将 `deploy/backend/backend.env.example` 复制为 `/etc/b-project/backend.env`，替换公开访问地址并限制文件权限。
4. 将 `deploy/backend/b-project-safety-gate.service` 放到 `/etc/systemd/system/`。
5. 执行 `systemctl daemon-reload`，再启用并启动 `b-project-safety-gate.service`。
6. 检查 `curl http://127.0.0.1:18080/health/ready` 返回 HTTP 200。

后端绑定 `0.0.0.0` 是为了让 node5 Easegress 也能访问单实例；防火墙应只允许 node4、node5 访问 `__BACKEND_PORT__`，不要向公网开放。Categraf 可抓取 `http://127.0.0.1:18080/actuator/prometheus`，再由 Nightingale 展示和告警。

## 3. 配置 Easegress 2.11

复制 `deploy/easegress/b-project-safety-gate.yaml.template`，替换全部占位符后，先在测试端口应用：

```bash
egctl create -f b-project-safety-gate.yaml
```

模板分别使用 `Proxy` 和 `WebSocketProxy`。WebSocket 路由设置了 `clientMaxBodySize: -1`，符合 Easegress 对 WebSocket HTTPServer 路径的要求。先用 `egctl get`/`egctl describe` 确认对象状态，再让 Nginx 接入。

## 4. node6 发布前端

1. 将构建产物 `frontend/` 下的所有文件同步到 `/opt/b-project/frontend/`。
2. 复制 `deploy/nginx/b-project-safety-gate.conf.template` 到 `/etc/nginx/conf.d/`，替换全部占位符。
3. 运行 `nginx -t`，确认通过后再执行 `systemctl reload nginx`。

Nginx 使用 `try_files` 支持 Vue Router history 模式，并将 `/api`、`/health`、`/ws` 原样转发到 Easegress。

## 5. 验证

```bash
bash deploy/scripts/verify-deployment.sh http://实际域名或IP
```

脚本检查前端健康、后端就绪和状态 API。最后使用浏览器确认页面能够加载，且界面中的实时连接状态为在线。

## 当前未接入的服务器服务

Demo 目前使用内存模拟数据，没有强行加入 openGauss、Kvrocks、RocketMQ 或 openGemini 客户端。部署运行本身不需要这些服务；在没有连接地址、账号、Schema、Topic 和数据保留策略前盲目接入会改变现有业务行为。后续拿到接口参数后，可依次将业务状态、缓存/幂等、事件消息和时序指标替换为对应适配器。
