# 旧 SQLite / MySQL 数据迁移运行手册

## 1. 切换前

1. 对旧 MySQL 做只读备份，并记录备份文件 SHA-256。
2. 确认旧 SQLite 文件可读，或旧 MySQL 可通过网络读取；新 openGauss 已执行全部版本迁移。
3. 先备份新库：

```powershell
.\scripts\backup-opengauss.ps1
```

4. 确定旧公司代码和目标公司代码。迁移不会导入旧密码。

## 2. SQLite 预检与执行

项目默认读取 `mt-wms-demo/.demo-data/wmsbase.sqlite3` 和 `auth.sqlite3`：

```powershell
.\scripts\invoke-legacy-sqlite-migration.ps1 -Mode precheck
.\scripts\invoke-legacy-sqlite-migration.ps1 -Mode execute
.\scripts\invoke-legacy-sqlite-migration.ps1 -Mode verify
```

如文件位于其他位置，使用 `-BusinessDatabase` 和 `-AuthDatabase` 传入绝对路径。

## 3. MySQL 预检与执行

```powershell
.\scripts\invoke-legacy-migration.ps1 `
  -Mode precheck `
  -SourceHost 127.0.0.1 -SourcePort 3306 `
  -SourceDatabase wmsbase -SourceUser wms `
  -SourcePassword '<旧库密码>' `
  -SourceCompany '<旧公司代码>' `
  -TargetCompany default
```

预检只读取旧库，不修改任何数据库。缺表或缺少关键字段时必须先处理报告，不能跳过。

## 4. 执行与验证

把上面命令中的 `-Mode` 依次改为 `execute`、`verify`。执行采用目标库事务，失败会回滚本次业务写入并把错误写入迁移审计表。相同来源可重跑，目标业务唯一键不会重复插入。

执行后至少核对：

- 公司下仓库、货主、伙伴、库区、库位数量。
- 每货主货类、货品、条码数量。
- 按仓库和货主汇总的可用、分配、冻结库存。
- 入库/出库各状态单量及明细数量。
- 财务收入、支出、实收实付合计。
- 随机抽取业务单和 RFID，从旧库逐字段对比。
- `legacy_migration_run` 的状态和统计 JSON。
- `legacy_archive_record` 的来源表覆盖范围及脱敏结果。

## 5. 回退

若对账不通过，停止新系统写入，使用切换前备份恢复：

```powershell
.\scripts\restore-opengauss.ps1 `
  -BackupFile '<绝对备份文件路径>' `
  -ConfirmDatabaseName mt_wms
```

恢复脚本会验证同名 `.sha256` 文件、停止应用、恢复数据库、重跑迁移校验并重新启动。随后执行：

```powershell
.\scripts\smoke-test.ps1
```

恢复属于覆盖性操作，只能使用已核验的精确备份路径和数据库名。
