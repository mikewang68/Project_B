# TRUST · 可信存证与溯源

Project_B 的平台公共能力，版本 **0.1.0，开发联调版**。支持统一事件台账、IPFS 证据归档、Fabric 智能合约存证、失败补办、件料批次溯源、证据核验与导出。

业务事实来自来源系统或开发录入。原业务审批、现场控制和库存核算仍由业务系统负责。归档核验检查记录一致性与文件完整性，不证明实物或原始业务事实真实。

## 功能与验证状态

| 能力 | 本版内容 |
|---|---|
| 事件台账 | 中文录入、JSON/CSV 导入、API 接入；组织/来源/事件号幂等，异文冲突，迟到补齐 |
| 证据归档 | PDF、PNG、JPEG，可靠暂存、SHA-256、IPFS 固定保存、授权下载与取回校验 |
| 智能合约 | 真实 Fabric 登记、双组织背书、有效提交确认、追加更正与历史 |
| 状态与补办 | 文件和链状态分别显示，持久任务、自动重试、人工补办、重启续办、链上对账 |
| 件料溯源 | 批次、捆号、交接单、来源事件查询，来源与分批发运相互追溯 |
| 核验与导出 | 数据库/IPFS/Fabric 分项核验、证据 ZIP、离线文件摘要工具 |

2026-09-13 的独立开发环境中，100 吨钢材的十条模拟业务事件已全部真实归档并上链；60/40 吨两次发运可反查来源，两个实际证据包通过在线与离线核验。13 项真实接口/恢复检查与 8 项实际页面检查通过。业务样例和附件均明确标注为模拟材料。

2026-09-14 已从 Project_B/TRUST 发布新应用制品，原 19 条事件的摘要、CID 和交易关联保持一致。8 项真实页面回归及新增 10 项真实页面流程检查通过，覆盖附件上传、JSON/CSV 导入、更正、60/40 吨发运溯源、授权下载和在线核验后导出。详见 [本次发布与页面验收](docs/test-results/application-release-20260914.md)。

详见 [验证摘要](docs/test-results/README.md)、[实施状态](docs/implementation-status.md) 和 [逐条需求覆盖](docs/requirements-coverage.md)。协调备份恢复、特殊超时/异常退出、真实仓库文件缺失替换仍待补齐。

## 工程与节点

`TRUST/` 是普通子目录，使用 Project_B 的 Git 历史；开发、打包与同步均从本模块发起。源代码按 `backend`、`frontend`、`contracts`、`deploy`、`scripts`、`tests`、`samples`、`docs` 组织，内部规则见 [目录与依赖约定](docs/project-structure.md)。

| 节点角色 | 内容 | 本机监听 / 应用侧转发 |
|---|---|---|
| IPFS 独立节点 | Kubo 0.43.0，离线模式，固定保存 | 5001 / 25001；网关 8080 |
| 数据库节点 | openGauss 6.0.5 独立开发实例 | 25432 / 25432 |
| Fabric 节点 | Fabric 3.1.5；双组织、双 Peer、单 Raft Orderer | Peer 27051、29051，Orderer 27050；应用转发 27051 |
| 应用节点 | Java 后端、任务处理、Vue 中文页面 | 28182；开发电脑通过 SSH 转发到 18180 |

组件接口仅监听回环地址。IPFS 与 Fabric 分别搭建、管理和验收。首版单 IPFS 节点加备份，Fabric 组织为开发模拟组织；生产多副本、自动故障切换、正式组织治理及 X-RAFT 属于后续范围。

## 依赖

- Java 17、Maven 3.9.16；Spring Boot 3.5.16、Liquibase 4.33.0、openGauss JDBC 6.0.3-og。
- Fabric Gateway 1.12.1、gRPC 1.83.1、Protobuf 4.36.0；保持该组已验证版本一致。
- Node 24.18.0、pnpm 10.34.5、Vue 3.5.42、TypeScript 5.9.3；前端版本由锁文件固定。
- Go 1.26.4；合约依赖由 go.mod/go.sum 固定。
- 开发电脑使用 Python 3.10+、OpenSSH 和 PowerShell 7；服务器部署脚本兼容 Python 3.9+。生成模拟图片需要安装 `scripts/requirements.txt` 中的依赖。
- 浏览器检查使用 Chrome 无界面模式和前端安装的 Playwright，不操作日常浏览器会话。

