# MT-WMS 在 B 项目 openEuler 环境的部署说明

## 1. 目标部署结构

推荐把 MT-WMS 单 JAR 部署在 `database-node`，直接连接同一节点已经运行的 openGauss 6.0.5：

```text
浏览器
  -> HTTPS / Easegress
  -> database-node:18080 / MT-WMS single JAR
  -> 127.0.0.1:5432 / openGauss 6.0.5
```

前端已经编译进 JAR，不需要替换 node6 上的 React Demo，也不依赖 Kvrocks、RocketMQ 或 openGemini。需要将应用与数据库分开部署时，可以把 JAR 放到 node5，并把 `WMS_DB_URL` 中的主机改为 node4 的内部地址。

## 2. 构建

服务器已安装 Java 17、Maven 3.9.16、Node.js 24.18.0 和 pnpm 10.34.5，满足本项目构建要求。

```bash
cd /path/to/mt-wms-rebuild/WMS
bash scripts/build-linux.sh
```

构建会依次完成：

1. pnpm 锁定依赖安装；
2. Vue/TypeScript 类型检查；
3. Vitest 单元测试；
4. Vite 前端生产构建；
5. Java 单元测试；
6. 生成 `target/mt-wms.jar`。

首次构建需要能访问项目依赖源。后续如需完全离线构建，应先准备 Maven 仓库和 pnpm store，而不是直接在空缓存环境增加 `--offline`。

## 3. 准备独立数据库

不要覆盖 node4 上已有业务数据库。由 openGauss 管理员创建 MT-WMS 独立数据库和最小权限运行用户，示意如下：

先确认现有容器名称以及宿主机实际监听的数据库端口：

```bash
isula ps
sudo ss -lntp | grep ':5432'
```

如果 openGauss 没有发布到宿主机 `5432`，不要直接假定 `127.0.0.1:5432` 可用；应由服务器管理员确认现有 API 使用的数据库地址，或为容器配置受控的宿主机端口，再把实际地址写入 `WMS_DB_URL`。

```sql
CREATE USER wms_app IDENTIFIED BY 'replace-with-a-strong-password';
CREATE DATABASE mt_wms OWNER wms_app;
```

实际用户创建、密码策略和授权应服从服务器现有 DBA 规范。应用部署到 node5 时，还需要同时配置 openGauss 监听地址、客户端访问规则和 node4 防火墙，仅允许 node5 访问数据库端口。

## 4. 执行数据库迁移

先确认 node4 上的容器名：

```bash
isula ps
```

安装独立的迁移管理员配置：

```bash
sudo install -d -m 0750 /etc/mt-wms
sudo install -m 0600 deploy/openeuler/mt-wms-migration.env.example \
  /etc/mt-wms/mt-wms-migration.env
sudo vi /etc/mt-wms/mt-wms-migration.env
```

至少填写真实的：

```ini
OPENGAUSS_CONTAINER_NAME=实际容器名
WMS_DB_ADMIN_PASSWORD=实际管理员密码
```

如果密码含有空格、`#`、`$` 等 Shell 特殊字符，请在迁移配置中使用单引号包围，并按 Shell 规则转义。随后运行：

```bash
sudo WMS_MIGRATION_ENV_FILE=/etc/mt-wms/mt-wms-migration.env \
  bash scripts/migrate-opengauss.sh
```

脚本通过 iSulad 容器内的 `gsql` 按版本顺序执行 `V001` 至当前最新脚本，并维护 `schema_version` 和 SHA-256 校验。已经成功执行且校验值一致的版本会自动跳过。

迁移管理员密码只供迁移脚本使用，不应放进应用的 systemd 环境文件。

## 5. 安装应用服务

执行安装脚本：

```bash
sudo bash scripts/install-openeuler.sh target/mt-wms.jar
```

脚本会：

- 创建不可登录的 `mtwms` 系统用户；
- 把 JAR 原子安装到 `/opt/mt-wms/mt-wms.jar`；
- 安装 `/etc/systemd/system/mt-wms.service`；
- 首次安装时创建 `/etc/mt-wms/mt-wms.env` 配置模板；
- 执行 `systemctl daemon-reload`，但不会在配置完成前自动启动。

编辑运行配置：

```bash
sudo vi /etc/mt-wms/mt-wms.env
sudo chmod 0600 /etc/mt-wms/mt-wms.env
```

node4 同机部署的核心配置为：

```ini
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=18080
WMS_DB_URL=jdbc:postgresql://127.0.0.1:5432/mt_wms?currentSchema=public
WMS_DB_USERNAME=wms_app
WMS_DB_PASSWORD=实际运行用户密码
WMS_DB_POOL_SIZE=10
WMS_FILES_DIR=/var/lib/mt-wms/files
```

启动并设为开机自启：

```bash
sudo systemctl enable --now mt-wms
sudo systemctl status mt-wms
sudo journalctl -u mt-wms -n 100 --no-pager
```

## 6. 健康检查和验收

服务器本机执行：

```bash
bash scripts/smoke-test.sh http://127.0.0.1:18080
```

该命令必须同时通过：

- `/health/live`：Java 进程正常；
- `/health/ready`：应用已经成功连接 openGauss。

配置好外部 HTTPS 入口后，可以进一步验证登录和工作台接口：

```bash
export WMS_SMOKE_COMPANY=default
export WMS_SMOKE_USERNAME=实际验收账号
read -rsp '请输入验收密码：' WMS_SMOKE_PASSWORD
echo
export WMS_SMOKE_PASSWORD
bash scripts/smoke-test.sh https://实际访问域名
unset WMS_SMOKE_PASSWORD
```

不要把验收密码写入脚本、Git 或 Shell 历史文件。

## 7. Easegress 入口要求

MT-WMS 使用 Session 和 CSRF Cookie，前端和 API 应保持同源。Easegress 应把同一个 HTTPS 域名下的以下路径转发到 `database-node:18080`：

```text
/
/assets/**
/api/**
/open/qimen/**
/health/**
```

代理需要传递 `X-Forwarded-For`、`X-Forwarded-Host` 和 `X-Forwarded-Proto: https`。生产 Profile 默认启用 Secure Session Cookie，因此正式入口必须使用 HTTPS。

如果 18080 已被占用，可以修改 `/etc/mt-wms/mt-wms.env` 中的 `SERVER_PORT`，并同步修改 Easegress 后端地址。

## 8. 更新与回退

更新前先完成数据库备份。数据库迁移通常只能向前执行，JAR 回退不等于数据库结构回退。

应用更新：

```bash
bash scripts/build-linux.sh
sudo cp /opt/mt-wms/mt-wms.jar /opt/mt-wms/mt-wms.jar.previous
sudo bash scripts/install-openeuler.sh target/mt-wms.jar
sudo systemctl restart mt-wms
bash scripts/smoke-test.sh http://127.0.0.1:18080
```

只在数据库结构仍兼容时回退 JAR：

```bash
sudo systemctl stop mt-wms
sudo mv /opt/mt-wms/mt-wms.jar.previous /opt/mt-wms/mt-wms.jar
sudo systemctl start mt-wms
```

## 9. 本地开发保持不变

Windows 本地开发仍然可以使用 Docker Desktop 和现有 PowerShell 脚本。Maven 会在 Windows 自动调用 `pnpm.cmd`，在 openEuler 自动调用 `pnpm`。服务器部署流程不会启动第二个 openGauss 容器。
