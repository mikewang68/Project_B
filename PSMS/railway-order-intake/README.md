# 铁路工单导入

铁路工单运行台可以选择系统模拟、邮件接收或文件上传。真实工单请使用下列 JSON 格式，按已有 WMS 派遣约定填写。

- 卸车入库示例见 `unloading.example.json`，装车出库示例见 `loading.example.json`。一个 JSON 文件提交一张工单，使用 UTF-8，最大 64 KB。
- 将示例中的外部工单号、计划 ID 和业务字段替换为实际数据。计划须已在 PSMS 确认，车次、货物类别和作业区取自计划。同一外部工单号在邮件和上传之间共用。
- 物料一行一个 WMS 编码及原库存单位。吨单位支持 `吨`、`t`、`ton`，数量最多三位小数。按捆、按件等单位用整数。粉煤灰、钢材按吨口径填报。批次和供应商按实际情况填写，未指定时可省略。
- 卸车填 `expectedArrivalAt`，实际目的仓和库位由 WMS 确认。装车填 `sourceWarehouseCode`。短驳使用 `SHUTTLE` 并填 `vehiclePlate`、`driverName`，直装直卸使用 `DIRECT` 并省略车辆字段。公司和货主取现有 WMS 联动配置。
- 邮件主题包含 `[PSMS-RAILWAY]`，并附上 JSON 文件。进入运行台点击“读取最近工单邮件”，核对附件后导入。导入写入正式作业工单表，初始状态为草稿。
- 邮箱设置由服务器管理员维护，收件夹是邮箱中的 `INBOX`。本目录存放模板，不存放生产邮件或授权码。服务器配置项为 `PSMS_RAILWAY_IMAP_HOST`、`PSMS_RAILWAY_IMAP_PORT`、`PSMS_RAILWAY_IMAP_USER`、`PSMS_RAILWAY_IMAP_FOLDER`、`PSMS_RAILWAY_IMAP_PASSWORD`，默认端口为 993。授权码仅存服务器环境文件。
- 每日模拟开关使用现有 `PSMS_RAILWAY_AUTO_IMPORT`，服务器管理员可在切换真实工单后将其设为 `false`。