## 环境配置

先配置并信任四台服务器的 SSH 别名。公开模板中的 `192.0.2.*` 是示例地址；必须替换成自己的环境。

在 `TRUST` 目录执行：

```powershell
New-Item -ItemType Directory -Force .local
Copy-Item deploy/deployment.example.json .local/deployment.json
# 填写配置后将 isExample 改为 false
python deploy/deployment.py
.\scripts\sync.ps1 -DryRun
# 既有服务已运行时，可只读核对配置与现场：
python scripts/check-environment.py
```

配置包含四个节点的角色、SSH 别名及 IPv4 地址、部署账号、服务器工程目录、数据库初始化账号、操作电脑和应用节点的允许来源地址。部署目录使用绝对 POSIX 路径，不含空格或 shell 特殊字符。四个角色必须位于独立机器。

加载顺序：显式 `TRUST_DEPLOYMENT_FILE`，否则本机 `.local/deployment.json`，否则服务器 `runtime/secrets/deployment.json`。显式指定的文件缺失时不会回退。密码及证书继续单独保存，不写入这份配置。

PowerShell 默认使用 PATH 中的 `python`，也可设置 `TRUST_PYTHON` 指向 Python 可执行文件。同步会单独传递私有部署配置，权限设为 600；源码包排除私有目录及父仓库。

## 初次安装与连接

1. 执行 `scripts/sync.ps1`，将模块源码及私有配置分发到四个配置节点。后续只同步某一角色时使用 `-Roles application` 等参数。脚本不自动重启服务。
2. **并行准备 IPFS 与 Fabric**：IPFS 节点依次执行 `bash deploy/ipfs.sh install`、`start`、`status`；Fabric 节点执行 `bash deploy/prepare-fabric.sh`，再依次执行 `bash deploy/fabric.sh init`、`start`、`join`、`deploy-contract`。
3. 数据库节点按 `deploy/extract-opengauss.py` 固定的官方 6.0.5 制品及摘要准备离线安装包，再执行 `bash deploy/database.sh install`、`init`、`start`、`create`。`create` 仅用于首次建库；库名 trust、schema trust_data，后续迁移由 Liquibase 执行。数据库初始化账号必须与配置一致。
4. 开发电脑执行 `python scripts/connect-environment.py`，生成或沿用项目专用密钥，核对既有 SSH 主机身份并配置限定转发。`--dry-run` 只检查配置，不修改服务器。
5. 如果服务器原策略禁止转发，由管理员在各节点的工程目录执行 `sudo python3 deploy/enable-project-forwarding.py --role ipfs --apply`，角色依次对应 ipfs/database/fabric/application。来源地址取配置；脚本核对本机地址，仅放行当前角色的回环端口。已有可用策略无需重复修改。
6. 应用节点构建后，执行 `bash deploy/tunnels.sh start`、`bash deploy/application.sh start`、`bash deploy/application.sh status`。启动命令最多等待 60 秒，健康检查通过后才报告就绪；IPFS/Fabric 的实际状态在登录后的运行状态页查看。
7. 开发电脑执行 `scripts/open-local.ps1`，访问 `http://127.0.0.1:18180`。开发账号由连接初始化工具写入 `.local/development-accounts.json`；管理员、录入员、查询员及跨范围测试账号分别配置。

独立启动 Fabric 时使用 `start` 和 `start-contract`，保留已有账本；不要用重新初始化代替日常启动。所有路径、账号和服务器地址以私有配置为准。

## 构建与日常开发

```powershell
# 从 TRUST 目录执行
python -m pip install -r scripts/requirements.txt
python -m unittest discover -s tests -p test_deployment.py
cd frontend
pnpm install --frozen-lockfile
pnpm build
cd ../backend
mvn -B -ntp clean verify
cd ../contracts
go test ./...
```

