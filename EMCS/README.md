# B-project-demo

B 项目能源管控系统演示 demo。运行后端已重构为 Java 17 + Spring Boot 3，架构和接口风格参考若依 RuoYi-Vue `springboot3` 版本线；数据库仍使用原 Docker MySQL/Redis，前端保持 Vue 3.5.26。

**当前状态**：Spring Boot 后端已经承接登录、动态路由、五幕业务、系统管理、监控、定时任务和统计管道；旧 `backend/` 仅保留为迁移契约对照，不参与运行。

## 目录结构

```
B-project-demo/
├── CLAUDE.md              # agent 团队的项目规则（需求来源、纪律、目录所有权）
├── AGENTS.md              # 同 CLAUDE.md（供其他 agent 工具读取）
├── .claude/               # agent 定义 ×4、共享 skill、模型配置
├── docker-compose.yml     # MySQL 8 + Redis（基础设施容器化，应用本机跑）
├── backend-java/          # 当前运行后端：Spring Boot 3 + Security + MyBatis
├── backend/               # 旧 FastAPI 契约对照；不参与启动或部署
├── web/                   # Vue3 + Element Plus + ECharts 前端（骨架前端剥离而来）→ frontend-dev
├── datagen/               # DDL + 演示数据构造脚本 + 造数测试 → data-engineer
│   ├── skeleton/          # 骨架系统表 + 骨架种子（skeleton-init.sql）
│   ├── ddl/               # 能源域业务表（V001__energy_domain.sql）
│   ├── tests/             # 造数脚本测试
│   └── generate_demo_data.py
└── docs/                  # 开发内部记录（坑点 dev-pitfalls、修复台账 fix-log、已知问题 known-issues、mock 契约）
```

## 环境搭建（macOS）

```bash
# 1. 基础设施（每次开发前确保在跑）
docker compose up -d

# 2. 仅为造数脚本安装 Python 依赖
cd backend
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cd ..

# 3. 建库 + 造数（幂等，固定种子；--reset 从空库重建骨架表 + 业务 DDL + 全部演示数据）
backend/.venv/bin/python datagen/generate_demo_data.py --reset
# 骨架 + DDL 已存在、只想重跑业务数据时去掉 --reset
# （如需手工灌骨架 SQL：mysql 必带 --default-character-set=utf8mb4，否则中文种子会报 Data too long）

# 4. 后端（Java 17 / Maven 3.9，端口 9099）
cd backend-java
mvn spring-boot:run

# 5. 前端（服务器清单：Node 24.18.0 + pnpm 10.34.5；Vue 3.5.26）
cd web
pnpm install --frozen-lockfile
pnpm run dev
```

演示账号（PRD 附录 K.6 五角色，由 `generate_demo_data.py` 种子，demo 阶段共用口令 `admin123`）：

| 账号 | 角色 | 数据范围 |
|---|---|---|
| `admin` | 超级管理员（骨架复用） | 全部 |
| `energy_mgr` | 能源管理员（五幕主角色） | 全部业务菜单 |
| `ops_user` | 运维 | 全部业务菜单，不含成本/报表（越权样例 INJ-08） |
| `dispatch_user` | 调度 | 仅 A 钢材装卸区 |
| `finance_user` | 财务 | 成本 + 报表 + 总览 |

## IntelliJ IDEA / PyCharm 启动指南

前置依赖按上面「环境搭建」跑过一次后，日常启动顺序是：Docker → Spring Boot → Vue。

**1. Docker（MySQL + Redis）**——不归 PyCharm 管，底部 Terminal 面板执行：
```bash
open -a Docker   # Docker Desktop 没开的话先开
docker compose up -d
docker ps --filter name=bdemo   # 确认 bdemo-mysql / bdemo-redis 都是 Up
```

**2. 后端**——最简单是直接用 Terminal 面板：
```bash
cd backend-java && mvn spring-boot:run
```
IDEA 中直接运行 `com.bdemo.BdemoApplication`，Working directory 设为 `backend-java/`。配置全部可通过环境变量覆盖，见 `backend-java/src/main/resources/application.yml`。

**3. 前端**——最简单是直接用 PyCharm 的 Terminal 面板：
```bash
cd web && pnpm run dev
```
想用 Run Configuration 也可以：package.json 选 `web/package.json`，包管理器选 pnpm，Script 选 `dev`。

⚠️ macOS 非 root 用户绑定 80 端口可能报 `EACCES`：终端方式用 `sudo npm run dev`；PyCharm 的 npm Run Configuration 不方便加 sudo，遇到这个问题建议直接走终端。

**4. 一键组合（可选）**：Run → Edit Configurations → + → Compound，把后端和前端两个 Run Configuration 揉在一起，一个按钮同时起两个（Docker 仍需手动先起一次）。

**常见启动失败**

| 现象 | 原因 | 解决 |
|---|---|---|
| 后端报 `Port 9099 was already in use` | 9099 端口被占 | `lsof -i:9099` 找到旧 Java 进程后停止再重启 |
| 后端报 Redis/MySQL 连接错误 | 第 1 步 Docker 容器没起来或没 ready | `docker ps` 确认 `bdemo-mysql` 是 `healthy` |
| 前端 `EACCES: permission denied 80` | 非 root 绑定 80 端口 | 改 `web/vite.config.js` 的开发端口，或由本机反向代理绑定 80 |
| 登录页验证码不显示 | 后端没启动 / `/captchaImage` 请求失败 | 先用上面第 2 步的 curl 验证后端 |
| 数据库连接失败 | Docker 未就绪或环境变量与 compose 不一致 | 检查 `DB_*`、`REDIS_*` 和容器健康状态 |

## 版本控制约定

- 主干 `main` 保持可演示；每个任务开 `feat/<幕次或模块>-<要点>` 短分支，qa-reviewer 过审后合入
- 提交信息带 REQ 锚点：`feat(backend): 数据质量着色接口 REQ-045`
- 每幕收口打 tag：`act1-done` … `act5-done`，评审演示版打 `demo-0801`
- 远端用私有仓库（项目材料不公开）；`.env`、`.mcp.json`、构造数据产物不入库
