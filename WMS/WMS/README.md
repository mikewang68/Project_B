# MT-WMS 重构项目

本目录是旧项目 `mt-wms-demo` 与 `mt-wms-demo-frontend` 的重写工程。旧项目保持只读，用于行为对照和迁移验收。

实施基线是仓库根目录的《MT-WMS重构实施计划.md》。当前只迁移旧系统已经存在的功能，不以其他需求规格说明书扩展范围。

## 当前进度

- 阶段 0：完成旧源码模型、接口、页面、菜单、权限、配置和状态清单。
- 阶段 1：已完成；前后端测试、单 JAR、openGauss 6.0.5、版本迁移、页面/API 和浏览器验收全部通过。
- 阶段 2：已完成；登录、退出、改密、角色权限、动态菜单、仓库/货主上下文和用户维护均已通过验收。
- 阶段 3：已完成；仓库、库区、工作区、库位、货主、合作伙伴、货类、货品、组合件和业务单号均已通过验收。
- 阶段 4：已完成；库存台账、流水、预警、冻结/解冻、调整、移库、盘点、自动补货和 RFID/唯一标签均已通过验收。
- 阶段 5：已完成；多明细入库单、分次收货、快捷入库、条码/RFID、库位推荐、强制完成和取消均已通过验收。
- 阶段 6：已完成；出库单、分配、条码/RFID 拣货、装箱、发运、波次、退库反单和调拨联动均已通过验收。
- 阶段 7：已完成；应收应付、收付款流水、经营统计、ECharts、Excel 任务和奇门接口均已通过验收。
- 阶段 8：已完成；`.demo-data` 中的 SQLite 业务库和认证库已真实迁入 openGauss，迁移、去重、对账、性能、备份恢复和页面验收均已通过。

阶段 0 的人工结论见 `docs/phase-0/`，可重复生成的源码盘点见 `docs/phase-0/generated/`。

## 本地运行

确保 Docker Desktop 已启动，然后双击根目录的 `run-local.cmd`，或在 PowerShell 执行：

```powershell
.\scripts\start-local.ps1
```

访问 `http://127.0.0.1:8080/`。停止服务：

```powershell
.\scripts\stop-local.ps1
```

当前迁移后验收账号：公司代码 `default`，账号 `admin0`，密码 `Admin@123456`，首次登录会要求修改密码。原有 `admin` 账号已修改过的密码保持不变。

重新执行完整构建与全部测试：

```powershell
.\scripts\build.ps1
```

本机已安装 Microsoft OpenJDK 17；项目通过 Maven Wrapper 固定使用 Maven 3.9.11。完整构建使用 Node.js 24，兼容 pnpm 10.34.5 至 11.x。

`start-local.ps1` 会依次启动 openGauss、校验并执行数据库迁移、启动单 JAR。数据库数据保存在 Docker 卷 `mt-wms-opengauss-data` 中。

阶段 2 的实施与验收记录见 `docs/phase-2/00-phase-summary.md`。
阶段 3 的实施与验收记录见 `docs/phase-3/00-phase-summary.md`。
阶段 4 的实施与验收记录见 `docs/phase-4/00-phase-summary.md`。
阶段 5 的实施与验收记录见 `docs/phase-5/00-phase-summary.md`。
阶段 6 的实施与验收记录见 `docs/phase-6/00-phase-summary.md`。
阶段 7 的实施与验收记录见 `docs/phase-7/00-phase-summary.md`。

阶段 8 的实施与验收记录见 `docs/phase-8/00-phase-summary.md`，旧库迁移操作见 `docs/phase-8/01-legacy-migration-runbook.md`。

## openEuler 服务器部署

项目已适配 B 项目开发服务器的 openEuler、Java 17、Maven 3.9、Node.js 24、pnpm 10.34.5、iSulad 和 openGauss 6.0.5 环境。服务器部署不启动新的数据库容器，而是连接 node4 已运行的 openGauss。

完整步骤、配置和验收方法见 `docs/deployment/openeuler-b-project.md`。