上面的后端构建验证 Java 代码；包含页面的完整制品由应用节点的 `bash deploy/application.sh build` 生成，该脚本先构建前端，再打包 JAR。源码更新后先同步，再按需要构建、停止和启动本工程应用。生产机器和既有服务不纳入本模块脚本的管理范围。

## 样例、接口与核验

```powershell
python scripts/seed-sample.py --prepare-only
# 实际应用与访问通道可用时提交样例：
python scripts/seed-sample.py
# 使用独立在线链上查询得到的清单摘要：
python scripts/verify-export.py evidence.zip --manifest-sha256 EXPECTED_SHA256
```

模拟样例涵盖到货、验收、过磅、卸货、入库、移库及 60/40 吨两次装车和发运。样例脚本先上传 PDF 和交接示意图，再提交事件，进度写入 `.local/sample-result.json`，支持幂等重跑。数量来自输入记录。

统一 API 前缀为 `/api/v1`，使用登录会话与 CSRF 校验。详见 [接口语义](docs/api.md) 和 [OpenAPI](docs/openapi.json)。组织范围由服务端账号确定，查询、下载和导出都执行鉴权及审计。

离线工具验证包内文件及参考摘要的一致性；导出的普通交易 JSON 不是独立链上密码学证明，链上真实性仍通过在线查询核验。

## 测试与运行资料

- `tests/test_deployment.py`：配置验证和源码打包隔离，不连接服务器。
- `tests/component-real.py`：真实 IPFS 与合约；随后可用其输出执行 `tests/recovery-components.py`。
- `deploy/test-database.sh`：独立 trust_test 数据库的集成测试，外部服务使用故障注入。
- `tests/cross-node-real.py`：实际应用样例、核验、权限与导出；加 `--faults` 会临时停止本工程依赖并验证恢复，只用于独立开发实例。
- 前端目录 `pnpm test:e2e`：实际应用无界面页面检查；传入 `--preview` 需要另行提供明确的界面预览服务。
- 前端目录 `pnpm test:e2e:write`：真实页面写入验收，默认使用私有账号文件中的录入员 `editor`；每次生成独立的 `UI-` 模拟批次，成功完成时新增 5 条事件与 2 份模拟附件。连接真实 IPFS/Fabric，只用于独立开发实例。结果输出到 `.local/test-results/UI-<本次标识>/`；失败时已提交记录会保留。可用 `TRUST_BASE_URL`、`TRUST_UI_ACCOUNT`、`TRUST_PYTHON` 指定应用入口、已有测试账号和离线核验所用 Python。
- `tests/offline-export-test.py`：合成证据包的离线正常、替换、缺失和错误参考摘要分支。

详细结果、截图和证据包默认保存在 `.local/test-results`，不提交 Git。公开目录仅保留日期、测试方式、结论和限制的摘要；历史原件由项目维护者本机保存。不得把模拟接口截图当作真实业务存证结果。

单文件上限 20 MiB，导入上限 200 行 / 1 MiB，IPFS 开发预算 10 GiB；不自动删除固定保存的证据。任务并行度 4，采用持久任务、租约和退避策略。

`scripts/backup.ps1` 编排应用暂存、IPFS 与数据库备份，`-DryRun` 只校验配置；单组件恢复工具位于 deploy。协调备份恢复仍待完整验收。凭据、备份和原始运行数据不存入 Git，备份不等同于在线多副本。

## 参考

- [Fabric 原生运行示例](https://github.com/hyperledger/fabric-samples/tree/main/test-network-nano-bash)
- [Fabric 外部链码](https://hyperledger-fabric.readthedocs.io/en/latest/cc_service.html)
- [IPFS 固定保存](https://docs.ipfs.tech/how-to/pin-files/)
- [openGauss 极简安装](https://docs.opengauss.org/zh/docs/6.0.0/docs/InstallationGuide/%E6%9E%81%E7%AE%80%E7%89%88%E5%AE%89%E8%A3%85.html)
