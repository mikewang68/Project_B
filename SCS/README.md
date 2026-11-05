# B项目装卸作业安全卡控系统

本目录是原 FastAPI + 原生 JavaScript Demo 的统一技术栈迁移版，项目统一位于 `code/project B`。

## 技术基线

- 前端：Node.js 24.18.0、npm 11.16.0、pnpm 10.34.5、Vue 3、TypeScript 严格模式、Vite、Vue Router 4、Pinia、Element Plus 2、ECharts 6.1。
- 后端：Java 17、Spring Boot 3.5、Maven 3.9.16，模块化单体。
- 接口：`/api/v1`、JSON UTF-8、原生 WebSocket、`trace_id` 与 `Idempotency-Key`。
- 主题：浅色管理端；品牌色 `#065A82`；告警使用黄/橙/红并同时显示文字与图标。

## 中央权限与设备来源接入

本模块已合入 `9ebdf83e6bed044724dfb7103ab78e349521984f` 的 SCS 源码增量，包含中央 SSO、项目/区域资源范围检查、按授权范围推送的 WebSocket 刷新，以及可信机器来源校验。公共 Java 依赖为仓库内的 `IAM/context-client`（`com.bproject.iam:context-client:2.1.0`）。首次构建或 SDK 更新后，先从仓库根目录执行：

```bash
mvn -gs SCS/backend/.mvn/settings.xml -s SCS/backend/.mvn/settings.xml -f IAM/context-client/pom.xml clean install
```

启用中央 V2 前，按 [`backend/db/v21/README.md`](backend/db/v21/README.md) 核对现有数据库，再依次补齐 `001-central-scope.sql` 与 `002-resource-scope.sql`；仅执行 001 不足以覆盖扩展资源。应用启动不会自动执行这些迁移。已有数据的未知归属保留 NULL，不批量填入默认项目或区域。

SSO 需要通过受保护配置提供 `APP_SECURITY_IAM_BASE_URL=<IAM服务地址>/api/v1/iam`、`APP_SECURITY_IAM_SERVICE_TOKEN` 和 `APP_SECURITY_IAM_AUDIENCE=scs`。机器来源使用 `app.security.machine-bindings-file` 指定私有登记文件，请求携带 `X-SCS-Source-ID` 与 `X-API-Key`，只允许更新已登记且在精确对象白名单中的对象。旧共享机器凭据不能更新已有归属或部分归属对象。协议、范围刷新行为及机器来源格式见 [`docs/scoped-realtime-machine-contract.md`](docs/scoped-realtime-machine-contract.md)。

## 开发运行

环境要求：Node.js 24.18.0、npm 11.16.0、pnpm 10.34.5、毕昇/OpenJDK 17、Maven 3.9.16。先按上一节安装公共 Java SDK。

SCS 根目录的 `.config.json` 供前端 Vite 读取 `frontend` 与 `server` 字段，配置开发端口和代理；字段示例见 `.config.example.json`。该文件已加入 `.gitignore`，请勿写入真实密码。

后端使用 Spring Boot 配置：`backend/src/main/resources/application*.yml`、环境变量、外部 Spring 配置及命令行参数；现有后端不读取 `.config.json` 或 `PROJECT_CONFIG_FILE`。服务凭据和机器来源登记放在受保护的外部配置中。现场启动显式选择 `server` profile，并核对演示 seed/simulator 已关闭。

从 SCS 工作区安装前端依赖，使用本目录的 `pnpm-workspace.yaml` 与 `pnpm-lock.yaml`：

```bash
pnpm --dir SCS install --frozen-lockfile
```

随后从 SCS 目录启动前端：

```bash
pnpm --dir frontend dev
```

```powershell
cd backend
mvn -gs .mvn/settings.xml -s .mvn/settings.xml spring-boot:run
```

前端默认访问 `http://127.0.0.1:5173`，Vite 将 `/api` 和 `/ws` 代理到 `http://127.0.0.1:8080`。

## 验证

```powershell
cd frontend
pnpm typecheck
pnpm test
pnpm build

cd ..\backend
mvn -gs .mvn/settings.xml -s .mvn/settings.xml test
```

项目 POM 会校验 Java 17 和 Maven 3.9.x；`.mvn/settings.xml` 固定使用 Maven Central，避免受本机全局 Maven 配置影响。

后端未启动时，前端会切换到内置演示快照，便于独立验收界面。演示数据不用于生产决策，也不会向 PLC 或现场设备发送控制指令。

## 服务器部署

生产构建默认使用同源 `/api/v1` 和 `/ws/live`，适配 node6 Nginx -> node4/node5 Easegress -> node4 Java 单实例链路。后端提供 `/health/live`、`/health/ready` 和 `/actuator/prometheus`，可分别供 systemd/Easegress 健康检查及 Categraf 指标采集使用。

部署模板、构建脚本和三节点操作顺序见 [`deploy/README.md`](deploy/README.md)。模板中的服务器地址和端口必须在上线前按实际环境替换。
