# B项目装卸作业安全卡控系统

本目录是原 FastAPI + 原生 JavaScript Demo 的统一技术栈迁移版，项目统一位于 `code/project B`。

## 技术基线

- 前端：Node.js 24.18.0、npm 11.16.0、pnpm 10.34.5、Vue 3、TypeScript 严格模式、Vite、Vue Router 4、Pinia、Element Plus 2、ECharts 6.1。
- 后端：Java 17、Spring Boot 3.5、Maven 3.9.16，模块化单体。
- 接口：`/api/v1`、JSON UTF-8、原生 WebSocket、`trace_id` 与 `Idempotency-Key`。
- 主题：浅色管理端；品牌色 `#065A82`；告警使用黄/橙/红并同时显示文字与图标。

## 开发运行

环境要求：Node.js 24.18.0、npm 11.16.0、pnpm 10.34.5、毕昇/OpenJDK 17、Maven 3.9.16。

项目根目录的 `.config.json` 是统一配置入口，保存服务端口、数据库、Redis 和前端开发代理配置。该文件已加入 `.gitignore`，请勿提交真实密码；字段说明和服务器配置样例见 `.config.example.json`。

配置优先级为：启动命令参数 / 环境变量 > `.config.json` > 项目默认值。后端可通过环境变量 `PROJECT_CONFIG_FILE` 或 JVM 参数 `-Dproject.config.file=...` 指定外部配置文件。前端构建只读取 `frontend` 与 `server` 字段，数据库密码不会注入浏览器代码。

```powershell
cd frontend
pnpm install --frozen-lockfile
pnpm dev
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
