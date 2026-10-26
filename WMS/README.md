# MT-WMS 重构项目

## 源码目录（2026-10-27 起）

仓库已去掉重复的一层 `WMS`，项目根目录现在为 `Project_B/WMS`。
从仓库根目录执行 `cd WMS` 后运行构建脚本；`pom.xml`、`frontend/`、
`src/`、`database/`、`scripts/` 和 `simulator/` 均在这一层。
原 `Project_B/WMS/WMS` 路径不再使用，历史验收记录中的旧路径仅表示当时位置。

node6 公共源码对应 `/home/bpoc-data/projects/Project_B/WMS`。
目录调整不改变独立发布目录、站点入口、服务端口或 node4 数据库；
不能仅因为源码移动就把 `target/mt-wms.jar` 覆盖为正式运行制品。
其他成员更新后请修改 IDE 项目根目录及自己脚本里的旧路径；有未提交代码时，
先备份再合并目录重命名，不要整目录覆盖或直接删除旧工作目录。

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

## 仓储智能体与货运演示

支持日常语言模糊查货、持久化定时库存巡检/站内预警及库位利用率分析。邮件默认关闭，按需显式启用；模型只通过受控只读工具查询业务数据。

使用、配置、扩展及钢材/粉煤灰等演示数据导入见 [仓储智能体使用与演示数据](docs/仓储智能体使用与演示数据.md)。演示数据不属于生产数据库迁移，不会在启动时自动导入。

## 业务逻辑删除（V015）

基础资料九类和用户管理提供逻辑删除：记录 DELETED、删除时间与操作人；日常列表、业务可选范围和基础资料导出过滤已删除项。停用资料仍在维护列表中显示，便于重新启用。删除后编码保留，普通编辑和 Excel 导入不能恢复或复用该编码。

已有库存、单据或其他业务引用的基础资料拒绝删除，应使用停用。系统默认资料受到保护；删除空仓库或货主时，其未使用的内置资料一并标记删除。库存和财务流水、入出库单继续通过取消、反单流程保留账据。

用户授权重配使用 revoked_at 撤销并可重新激活；取消拣货使用 cancelled_at 保留每次标签关联，重新拣货生成新的有效关联。每次 API 请求重新验证账号和授权，已删除或停用账号的旧会话无法继续访问。

部署时先执行 V015，再启动新版 JAR。迁移撤销默认应用账号及实验室运行账号的表 DELETE 权限，引用检查触发器拒绝新增对已删除资料的引用。历史账据保留操作人与基础资料关联，专项审计可查看保留行。

实施范围、迁移顺序和验收结果见 [逻辑删除实施与验收记录](docs/逻辑删除实施与验收记录.md)。

## Android 设备模拟终端

[Android C 设备模拟终端0.5.0](simulator/android-terminal/README.md)提供摄像头二维码、
扫码持久队列、称重、工艺测点以及EE600/VEGA只读协议参考；包含源码、电脑端参考采集工具、
单元测试及可下载的Android安装包。它不等同于WMS业务客户端，也不表示已完成真实网关、
公共底座或线上业务联合验收；扫码不会直接写入服务器库存。签名私钥与运行数据不在仓库中。
