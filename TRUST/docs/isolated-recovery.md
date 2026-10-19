# 隔离实例恢复验证

`deploy/verify-isolated-restore.py` 用于已确认归属的隔离资源，不替代正式灾备流程。由数据库运维账号执行；密码通过客户端受限密码文件或环境加载，不写进清单、命令参数或交接包。

先停止对应隔离应用，保留配置、源码及构建摘要，并确认源库无其他连接。共享 Fabric/IPFS 不停机。工具验证数据库兼容模式、源库与实例白名单、二进制和输出路径归属；只创建不存在的 `trust_restore_*` 新库，绝不删除或覆盖源库。

私有 manifest 字段：`schemaVersion=1`、`purpose=isolated-recovery`、`instance`、`sourceDatabase`（`trust_iam_*`）、`targetDatabase`（`trust_restore_*`）、`owner`、`engine`（opengauss/postgresql）、`compatibility`（A/PG）、`host`（回环或 resourceRoot/runtime socket）、`port`、`resourceRoot`、`binaryDirectory`、`outputDirectory`、`resourceAllowlist`。路径使用绝对路径，输出目录必须不存在。

`owner` 是执行恢复的数据库运维账号；另须提供 `databaseOwner`，必须与源库实际所有者相同，新库保持该所有者。工具不把运维账号当作源库所有者。

resourceAllowlist 是由资源负责人确认的私有 JSON：`instances` 下以 instance 为键，值精确包含 `sourceDatabase、owner、databaseOwner、host、port、compatibility、engine`，与 manifest 一致。它不是从待恢复备份生成的自证材料。不得把已有正式库列入隔离清单。

运行 `python3 deploy/verify-isolated-restore.py --manifest <private-manifest.json>`。工具进行一致性 dump、恢复和全表记录摘要比对，并核对迁移记录、索引、约束、表/列权限；包括事件、任务、原文 JSON、CID、交易回执、加密日志和审计记录。恢复库撤销 PUBLIC 连接权限，设为默认只读，不加载应用配置、不启动工作线程。工具失败时保留输出与新库供诊断，不自动删除资源。

数据库验证不能替代 IPFS 与密钥恢复：选取相关 CID 导出 CAR，在新建的离线 IPFS 仓库导入并比较原始字节摘要。不复用运行中的仓库目录。日志归档密钥通过另一个受限通道加载，核验解密后的 canonical 与原 payloadHash。真实密钥、dump、CAR、环境与现场报告全部保存在私有区域；确认不再需要后由资源负责人清理测试库。

旧 `verify-database-restore.sh` 仅适用于它明确支持的旧库名，不适用于本工具的隔离实例。旧 `recovery-components.py` 包含共享依赖启停，不用于本收尾验证。
