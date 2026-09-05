# 本机开发环境只读检查报告

## 2026-09-05 D 盘工程副本清理更新

本节是对下方 2026-09-04 只读快照的后续状态更新。为缩减 `D:\claude-code-workspace\Project b\Project_B\SCS` 的体积，已永久删除以下可重建依赖目录：

| 已删除目录 | 删除前大小 | 恢复方式 |
|---|---:|---|
| `SCS\node_modules` | 1193.92 MB | 在 SCS 根目录按锁文件安装 |
| `SCS\frontend\node_modules` | 178.61 MB | 同一次 pnpm workspace 安装恢复 |

本次共释放约 1372.53 MB。`package.json`、`frontend/package.json`、`pnpm-lock.yaml`、`pnpm-workspace.yaml`、前后端源码、配置和 `frontend/dist` 均未删除，已经部署到服务器的服务不受影响。

恢复依赖时必须使用项目锁定的 pnpm 10.34.5；当前电脑全局 pnpm 11.19.0 不符合前端声明的 `<11` 约束。在 PowerShell 中执行：

```powershell
cd "D:\claude-code-workspace\Project b\Project_B\SCS"
corepack pnpm@10.34.5 install --frozen-lockfile
```

该命令会依据根目录 `pnpm-lock.yaml` 恢复整个 workspace 的依赖。不要逐个安装依赖，也不要删除或重写原有锁文件。以下报告仍保留初次检查时的路径、版本和环境信息；凡涉及 `node_modules` 当前状态的内容，以本更新为准。

- 项目：智慧货场 S3「装卸作业安全卡控系统」（b-project-safety-gate，v0.2.0）
- 工程实际根目录：`C:\Users\xis\Desktop\项目开发\code\project B`
- 检查时间：2026-09-04（UTC+8）
- 检查方式：**只读探测**。未安装/卸载/升级任何软件，未修改 PATH/环境变量/注册表，未启停服务，未下载依赖，未改动项目代码。
- 目标技术栈：Spring Boot 3.5.5 / Java 17 / Maven / Vue 3 / TypeScript / Node.js / pnpm。
- 服务器侧组件（本机非必装）：openGauss、Kvrocks、RocketMQ、openGemini、Easegress、Nginx。

> 说明 1：本次探测由豆包桌面端发起，其运行时会在**当前会话进程**的 PATH 最前面注入自带运行时（Node v20.20.2、Python 3.13.13、corepack 0.34.6、pip 26.1.1，路径位于 `...\Doubao\User Data\...\sandbox_runtime\...`）。这不是用户系统的真实安装。本报告对关键工具均通过**绝对路径直接执行** + **读取注册表持久化 PATH（User/Machine）**两种方式还原了真实环境，并在相关章节明确区分。
>
> 说明 2：工程目录 `.tools/` 内自带一套**便携工具链**（JDK 17 / Maven 3.9.16 / Node 24.18 / pnpm 10.34.5），已逐一实测可用，是本工程的推荐构建链路。

---

## 1. 操作系统

| 项目 | 结果 |
|---|---|
| Windows 版本 | Microsoft Windows 11 **Pro Education** |
| Windows Build | 10.0.**26100**（Build 26100） |
| 系统架构 | **x64**（64-bit / amd64，非 ARM） |
| PowerShell | **5.1.26100.9168**（Desktop Edition，Windows PowerShell） |
| 当前用户名 | `xis` |
| 当前工作目录 | `C:\Users\xis\Desktop\项目开发` |

### WSL / WSL2

| 项目 | 结果 |
|---|---|
| `wsl.exe` | 存在：`C:\Windows\System32\wsl.exe`（Windows 内置命令） |
| WSL 子系统状态 | `wsl --status` 返回「未安装用于 Linux 的 Windows 子系统分发版，可通过 wsl.exe --install 安装」 |
| WSL 发行版 | **无**（`wsl -l -v` 无任何发行版） |
| WSL 版本 | 不适用（无发行版，无法判定 WSL1/WSL2） |
| 是否运行 | 否，无任何 WSL 实例 |

结论：**WSL 不可用（仅有外壳命令，未装发行版）**。第 13 节「WSL 开发能力」因此为 N/A。属可选项，不阻塞 Windows 本地开发。

---

## 2. Git

| 项目 | 结果 |
|---|---|
| `git --version` | **git version 2.45.1.windows.1** |
| `where.exe git` | `C:\Program Files\Git\cmd\git.exe`（仅一份，无多版本） |
| 附带 Git Bash | `C:\Program Files\Git\bin\bash.exe` 存在 |
| 全局 user.name / user.email | **均未配置**（`git config --global` 返回空；提交代码前需配置，非环境阻塞） |
| 工程 Git 仓库 | 工程根目录**不存在 `.git`**（当前不是 Git 工作区） |

状态：**READY**。

---

