# 能源管控系统

项目使用 Spring Boot 后端、Vue 前端和 MySQL 数据库。MySQL、Redis 通过 Docker 启动，前后端在主机运行。

## 1. 环境要求

| 组件 | 版本 | 用途 |
|---|---|---|
| Docker Engine / Docker Desktop | 支持 Docker Compose v2 | 运行 MySQL、Redis |
| JDK | 17 | 运行后端 |
| Maven | 3.9.x | 构建后端 |
| Node.js | 24.18.0 或以上 | 运行前端 |
| pnpm | 10.34.5 | 安装前端依赖 |
| MySQL | 8.4 | 由 Docker 安装 |
| Redis | 7 | 由 Docker 安装 |

前端依赖版本见 `web/version.config.json`，后端依赖见 `backend-java/pom.xml`。

以下命令适用于 macOS / Linux，默认从项目根目录执行。Windows 可在 WSL2 中执行。

## 2. 项目目录

```text
backend-java/         Spring Boot 后端
web/                  Vue 前端
  version.config.json 前端版本与依赖清单
sql/              固定表结构与演示数据 SQL
docker-compose.yml    MySQL、Redis 配置
```

旧 Python 后端 `backend/` 为本地备份，不参与部署。新环境直接导入固定 SQL，不需要安装 Python。

## 3. 启动数据库和 Redis

先安装并启动 Docker，然后执行：

```bash
docker compose up -d
docker compose ps
```

首次启动会自动导入固定 SQL。等待 MySQL 日志出现 `MySQL init process done. Ready for start up.`，并确认 MySQL 状态为 `healthy`、Redis 状态为 `Up`。可用 `docker compose logs -f mysql` 查看进度，按 Ctrl+C 退出日志查看。默认配置如下：

| 配置 | 默认值 |
|---|---|
| MySQL 地址 | `127.0.0.1:3306` |
| 数据库 | `b_demo` |
| 数据库用户 / 密码 | `demo` / `bdemo_dev` |
| MySQL root 密码 | `bdemo_root` |
| Redis 地址 | `127.0.0.1:6379` |
| Redis 密码 / 库编号 | 无密码 / `2` |

配置文件为 `docker-compose.yml`。数据库数据保存在 Docker 命名卷中，普通停止或重启不会清空数据。以上账号用于本地演示，部署到共享服务器前应调整密码和端口访问范围。

## 4. 初始化演示数据

固定 SQL 位于 `sql/`，包含表结构、演示账号和基础业务数据。`docker-compose.yml` 已将该目录挂载到 MySQL 初始化目录，**空数据卷首次启动时自动导入**，无需额外执行命令。

已有数据卷不会自动重复导入。如需手动初始化或恢复固定演示数据，先停止 Java 后端，再从项目根目录执行：

```bash
cat sql/*.sql | docker compose exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE"'
```

**该命令会删除并重建项目表，覆盖已有数据。** 请仅在初始化或明确需要重置时执行。文件按名称排序导入：先 `00-schema.sql`，再导入全部 `10-data-*.sql`。不要单独重复导入数据分片。

SQL 只包含构造数据，业务时间范围为 2026-05-04 至 2026-07-12。`sql/` 仅保留 1 个表结构文件和 8 个数据分片，不包含造数脚本。

## 5. 启动后端

在新终端执行：

```bash
cd backend-java
export JWT_SECRET_KEY="$(openssl rand -hex 32)"
mvn spring-boot:run
```

默认地址：`http://127.0.0.1:9099`。可在另一个终端检查：

```bash
curl http://127.0.0.1:9099/actuator/health
```

返回 `"status":"UP"` 表示健康检查通过。

后端配置文件为 `backend-java/src/main/resources/application.yml`，支持以下环境变量：

| 环境变量 | 默认值 |
|---|---|
| `APP_PORT` | `9099` |
| `APP_ROOT_PATH` | 空 |
| `DB_HOST` / `DB_PORT` | `127.0.0.1` / `3306` |
| `DB_DATABASE` | `b_demo` |
| `DB_USERNAME` / `DB_PASSWORD` | `demo` / `bdemo_dev` |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `6379` |
| `REDIS_PASSWORD` / `REDIS_DATABASE` | 空 / `2` |
| `JWT_SECRET_KEY` | 启动时设置，至少 32 字节 |
| `DEMO_NOW` | `2026-07-12T23:59:00` |

修改配置时，在启动后端的终端中设置对应环境变量。正式部署应保存并复用生成的 JWT 密钥；更换密钥会使已有登录失效。

本地开发保持 `APP_ROOT_PATH` 为空，前端代理会移除 `/dev-api` 前缀。根目录旧 `.env.example` 属于 Python 后端，不用于 Java 启动；Spring Boot 不会自动加载该文件。

### 云端 LLM 配置

Agent 的独立配置文件为 `backend-java/config/agent.yml`。以 `backend-java/` 为工作目录启动时自动加载；配置修改后重启后端。

支持 **OpenAI-compatible Chat Completions** 接口。配置 `base-url` 为供应商 API 根路径（通常包含 `/v1`），程序自动追加 `/chat/completions`。不适用于仅提供原生 Gemini / Anthropic 协议的地址。

在启动后端的终端中设置：

