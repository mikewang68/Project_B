# EMCS 能源管理与控制系统

前端 Vue 3 + Element Plus，后端 Spring Boot + JDK 17。关系数据库为 **openGauss**，缓存使用 **Kvrocks** 的 Redis 协议。初始化数据为固定 SQL，无需 Python。

## 环境与目录

| 组件 | 版本 / 位置 |
|---|---|
| openGauss | 6.0.5，node4（192.168.101.57） |
| Kvrocks | 2.16.0，node4 |
| Java / Maven | JDK 17 / Maven 3.9.16 |
| Node / pnpm | Node 24.18.0 / pnpm 10.34.5 |
| Nginx | node6（192.168.101.74） |

- `backend-java/`：后端代码和云端模型配置。
- `web/`：前端代码、依赖锁文件、版本配置。
- `sql/`：表结构及固定演示数据，按文件名顺序执行。
- `deploy/`：服务器环境模板、systemd 服务和 Nginx 配置。

生产环境复用现有 openGauss 和 Kvrocks，不需要 Docker Compose，也不启动额外数据库或缓存容器。RocketMQ、openGemini 不属于当前应用的数据链路。

## 1. 准备数据库和缓存

由管理员为 EMCS 创建独立数据库 `emcs`（UTF-8，`DBCOMPATIBILITY='PG'`）及专用业务账号，业务账号应拥有该库 `public` schema 的建表权限。数据库端口由管理员确认，模板中的 5432 仅为默认值。

使用 openGauss 自带 `gsql`，在项目根目录按顺序导入。密码通过交互提示输入或由管理员配置权限为 600 的 `.pgpass`，不要写入命令：

```bash
for file in sql/*.sql; do
  gsql -h 192.168.101.57 -p 5432 -U emcs -d emcs -v ON_ERROR_STOP=1 -f "$file" || break
done
```

**`00-schema.sql` 会删除已有项目表。只在空库初始化或明确需要重置演示库时执行，重置前先停止后端。** SQL 使用 PG 兼容模式，不依赖 MySQL/Dolphin 语法。最后一个数据文件会校准自增序列。

数据范围为 2026-05-04 至 2026-07-12。告警由规则引擎计算，不预置告警事件。

Kvrocks 管理员须分配独立 EMCS namespace token，将其填入 `REDIS_PASSWORD`。默认 `REDIS_DATABASE=0`，不依赖未启用的多逻辑库功能；应用键另加 `emcs:` 前缀。验证码、缓存列表和清理均限制在 EMCS 键范围。

## 2. 构建后端

```bash
cd backend-java
mvn clean verify
```

产物为 `target/b-demo-server-0.1.0-SNAPSHOT.jar`。

本地调试先将数据库、缓存及 `JWT_SECRET_KEY` 配置为环境变量，再运行 `mvn spring-boot:run`。Spring 不会自动读取 `.env` 文件。默认端口 18103，健康检查为 `/actuator/health`。

## 3. 在 node4 安装后端服务

1. 创建低权限系统账号 `emcs`，准备 `/opt/emcs/config`、`/etc/emcs`、`/var/lib/emcs`。
2. 将 JAR 放到 `/opt/emcs/emcs.jar`，将 `backend-java/config/agent.yml` 放到 `/opt/emcs/config/agent.yml`。
3. 复制 `deploy/emcs.env.example` 为 `/etc/emcs/emcs.env`，填入实际数据库、Kvrocks 和 JWT 密钥。环境文件由 root 持有，权限设为 600。
4. 运行 `java -version`，确认 `/usr/bin/java` 为 JDK 17；路径不同时修改服务文件中的 `ExecStart`。
5. 将 `deploy/emcs.service` 安装到 `/etc/systemd/system/emcs.service`，确保 `/var/lib/emcs` 由业务账号拥有。

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now emcs
sudo systemctl status emcs
sudo journalctl -u emcs -n 100 --no-pager
curl http://127.0.0.1:18103/actuator/health
```

后端端口只允许 node6 或网关内网访问。数据库与缓存端口按现有服务器访问策略配置。

### 云端模型

配置文件为 `backend-java/config/agent.yml`。启用时设置 `LLM_ENABLED=true`、`LLM_BASE_URL`、`LLM_MODEL` 和 `LLM_API_KEY`。接口使用 OpenAI 兼容的 `/chat/completions` 协议；密钥仅写入服务器环境文件。当前能力为单轮汇总问答，不执行模型生成的 SQL。未启用时使用本地汇总。

### 演示时间与定时任务

`DEMO_NOW=2026-07-12T23:59:00` 与固定数据一致。默认 `SCHEDULING_ENABLED=false`，需要定时演示计算时再开启；计算窗口使用同一演示时间，调度时区为 `Asia/Shanghai`。这不是实时生产数据采集模式。

## 4. 构建前端

```bash
cd web
npm install -g pnpm@10.34.5
cp .env.example .env.production
pnpm install --frozen-lockfile
pnpm run build:prod
```

默认部署路径 `/emcs/`，API 前缀 `/emcs-api`。将 `dist/` 内容发布到 node6 的 `/srv/www/emcs/`，不要覆盖其他模块目录。把 `deploy/nginx-emcs.conf` 加入已有 Nginx 的 `server` 块：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

浏览器访问 `http://node6地址/emcs/`。Nginx 将 `/emcs-api/` 代理到 node4:18103，代理保留 SSE 流式响应。已有 Easegress 可按同一转发目标接入，但不要同时重复剥离 API 前缀。

本地开发创建 `.env.development`，设置 `VITE_APP_BASE_PATH=/`、`VITE_APP_BASE_API=/dev-api`、`VITE_API_TARGET=http://127.0.0.1:18103`，运行 `pnpm dev`，默认端口 5173。

## 5. 初始化业务与验证

演示账号：`admin`、`energy_mgr`、`ops_user`、`dispatch_user`、`finance_user`，初始密码均为 `admin123`。仅用于隔离演示环境。

首次导入后，用 `energy_mgr` 登录，从登录响应取得 token，调用 `POST /pipeline/bootstrap` 初始化统计、基线、成本和告警。请求需携带 `Authorization: Bearer <token>`，通过前端入口调用时加 `/emcs-api` 前缀。

部署检查：验证码及登录、菜单权限、能源总览、采集质量、设备画像、告警、建议、成本、报表导出、AI 汇总、服务重启后登录。定时任务仅在启用时验证。

顶栏“外观设置”提供 4 套皮肤和 3 种布局，默认科技蓝、左侧菜单；偏好保存为 `b-emcs-prefs`。共享令牌来自 IAM/SYS 基线，所有皮肤保持统一主色和状态语义。

### 本地回归测试

`mvn test` 执行普通单元测试。真实数据库回归测试默认跳过；需先向**隔离测试库**导入 SQL，再配置数据库和 Kvrocks 环境变量，运行：

```bash
cd backend-java
EMCS_DB_TEST=true mvn test
```

该测试覆盖统计重建、基线、成本、规则、查询、报表归档、主键回传，以及验证码有效期、一次性消费和缓存键隔离。数据库写入随测试事务回滚，序列值可能递增；禁止指向生产库。

本地已在 openGauss Lite 5.1.0 与 Kvrocks 2.16.0 隔离环境验证 SQL 和业务流程。目标服务器 openGauss 6.0.5 的部署与联调尚未执行，上线前需按上述清单复验。