## 3. Java / JDK

### 3.1 当前会话默认 Java

| 项目 | 结果 |
|---|---|
| `java -version` | **Java(TM) SE 21.0.8 LTS**（Oracle，build 21.0.8+12-LTS-250，2025-07-15） |
| `javac -version` | **javac 21.0.8** |
| `where.exe java` | `D:\Jdk21\bin\java.exe`（会话内仅命中这一份） |
| `where.exe javac` | `D:\Jdk21\bin\javac.exe` |
| `JAVA_HOME` | `D:\Jdk21`（注册表 **Machine 级**持久化；会话值一致） |
| PATH 中 Java 来源（会话） | `D:\Jdk21\bin`（由父进程注入到会话 PATH） |

### 3.2 全部已识别 JDK（共 3 处，常见安装目录已逐一排查）

| # | 路径 | 版本 | 来源/性质 |
|---|---|---|---|
| 1 | `D:\Jdk21` | **Oracle JDK 21.0.8**（含 java/javac/javaw/jshell） | 用户独立安装，`JAVA_HOME` 指向它 |
| 2 | `C:\Users\xis\Desktop\项目开发\code\project B\.tools\jdk-17.0.20.1+1` | **Eclipse Temurin JDK 17.0.20.1**（已实测 `java -version` 正常） | **工程自带便携 JDK 17** |
| 3 | `D:\software\idea\jbr` | IntelliJ IDEA 自带 JBR（仅 IDE 内部使用，不计入开发 JDK） | IDE 运行时 |

- `C:\Program Files\Java`、`Eclipse Adoptium`、`BellSoft`、`Zulu`、`Amazon Corretto` 等常见目录**均不存在 JDK**。
- `C:\Program Files\Common Files\Oracle\Java\javapath` 为 Oracle 的 java 软链接目录（指向同一套 21），**未出现在有效 PATH 中**。
- 持久化（注册表）PATH 的特别发现：**Machine 级 PATH 中写入了工程便携 JDK17 的 bin 目录**（`...\project B\.tools\jdk-17.0.20.1+1\bin`，且排在 system32 之前），但**未写入 `D:\Jdk21\bin`**。即：新开的普通终端里首个命中的 `java` 反而是工程 JDK 17；而 `JAVA_HOME` 仍指向 JDK21，二者存在不一致（见第 18 节风险）。

### 3.3 是否满足 Java 17

- **系统全局默认：不满足（VERSION MISMATCH）**——默认是 JDK 21，且本工程 `maven-enforcer-plugin` 硬性要求 `requireJavaVersion [17,18)`，**JDK 21 构建会被直接拒绝**。
- **工程层面：满足（READY）**——`.tools` 内 Temurin **17.0.20.1** 实测可用，且后端上次成功运行使用的正是它（见第 17 节冒烟日志）。

---

## 4. Maven

### 4.1 全局 Maven

| 项目 | 结果 |
|---|---|
| `mvn -version` | **Apache Maven 3.6.1**（2019-04-05） |
| Maven 路径 | `D:\software\apache-maven-3.6.1`（`where mvn`：`...\bin\mvn`、`mvn.cmd`） |
| 使用的 Java | 21.0.8（Oracle），runtime `D:\Jdk21` |
| `MAVEN_HOME` | `D:\software\apache-maven-3.6.1`（Machine 级持久化） |
| `M2_HOME` | 未设置 |
| platform encoding | UTF-8 |

### 4.2 工程自带 Maven（.tools）

| 项目 | 结果 |
|---|---|
| 版本 | **Apache Maven 3.9.16**（指定 JAVA_HOME=工程 JDK17 时实测） |
| 路径 | `.tools\apache-maven-3.9.16` |
| 使用的 Java | **17.0.20.1 Temurin**（符合要求） |

### 4.3 Maven Wrapper

| 项 | 结果 |
|---|---|
| `backend/mvnw` | **不存在** |
| `backend/mvnw.cmd` | **不存在** |
| `backend/.mvn/` | 存在，但**只含配置、不含 wrapper**：`.mvn/jvm.config`（`-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai`）、`.mvn/settings.xml`（central 镜像 `https://repo.maven.apache.org/maven2`），无 `maven-wrapper.jar` |

### 4.4 本地依赖缓存

- `C:\Users\xis\.m2\repository` 存在，已缓存约 **497 个 jar**，具备离线/弱网构建的基础。

### 4.5 结论

- 全局 Maven **3.6.1 不满足**工程 enforcer 的 `requireMavenVersion [3.9,4)`（VERSION MISMATCH）。
- 工程自带 **Maven 3.9.16 + JDK17 组合 READY**，应使用它构建。本次未执行任何依赖下载。

---

## 5. Node.js / npm

### 5.1 三份 Node 辨析（重要）

