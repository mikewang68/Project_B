# SCS bpoc-node4 Step 0 部署与验证执行说明

本说明仅包含 node4 部署所需的最小人工操作步骤。后续所有构建、Spike 测试、启动与探针核验由 verify-node4-step0.sh 脚本全自动完成。

---

### 步骤 1：Windows 本地上传 Backend 部署包

在 Windows PowerShell（工作目录 c:\Users\xis\Desktop\项目开发\Project_B\SCS）执行：

```powershell
scp -J jingchanglong@100.65.200.125 `
  SCS-backend-deploy.tar.gz `
  jingchanglong@192.168.101.57:/home/jingchanglong/Project_B/
```

---

### 步骤 2：登录 bpoc-node4 解压部署包

在 bpoc-node4 Cockpit Terminal 执行：

```bash
mkdir -p /home/jingchanglong/Project_B/SCS
cd /home/jingchanglong/Project_B/SCS
tar -xzf ../SCS-backend-deploy.tar.gz
```

---

### 步骤 3：人工初始化 openGauss

在 bpoc-node4 执行：

```bash
cd /home/jingchanglong/Project_B/SCS
bash deploy/scripts/init-opengauss-scs.sh
```

- 脚本已内置容器运行时预检（检查 gsql、lib 依赖、omm 本地身份与 SELECT 1 连通性）。
- 根据终端提示交互式输入 safety_admin 应用密码（不再需要输入管理员密码，通过容器本地 omm 身份执行）。
- 若提示 EXISTING_SAFETY_SCHEMA_REQUIRES_REVIEW 则立即停止，不得覆盖或 DROP。

---

### 步骤 4：人工配置 runtime/backend.env

在 bpoc-node4 执行：

```bash
mkdir -p /home/jingchanglong/Project_B/SCS/runtime
chmod 700 /home/jingchanglong/Project_B/SCS/runtime

touch /home/jingchanglong/Project_B/SCS/runtime/backend.env
chmod 600 /home/jingchanglong/Project_B/SCS/runtime/backend.env

cat << 'EOF' > /home/jingchanglong/Project_B/SCS/runtime/backend.env
SPRING_PROFILES_ACTIVE=server
SERVER_ADDRESS=0.0.0.0
SERVER_PORT=18080

OPENGAUSS_HOST=127.0.0.1
OPENGAUSS_PORT=5432
OPENGAUSS_DATABASE=b_project
OPENGAUSS_USERNAME=safety_admin
OPENGAUSS_PASSWORD=<YOUR_SAFETY_ADMIN_PASSWORD>
OPENGAUSS_POOL_MAX=10

KVROCKS_HOST=127.0.0.1
KVROCKS_PORT=6666
KVROCKS_PASSWORD=<YOUR_KVROCKS_PASSWORD>
KVROCKS_DATABASE=0

ROCKETMQ_ENABLED=false
OPENGEMINI_ENABLED=false

APP_DEMO_SEED_ENABLED=false
APP_DEMO_SIMULATOR_ENABLED=false

CORS_ALLOWED_ORIGINS=http://192.168.101.74:18088,http://127.0.0.1:18088
APP_LOG_LEVEL=INFO
EOF
```

- 将 `<YOUR_SAFETY_ADMIN_PASSWORD>` 替换为步骤 3 设定的密码。
- 将 `<YOUR_KVROCKS_PASSWORD>` 替换为容器现有密码（可通过 `sudo isula inspect bpoc-kvrocks | grep -i 'requirepass'` 获取）。
- 真实密码严禁提交 Git 或写入公共文档。

---

### 步骤 5：执行一键验收脚本

在 bpoc-node4 执行：

```bash
cd /home/jingchanglong/Project_B/SCS
bash deploy/scripts/verify-node4-step0.sh
```

该脚本将全自动依次完成：
- 节点身份核验（bpoc-node4 / 192.168.101.57）
- 容器状态与 openGauss 运行时依赖预检（gsql、lib、omm 用户、SELECT 1）
- openGauss safety_admin 真实 TCP 登录验证
- 25 张表与 21 个序列核验（local omm 管理只读查询）
- Kvrocks PING 连通性测试（REDISCLI_AUTH 安全验证）
- Java 17 与 Maven 环境确认
- Maven Clean Verify（严禁跳过测试）
- openGauss Compatibility Spike 实机 4 组测试
- 唯一可执行 Spring Boot JAR 筛选与校验
- 后端 tmux 进程拉起与 30 秒健康就绪探测
- /health/ready 聚合探针核验（DatabaseProbe UP / KvrocksProbe UP）
- 生成运行时核验报告并输出标准结算判定