```bash
export LLM_ENABLED=true
export LLM_BASE_URL='https://替换为供应商地址/v1'
export LLM_MODEL='替换为供应商模型名称'
# 在终端输入密钥，不把密钥写入仓库配置文件或命令历史
read -r -s LLM_API_KEY
export LLM_API_KEY
mvn spring-boot:run
```

| 配置项 | 环境变量 | 默认值 |
|---|---|---|
| 是否启用 | `LLM_ENABLED` | `false` |
| API 根地址 | `LLM_BASE_URL` | 空 |
| API 密钥 | `LLM_API_KEY` | 空 |
| 模型名称 | `LLM_MODEL` | 空 |
| 请求超时（秒） | `LLM_TIMEOUT_SECONDS` | `60` |
| 最大输出 token 数 | `LLM_MAX_TOKENS` | `2048` |
| temperature | `LLM_TEMPERATURE` | `0.2` |

启用后，AI 问答和巡检摘要使用配置的模型；未启用时保留本地汇总，并在问答中明确标识。当前传给模型的业务上下文仅包含未关闭告警和待闭环建议数量，不支持由模型执行任意 SQL 或业务操作。问答当前按单轮发送。

打包后包含默认配置。若从其他目录启动 JAR，将配置放在该工作目录的 `config/agent.yml`，或直接使用以上环境变量。

## 6. 启动前端

在新终端执行：

```bash
npm install -g pnpm@10.34.5
cd web
cat > .env.development <<'CONFIG'
VITE_APP_TITLE=B-Demo能源管控系统
VITE_APP_ENV=development
VITE_APP_BASE_API=/dev-api
CONFIG
pnpm install --no-frozen-lockfile
pnpm run dev -- --port 5173
```

访问：`http://127.0.0.1:5173`。

首次需要安装 pnpm 并创建环境配置文件，后续启动只需执行最后一条命令。仓库未提交 `pnpm-lock.yaml`，首次安装会生成本地锁文件，因此使用 `--no-frozen-lockfile`。

接口代理位于 `web/vite.config.js`，默认将 `/dev-api` 转发到 `http://127.0.0.1:9099`。如果后端端口改变，需同步修改代理地址。

### 演示账号

以下账号默认密码均为 `admin123`。

| 账号 | 角色 |
|---|---|
| `admin` | 系统管理员 |
| `energy_mgr` | 能源管理员 |
| `ops_user` | 运维人员 |
| `dispatch_user` | 调度人员 |
| `finance_user` | 财务人员 |

### 首次计算业务数据

固定 SQL 只写入基础数据。后端启动后，还需执行一次统计、基线、成本和告警计算：

1. 使用 `energy_mgr` 登录前端。
2. 在浏览器开发者工具的“网络”面板中，找到 `/login` 响应并复制 `token` 字段。
3. 在终端执行以下命令，将占位内容替换为该 token：

```bash
curl -X POST http://127.0.0.1:9099/pipeline/bootstrap \
  -H 'Authorization: Bearer 替换为登录返回的token'
```

等待返回 `code: 200`、`bootstrap 完成` 后刷新页面。每次重置演示数据后需重新执行此步骤。

## 7. 打包部署

### 后端

```bash
cd backend-java
mvn clean package
java -jar target/b-demo-server-0.1.0-SNAPSHOT.jar
```

运行 JAR 前，按第 5 节配置环境变量。长期运行可交由 systemd 等进程管理工具管理。

### 前端

```bash
cd web
cat > .env.production <<'CONFIG'
VITE_APP_TITLE=B-Demo能源管控系统
VITE_APP_ENV=production
VITE_APP_BASE_API=/prod-api
VITE_BUILD_COMPRESS=gzip
CONFIG
pnpm install --no-frozen-lockfile
pnpm run build:prod
```

产物位于 `web/dist/`。将其复制到服务器的 `/var/www/b-demo/`，使用 Nginx 提供静态文件和接口代理。同机部署示例：

```nginx
server {
    listen 80;
    server_name _;
    root /var/www/b-demo;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /prod-api/ {
        proxy_pass http://127.0.0.1:9099/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 300s;
        proxy_buffering off;
    }
}
```

`proxy_pass` 末尾的 `/` 用于移除 `/prod-api/` 前缀，后端仍保持 `APP_ROOT_PATH` 为空。不要使用 Vite 开发服务器替代正式静态文件服务。

## 8. 日常启动与排查

日常启动顺序：Docker → Java 后端 → 前端。数据库已初始化时，无需重新造数。

```bash
# 查看数据库日志
docker compose logs --tail=100 mysql redis

# 停止数据库和 Redis，保留数据卷
docker compose stop

# 再次启动
docker compose up -d
```

`docker compose down -v` 会删除数据库数据卷，请勿用于日常停止。

| 问题 | 检查方式 |
|---|---|
| 数据库连接失败 | 检查容器健康状态、端口和账号密码 |
| 前端接口返回 500 / 验证码不显示 | 检查后端日志及 `9099` 端口是否可用 |
| 前端接口返回 404 | 检查代理地址、API 前缀和 `APP_ROOT_PATH` |
| 前端提示无法监听 80 端口 | 使用 `pnpm run dev -- --port 5173` |
| 页面没有统计、成本或告警 | 确认 SQL 导入和 `/pipeline/bootstrap` 均已执行成功 |
| 修改 Docker 密码后无法连接 | 已有数据卷不会因修改 Compose 环境变量自动更改数据库密码，需在数据库中同步修改 |