| # | 路径 | node 版本 | npm 版本 | 性质 |
|---|---|---|---|---|
| 1 | `...\Doubao\...\sandbox_runtime\...\node` | v20.20.2 | 10.8.2 | **豆包运行时注入**，仅存在于本次探测会话，非用户安装 |
| 2 | `D:\nodejs`（用户真实全局安装） | **v24.13.0** | **11.6.2** | 持久化 PATH 中的真实 Node（Machine+User PATH 均有） |
| 3 | `.tools\node-v24.18.0-win-x64`（工程自带） | **v24.18.0** | **11.16.0** | 工程便携 Node，已实测可用 |

- `nvm`：**NOT INSTALLED**（无 nvm-windows，`where nvm` 无结果）。
- 失效 PATH 项：`E:\Program Files\nodejs`（Machine/User PATH 均有残留，但该目录**已不存在**）；Machine PATH 中还残留字面量 `%NODE_PATH%`；`NODE_PATH`（Machine）=`D:\nodejs\node_global\node_modules`。
- 前端 `engines` 硬性要求 `node >=24.18.0 <25`：全局 **v24.13.0 不满足（偏低）**，工程 **v24.18.0 满足**。

---

## 6. pnpm

| 项目 | 结果 |
|---|---|
| 会话命中的 pnpm | `D:\koumuku\projectb\test02\.tools\pnpm\pnpm.ps1`（**其他项目的残留 shim，被写进了 User PATH 第一项**） |
| 该全局 shim 状态 | **运行失败/不可用**：其 pnpm 要求 Node ≥ v22.13，并依赖内置模块 `node:sqlite`；在会话 Node v20.20.2 下直接崩溃（`ERR_UNKNOWN_BUILTIN_MODULE`）。即便在更高 Node 下可运行，也属于外部项目目录，脆弱且不应依赖 |
| 工程 pnpm | `.tools\pnpm-local`，**pnpm 10.34.5**，用工程 Node v24.18.0 实测 `--version` 正常输出 10.34.5 |
| `packageManager` 字段 | 根 `package.json` 声明 **pnpm@10.34.5**，与 pnpm-local 完全一致 |
| `corepack --version` | 会话命中豆包注入版 **0.34.6**（D:\nodejs 亦随附 corepack，未单独计版本） |
| yarn | **NOT INSTALLED** |

结论：**全局 pnpm 实际不可用（BROKEN，属“真正缺失/需清理”项）**；但工程内 pnpm-local 10.34.5 **READY**。

---

## 7. Vue / TypeScript 前端工程情况

工程为 pnpm workspace：根 `pnpm-workspace.yaml` → `packages: [frontend]`。

### 7.1 根 package.json

- name：`b-project-safety-gate`，private，version 0.2.0，**packageManager：pnpm@10.34.5**。

### 7.2 frontend/package.json（声明版本 → 实际安装版本）

| 依赖 | package.json 声明 | node_modules 实测 |
|---|---|---|
| **Vue** | ^3.5.20 | **3.5.41** |
| **TypeScript** | ^5.9.2 | **5.9.3** |
| **Vite** | ^7.1.3 | **7.3.6** |
| **ECharts** | 6.1.0（精确） | **6.1.0** |
| Element Plus | ^2.11.1 | 2.14.5 |
| Pinia | ^3.0.3 | 3.0.4 |
| vue-router | ^4.5.1 | 4.6.4 |
| vue-tsc | ^3.0.6 | 3.3.11 |

- engines：`node >=24.18.0 <25`、`pnpm >=10.34.5 <11`。
- scripts：

| 脚本 | 命令 |
|---|---|
| dev | `vite --host 0.0.0.0`（默认端口 5173） |
| build | `vue-tsc -b && vite build` |
| preview | `vite preview --host 0.0.0.0`（默认 4173） |
| typecheck | `vue-tsc -b` |
| test | `vitest run` |
| lint | `eslint . --max-warnings 0` |

### 7.3 依赖与锁文件状态

| 项 | 结果 |
|---|---|
| 根 `node_modules` | **已于 2026-09-05 清理；可根据锁文件恢复** |
| `frontend/node_modules` | **已于 2026-09-05 清理；由 workspace 安装恢复** |
| `frontend/dist` | 存在（已执行过构建） |
| `pnpm-lock.yaml` | **仅根目录一份**，lockfileVersion 9.0 |
| `package-lock.json` | 不存在 |
| `yarn.lock` | 不存在 |
| 多 lock 冲突风险 | **无**（只有 pnpm 一种锁文件） |

- 环境变量样例：仅 `frontend/.env.production.example`（样例文件）；**不存在真实 `.env` / `.env.production`**。
- 结论：**Vue3 + TS 工程可被正确识别且曾成功构建；依赖目录已清理，重新执行锁定安装后可恢复开发与构建。**

---

## 8. Python 辅助环境（后端不依赖，仅记录）

| 来源 | 版本 / 位置 |
|---|---|
| 会话默认 python | 3.13.13（豆包 sandbox 注入，非用户安装） |
| Anaconda3 | **conda 24.11.3**，`D:\software\anaconda3`，base 解释器 **Python 3.12.7** |
| 独立 Python | `D:\python\python.exe`，**Python 3.13.5** |
| WindowsApps 别名 | `python.exe` / `python3.exe` 商店别名存在 |
| pip | 会话命中 sandbox pip 26.1.1；Anaconda / D:\python 各自带 pip |

`conda env list` 环境：`base`、`agentDemo`、`bloodmnist`、`fewshot_aug`、`ml_final`、`ml_lab1`、`pytorch`（另有 codegeex mamba 环境一条）。未做任何修改。

---

## 9. 数据库客户端

| 客户端 | 状态 | 路径 / 备注 |
|---|---|---|
| `psql`（PostgreSQL） | **NOT INSTALLED** | — |
| **`gsql`（openGauss）** | **NOT INSTALLED** | 本机没有属正常，openGauss 在服务器侧（SERVER-ONLY） |
| `mysql`（CLI） | **NOT INSTALLED** | 但本机存在 **MySQL 服务端**：服务 `MySQL94` 正在运行，占用 3306（见第 10/15 节），命令行客户端未入 PATH |
| `sqlite3` | **INSTALLED** | `D:\software\anaconda3\Library\bin\sqlite3.exe`（Anaconda 附带） |
| `redis-cli` | **NOT INSTALLED** | — |
| `mongosh` | **NOT INSTALLED** | — |

---

## 10. Redis / Kvrocks

| 检查项 | 结果 |
|---|---|
| `redis-server` | NOT INSTALLED |
| `redis-cli` | NOT INSTALLED |
| Kvrocks 相关命令/程序 | 未发现 |
| Windows 服务中的 Redis / Kvrocks | **无**（Get-Service 无任何匹配） |
| 端口 6379 | 空闲 |
| 应用默认 Kvrocks 端口 | `application.yml` 中为 **6666**（`KVROCKS_PORT`，当前也空闲） |

结论：本机**未安装** Redis/Kvrocks。二者属 **SERVER-ONLY / OPTIONAL FOR LOCAL**，不阻塞本地开发（后端以 Probe 探针形式降级，见第 17 节）。

---

## 11. RocketMQ

| 检查项 | 结果 |
|---|---|
| `ROCKETMQ_HOME` | 空（User/Machine/Process 均未设置） |
| `mqnamesrv` / `mqbroker` / `mqadmin` | **全部 NOT INSTALLED** |
| PATH / 常见目录 | 未发现 RocketMQ |
| NameServer 端口 9876 / Broker 10911、10909、10912 | 全部空闲 |
| 应用开关 | `app.rocketmq.enabled` 默认 **false**（`ROCKETMQ_ENABLED`） |

结论：**NOT INSTALLED，SERVER-ONLY / OPTIONAL FOR LOCAL**，默认关闭，不阻塞本机启动。

---

## 12. Docker / 容器

| 项目 | 结果 |
|---|---|
| `docker --version` | **Docker 29.7.2**（a7dcaa6） |
| `docker compose version` | **Docker Compose v5.5.0** |
| `where docker` | `C:\Users\xis\AppData\Local\Programs\DockerDesktop\resources\bin\docker(.exe)`（按用户安装的 Docker Desktop） |
| `docker info`（守护进程） | **失败：守护进程未运行**（`npipe:////./pipe/dockerDesktopLinuxEngine` 找不到；无 docker 进程）。按只读要求**未代为启动** Docker Desktop |
| `podman` | NOT INSTALLED |

结论：Docker 客户端 **INSTALLED**，但 **daemon 未启动（PARTIALLY READY）**；需要容器时手动启动 Docker Desktop 即可。属可选项。

---

## 13. WSL 开发能力

因第 1 节已确认**没有任何 WSL 发行版、子系统未完成安装**，无法在 WSL 内执行 `java/mvn/node/npm/pnpm/git/docker` 检查，本节 **N/A**。Windows 与 WSL 环境无需合并记录：WSL 侧为空。未执行任何安装。

---

## 14. IDE / 开发工具（仅记录是否存在与路径，未扫描私人文件）

| 工具 | 状态 | 路径 / 版本 |
|---|---|---|
| Visual Studio Code | **INSTALLED** | `D:\software\Microsoft VS Code`，版本 **1.108.2**（x64），`code` 在 PATH |
| Cursor | NOT INSTALLED | 常见目录与 PATH 均无 |
| IntelliJ IDEA | **INSTALLED** | `D:\software\idea`，**IntelliJ IDEA 2025.1.1.1**（build 251.25410.129），配置目录 `...\AppData\Local\JetBrains\IntelliJIdea2025.1` |
| PyCharm | **INSTALLED** | `D:\pycharm\PyCharm 2024.1.7`（User PATH 含其 bin） |
| Git Bash | **INSTALLED** | `C:\Program Files\Git\bin\bash.exe`（随 Git 2.45.1） |
| Windows Terminal | **INSTALLED** | `...\AppData\Local\Microsoft\WindowsApps\wt.exe` |

---

## 15. 常用端口占用（只读，未结束任何进程）

| 端口 | 状态 | PID | 进程 |
|---|---|---|---|
| 5173（Vite dev） | 空闲 | — | — |
| 3000 | 空闲 | — | — |
| 8080（后端默认） | 空闲 | — | — |
| 8081 | 空闲 | — | — |
| 8082 | 空闲 | — | — |
| 5432（openGauss/PG） | 空闲 | — | — |
| **3306（MySQL）** | **占用** | **8044** | **mysqld（Windows 服务 MySQL94，自启）** |
| 6379（Redis） | 空闲 | — | — |
| 9876（RocketMQ NameServer） | 空闲 | — | — |
| 10909 / 10911 / 10912（RocketMQ） | 空闲 | — | — |

补充：应用 Kvrocks 默认端口 6666 亦空闲。后端 8080、前端 5173 当前均无冲突。本机 MySQL94 与本项目无关（项目使用 openGauss），仅记录。

---

## 16. 当前项目结构

会话选定目录 `C:\Users\xis\Desktop\项目开发` 顶层：

```
项目开发/
├─ .pnpm-store/          # pnpm 全局内容寻址存储（v11，当前基本为空）
├─ .wp0-clean/           # 历史清理工作区
├─ code/
│  └─ project B/         # ★ 实际工程根目录
├─ ppt/
└─ 文档/                  # 历史核对/迁移/功能清单文档（4 份 md + 1 docx）
```

`code/project B/` 结构（已忽略 node_modules 内部展开）：

```
project B/
├─ .tools/                         # ★ 工程自带便携工具链（JDK17/Maven3.9.16/Node24.18/pnpm10.34.5 + 各自 zip 与校验文件）
├─ backend/
│  ├─ .mvn/ (jvm.config, settings.xml)
│  ├─ src/main/java/com/bproject/safety/...   # 26 个 .java
│  ├─ src/main/resources/ (application.yml, logback-spring.xml)
│  ├─ src/test/java/...
│  ├─ target/ (safety-gate-service-0.2.0.jar 等，见第 17 节)
│  └─ pom.xml                      # ★ 已存在
├─ frontend/
│  ├─ src/、dist/、node_modules/
│  ├─ package.json、vite.config.ts、vitest.config.ts
│  ├─ tsconfig*.json、eslint.config.js、index.html
│  └─ .env.production.example      # 仅样例
├─ deploy/ (backend service、easegress 模板、nginx 模板、scripts)
├─ docs/ (backend-demo-api-inventory.json、backend-demo-api-requirements.md)
├─ package.json、pnpm-lock.yaml、pnpm-workspace.yaml
├─ node_modules/                    # 2026-09-05 已清理，可按锁文件恢复
├─ README.md、.gitignore
├─ .config.example.json
└─ .config.json                    # 隐藏文件（敏感配置，仅报告存在，未读取内容）
```

关键项核对：`frontend/`、`backend/`、`docs/`、`package.json`、`pom.xml`、`.mvn/` 均**存在**；`mvnw/mvnw.cmd`、`.git`、真实 `.env` **不存在**。

敏感信息处理：发现敏感配置文件 **`.config.json`（隐藏，504B）**，按要求**只报告存在、未打印内容**；`application.yml` 中的数据库/Kvrocks 口令以环境变量注入，本报告不展示其值；另存在 `deploy/backend/backend.env.example` 样例。

---

## 17. 当前 Spring Boot 工程情况（只读分析 pom.xml）

### 17.1 坐标与基线

| 项 | 值 |
|---|---|
| parent | **spring-boot-starter-parent 3.5.5** |
| groupId | `com.bproject` |
| artifactId | `safety-gate-service` |
| version | 0.2.0，finalName `safety-gate-service` |
| `<java.version>` | **17** |
| 构建强制约束 | maven-enforcer-plugin 3.6.1：**requireJavaVersion [17,18)、requireMavenVersion [3.9,4)**（JDK21 / Maven3.6.1 都会被拒绝） |

### 17.2 关键依赖核查表

| 能力 | 是否具备 | 依赖 / 说明 |
|---|---|---|
| **openGauss 驱动** | ✅ 有 | `org.opengauss:opengauss-jdbc:6.0.0`（明确不用 PG/MySQL 驱动替代） |
| **Redis（兼容 Kvrocks）** | ✅ 有 | `spring-boot-starter-data-redis`（Lettuce） |
| **RocketMQ** | ✅ 有 | 官方 `rocketmq-client` + `rocketmq-tools` **5.5.0**（不用第三方 starter，规避 Jakarta 问题） |
| **Swagger / OpenAPI** | ✅ 有 | `springdoc-openapi-starter-webmvc-ui:2.7.0`（/swagger-ui.html、/v3/api-docs） |
| **Actuator** | ✅ 有 | `spring-boot-starter-actuator` + `micrometer-registry-prometheus`（暴露 health/info/prometheus） |
| **JDBC** | ✅ 有 | `spring-boot-starter-jdbc`（HikariCP + JdbcTemplate） |
| **JPA** | ❌ 无 | 本阶段刻意不引入 ORM |
| **MyBatis** | ❌ 无 | — |
| **Lombok** | ❌ 无 | — |
| **WebSocket** | ❌ 无 | — |
| Web/校验/测试 | ✅ | starter-web、starter-validation、starter-test |

### 17.3 代码骨架与已验证运行情况

- 包结构 `com.bproject.safety`：`common`（统一错误、幂等、TraceId）、`config`（OpenApi/Web）、`controller`（Auth/Health/Meta）、`infrastructure`（database/cache(Kvrocks)/mq(RocketMQ)/timeseries(OpenGemini) 的 Probe 与 Adapter、health 聚合）、`module`（占位）、`support`（demo 用户、字典种子），启动类 `SafetyApplication`，共 26 个 Java 文件。
- 已构建产物：`backend/target/safety-gate-service-0.2.0.jar`（约 **28.7 MB** 可执行 fat jar）。
- **冒烟日志 `target/smoke.out.log` 证明：2026-09-03 20:47 该工程使用 Java 17.0.20.1 + Spring Boot 3.5.5（Tomcat 10.1.44）成功启动，监听 127.0.0.1:8080，约 5 秒启动完成**；当时本机并无 openGauss/Kvrocks（5432/6666 空闲），应用仍正常 Started——说明基础设施探针为**降级探测**，缺中间件不阻塞启动。
- 配置要点（`application.yml`，默认 profile `dev`，全部可被环境变量覆盖）：端口 8080；openGauss 默认 `127.0.0.1:5432/b_project`；Kvrocks 默认 `127.0.0.1:6666`；**RocketMQ 默认 enabled=false、OpenGemini 默认 enabled=false**；CORS 已放行 5173/4173。

---

## 18. 环境兼容性判断

状态词：**READY** / **PARTIALLY READY** / **MISSING** / **VERSION MISMATCH** / **SERVER-ONLY**。

### 18.1 目标一：Spring Boot 3.5.5 + Java 17

| 维度 | 全局系统环境 | 工程 .tools 链路 | 判定 |
|---|---|---|---|
| JDK | 默认 **JDK 21.0.8**，落在 enforcer `[17,18)` 之外 | **Temurin 17.0.20.1** | 全局 VERSION MISMATCH；工程内 **READY** |
| Maven | **3.6.1**，低于 enforcer `[3.9,4)` | **3.9.16** | 全局 VERSION MISMATCH；工程内 **READY** |
| 构建可行性 | 全局组合会被 enforcer 拒绝 | 已有成功构建产物 + 成功启动日志 | **用 .tools 即 READY** |
| Git | 2.45.1 | — | **READY** |

### 18.2 目标二：Vue 3 + TypeScript

| 维度 | 全局系统环境 | 工程 .tools 链路 | 判定 |
|---|---|---|---|
| Node | D:\nodejs **v24.13.0**，低于 engines `>=24.18.0` | **v24.18.0** | 全局 VERSION MISMATCH（偏低）；工程内 **READY** |
| pnpm | PATH 命中**其他项目残留 shim 且会崩溃**，无正常全局安装 | **pnpm-local 10.34.5** | 全局 MISSING/BROKEN；工程内 **READY** |
| Vue3/TS 依赖 | — | 版本由锁文件固定，`node_modules` 已清理，dist 仍保留 | **恢复依赖后 READY** |

### 18.3 总体结论

- **后端：PARTIALLY READY（系统层面）→ 按工程 .tools 工具链操作为 READY。**
- **前端：PARTIALLY READY（系统层面）→ 按工程 .tools 工具链操作为 READY。**
- 与服务器“版本不同但不影响开发”的项：本机用 JDK17/Maven3.9.16/Node24.18/pnpm10 开发，服务器运行时为 openGauss/Kvrocks/RocketMQ/openGemini/Easegress/Nginx——通过 dev profile、探针降级、环境变量切换、后续服务器联调解决，不要求本机同版本安装。

---

## 19. 服务器组件定位（非本机必装，不计开发阻塞）

| 组件 | 本机状态 | 定位 | 本地替代路径 |
|---|---|---|---|
| openGauss | 服务端/gsql 均无 | **SERVER-ONLY / OPTIONAL FOR LOCAL** | dev profile + 探针降级；需要时连测试库或 H2/内存方案（当前 JDBC 驱动为 openGauss 专用，换库需评估） |
| Kvrocks | 无 | **SERVER-ONLY / OPTIONAL FOR LOCAL** | Lettuce 探针降级 / Mock；默认端口 6666 |
| RocketMQ | 无，且默认 `enabled=false` | **SERVER-ONLY / OPTIONAL FOR LOCAL** | 本地关闭开关，联调环境再启用 |
| openGemini | 无客户端/服务，默认 `enabled=false` | **SERVER-ONLY / OPTIONAL FOR LOCAL** | 本地关闭，HTTP 探针降级 |
| Easegress | 无（命令不存在），仅 `deploy/easegress/*.template` | **SERVER-ONLY** | 本地前端直连后端（5173→8080），网关部署在服务器 |
| Nginx | 无（命令不存在），仅 `deploy/nginx/*.template` | **SERVER-ONLY** | 本地用 Vite dev server，无需 Nginx |

---

## 20. 环境问题与风险清单（仅记录，未做任何修复）

1. **全局默认 JDK 与工程要求冲突**：`JAVA_HOME=D:\Jdk21`（21），而 enforcer 只接受 `[17,18)`；必须用工程 `.tools` JDK17 构建。
2. **全局 Maven 过旧**：3.6.1 不满足 `[3.9,4)`；必须用 `.tools` Maven 3.9.16。
3. **全局 Node 偏低**：D:\nodejs 为 24.13.0，不满足前端 engines `>=24.18.0 <25`；用 `.tools` Node 24.18.0。
4. **全局 pnpm 实际不可用**：User PATH 第一项 `D:\koumuku\projectb\test02\.tools\pnpm` 是**别的工程残留**，在低版本 Node 下直接崩溃；建议日后清理该 PATH 项（本次未改），本机开发统一用 `.tools/pnpm-local`。
5. **PATH 失效/矛盾项**：`E:\Program Files\nodejs` 已不存在但仍在 Machine/User PATH；Machine PATH 含字面量 `%NODE_PATH%`；Machine PATH 把“工程内便携 JDK17 bin”写进了系统 PATH（一旦移动工程目录会失效），与 JAVA_HOME(JDK21) 不一致。
6. **无 Maven Wrapper**：有 `.mvn/` 配置但缺 `mvnw/mvnw.cmd/wrapper jar`，不能靠 `./mvnw` 自动锁定 Maven 版本。
7. **工程不是 Git 仓库**（无 `.git`），且 git 全局 user.name/user.email 未配置。
8. **多 Python 并存**（Anaconda 3.12.7 / D:\python 3.13.5 / 商店别名 / 会话注入 3.13.13），与后端无关，仅提示注意解释器选择。
9. **Docker 守护进程未启动**（客户端已装）；**WSL 无发行版**。两者均可选。
10. **敏感配置**：根 `.config.json` 为隐藏敏感文件（未读取）；口令类配置走环境变量，注意不要把真实 `.env` 提交入库。
11. 本机 **MySQL94 服务自启并占用 3306**，与本项目无关；若未来本地排障注意区分（项目默认连 openGauss 5432）。

### 真正缺失（影响“脱离 .tools”的全局开发，但均有工程内替代，不构成当前阻塞）

- 全局默认 JDK 17（现为 21）
- 全局 Maven ≥ 3.9（现为 3.6.1）
- 全局 Node ≥ 24.18（现为 24.13.0）
- 一个正常可用的全局 pnpm（现 PATH pnpm 为外部残留且崩溃）
- 可选缺失：nvm、yarn、WSL 发行版、Docker daemon 运行态、gsql/psql/redis-cli 等客户端

### 仅服务器侧、本机无需安装

openGauss、Kvrocks、RocketMQ、openGemini、Easegress、Nginx。

---

## 21. Backend Demo 本机运行建议（仅建议，未代为执行；不触发下载/安装）

> 统一原则：**不要使用全局 JDK21 / Maven3.6.1 / Node24.13 / D:\koumuku 残留 pnpm**，统一改用工程 `.tools`。以下命令在 `code\project B` 目录下执行。

后端（PowerShell，临时只影响当前窗口，不改系统环境变量）：

```powershell
# 1) 指定工程自带 JDK17
$env:JAVA_HOME = (Resolve-Path '.\.tools\jdk-17.0.20.1+1').Path
# 2) 用工程自带 Maven 3.9.16 构建（使用工程 .mvn/settings.xml；依赖已在 ~/.m2 缓存）
.\.tools\apache-maven-3.9.16\bin\mvn.cmd -s .\backend\.mvn\settings.xml -f .\backend\pom.xml clean package
# 3) 运行（默认 dev profile；RocketMQ/OpenGemini 默认关闭；无 openGauss/Kvrocks 时探针降级，不阻塞启动）
java -jar .\backend\target\safety-gate-service-0.2.0.jar
# 访问：http://127.0.0.1:8080/actuator/health、http://127.0.0.1:8080/swagger-ui.html
```

前端（PowerShell，使用工程 Node + pnpm-local；`node_modules` 已于 2026-09-05 清理，首次运行前必须重新安装）：

```powershell
# 用工程 Node 运行工程 pnpm 10.34.5
$pnpm = '.\.tools\pnpm-local\node_modules\pnpm\bin\pnpm.cjs'
$node = '.\.tools\node-v24.18.0-win-x64\node.exe'
& $node $pnpm install --frozen-lockfile
& $node $pnpm --dir .\frontend dev      # 启动 Vite，http://localhost:5173（后端 CORS 已放行）
# 其它：& $node $pnpm --dir .\frontend typecheck / build / test / lint
```

- 端口：8080、5173 当前均空闲，可直接起。
- 需要联调服务器中间件时，再通过 `OPENGAUSS_HOST/PORT`、`KVROCKS_HOST/PORT`、`ROCKETMQ_ENABLED=true`、`ROCKETMQ_NAMESRV_ADDR`、`OPENGEMINI_ENABLED=true` 等环境变量切换，无需在本机安装这些服务。
- 本次检查**未执行**上述任何构建/运行命令，未做任何自动修复。

---

## 22. 总结表

| 项目 | 当前版本 / 状态 | 项目要求 | 是否满足 |
|---|---|---|---|
| **Java 17** | 全局默认 JDK **21.0.8**（D:\Jdk21，VERSION MISMATCH）；工程自带 **Temurin 17.0.20.1** 可用 | Java 17（enforcer [17,18)） | **工程内满足；全局默认不满足** |
| **Maven 可构建** | 全局 **3.6.1**（不达标）；工程自带 **3.9.16**，已有构建产物与成功启动记录 | Maven ≥3.9（[3.9,4)） | **用 .tools 满足** |
| **Spring Boot** | 工程已存在，parent **3.5.5**，fat jar 已产出并成功跑通过 | Spring Boot 3.5.5 | **满足（工程已存在）** |
| **Node.js** | 全局 **24.13.0**（偏低）；工程自带 **24.18.0**；另有会话注入 20.20.2（非用户安装） | ≥24.18.0 <25 | **用 .tools 满足；全局偏低** |
| **Vue3 / TS** | Vue 3.5.41、TypeScript 5.9.3、Vite 7.3.6、ECharts 6.1.0 由锁文件固定；依赖目录已清理，dist 保留 | Vue3 + TypeScript | **恢复依赖后满足** |
| **pnpm** | 全局为外部残留 shim（崩溃，BROKEN）；工程 **pnpm-local 10.34.5** 正常 | pnpm ≥10.34.5 <11 | **用 .tools 满足；全局缺失** |
| **Git** | **2.45.1.windows.1**（单份，含 Git Bash） | 版本控制 | **满足** |
| **Docker（可选）** | 客户端 29.7.2 / Compose v5.5.0，**守护进程未启动**，podman 无 | 可选 | **已装未运行（可选）** |
| **WSL（可选）** | 仅 wsl.exe，**无发行版/未启用** | 可选 | **不具备（可选，不阻塞）** |
| **openGauss（服务器提供）** | 本机无服务端、无 gsql | 服务器提供 | **SERVER-ONLY，本机无需安装** |
| **Kvrocks（服务器提供）** | 本机无 | 服务器提供 | **SERVER-ONLY，本机无需安装** |
| **RocketMQ（服务器提供）** | 本机无，默认 enabled=false | 服务器提供 | **SERVER-ONLY，本机无需安装** |
| **openGemini（服务器提供）** | 本机无，默认 enabled=false | 服务器提供 | **SERVER-ONLY，本机无需安装** |
| Easegress / Nginx（服务器提供） | 本机均无，仅 deploy 模板 | 服务器提供 | **SERVER-ONLY，本机无需安装** |

---

### 一句话结论

后端 Spring Boot 3.5.5 工程与前端 Vue3/TS 工程**均已存在且可被识别**；请以工程自带 **`.tools`（JDK 17.0.20.1 + Maven 3.9.16 + Node 24.18.0 + pnpm 10.34.5）** 作为唯一构建链路——该链路 READY，且后端此前已在该链路上成功构建并启动；系统全局的 JDK21 / Maven3.6.1 / Node24.13 / 残留 pnpm 与工程要求不匹配，请勿使用。openGauss、Kvrocks、RocketMQ、openGemini、Easegress、Nginx 均为服务器侧组件，本机无需安装，不构成开发阻塞。本次为纯只读检查，未做任何安装、修改或自动修复。
