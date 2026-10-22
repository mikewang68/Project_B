# 业务版本留存与恢复

业务修改追加不可变版本，事务内切换当前引用。停用、拒绝、IAM 逻辑删除和处理失败都保留旧状态；没有新增事件或证据删除入口。IAM 已删除主体仍不可恢复。

## 数据与事务

009 是 001—008 之后的新迁移，既有 changeset 不变。事件、证据、通用原文、钱包、来源绑定、申请复核、身份、证书、日志设置、日志存证、日志投递、页面操作、签名尝试、任务各有领域类型化版本表。

原表保存既有行与新接收的不可变字段，`*_versions` 保存完整可变业务状态及 `retention_revision/previous_revision/change_actor/change_operation/change_reason/changed_at`。完整历史快照由对应版本和不可变原表字段组合还原。`*_head` 只保存当前版本引用；`*_runtime` 保存租约、下一调度时间及尝试次数。`*_read` 是明确列出的组合视图，不是存储副本。

应用只对视图的许可列发出 UPDATE。数据库触发器锁定当前引用、重验版本和租约、新增业务版本、切换引用；事务回滚同时回滚审计和任务。运行字段单独变化不产生业务版本。显式人工重试即使状态相同也追加操作版本。SQLSTATE 40001 以 HTTP 409 `VERSION_CONFLICT` 返回，调用方重新读取后决定重试。

原事件 `root_id/version/supersedes_id` 继续表示业务更正链；`event_heads` 指向链的当前事件。处理状态的 retention revision 与事件业务 version 是不同编号。当前事件仍可有多次归档、签名或确认处理版本。

接入先以短事务确保 `(org_id,source_system)` 协调行存在，再在业务事务锁来源行、校验关系、保存事件和当前引用。后台事件归档按 tasks head → event head 顺序取得锁，取得后再次检查 token、RUNNING 和实际截止时间；旧 worker 不得在等待锁后发布结果。IAM 的证书处理由身份 head 锁和 revision/lease 校验约束。

## 历史查询与操作

- `GET /api/v1/events?view=current|history`：不传为 history，兼容旧客户端；页面显式传 current。先确定当前记录再搜索，列表和总数用同一条件。
- `GET /api/v1/events/{id}`：返回所选事件版本，包含 `currentVersionId` 和原有 versions 导航。核验、导出、溯源仍绑定这个 ID。
- `GET /api/v1/events/{id}/processing-history`：该事件的归档、签名和确认历史。
- 钱包、绑定、复核、身份、证书和日志设置的 `.../history` 路由见 OpenAPI。页号从 0 开始，每页最多 50 条，返回 `{items,page,hasMore}`，记录含版本、操作、操作者、时间和原因。
- 身份历史必须同时满足当前身份组织与历史版本组织；证书材料只返回状态、指纹和有效期，不返回私钥、密钥引用或临时秘密。
- 日志设置 GET 返回 `revision`；PATCH 必须提交 `expectedRevision`。缺少版本为 409 VERSION_REQUIRED，过期为 409 VERSION_CONFLICT；页面提示刷新。
- 历史读取沿用功能读取权限、组织范围和严格只读规则；下载、导出、核验没有新增放行。

迁移只保存可证明的现存状态，操作标记 BASELINE；此前已经被覆盖的历史不补造。历史 UI 对基线明确说明这一边界。

## 原件接收和存储边界

上传最大 20 MiB。先持久化不含凭据的接收清单，再 fsync 原件、原子移动并 fsync 目录，最后登记数据库。正式原件和清单不会因登记异常或提交回执丢失被删除；只清理未完成接收的 `.part` 临时文件。成功响应直接来自已确认接收数据。

`com.bproject.trust.evidence.EvidenceReconcile` 使用既有实例白名单和应用账号，默认只读扫描，`--apply` 按原 ID 补登记；只有清单、大小、摘要和类型一致时才补登。已登记内容全字段核对；异常保留供人工检查。暂存容量包含未登记正式文件。上线前应在发布包 JAR 中按部署运行方式调用该入口；不直接执行清理命令。

IPFS 集中参数：`trust.ipfs.connect-timeout=5s`、`exchange-timeout=30s`、`add-timeout=90s`、`metadata-limit=65536`、`content-limit=33554432`。exchange 包含响应正文，add 包含本地锁、数据库容量锁、预算查询、上传及读回校验。请求取消或超限终止订阅，释放锁后进入已有重试。每个专用数据库的 `ipfs:primary` 与 `staging:primary` 协调行供同实例多进程共享，不因访问 URL 或挂载路径别名而拆分容量锁。共享同一存储的进程必须使用同一协调数据库与预算配置。

运行数据库账号只读/插入不可变表，只能更新明确列出的业务视图；head 仅 UPDATE(lock_epoch) 以允许行锁，runtime 无直接更新权。禁止业务表 DELETE/TRUNCATE、schema CREATE、临时表及其他角色继承。迁移账号独立持有 DDL 和回填权；数据库管理员仍具有运维权限，不能以其能力替代应用账号拒绝测试。

## 策略版本

链码 SetSigner 追加 signer-version 键，切换 signer-head；GetSigner 返回结构不变，GetSignerVersion 按 revision 查询。首次修改旧策略先保存可证明的旧当前状态，不能推断更早历史。`generic-signer-policy.py --history-revision N` 只读查询并比较配置内所有 peer 的结果。代码发布不等于链码已升级。

## 发布与恢复顺序

1. 在隔离 openGauss 中先跑迁移、旧数据升级和权限拒绝测试，再跑业务并发与故障回归；测试账号不得指向部署库。
2. 维护窗口暂停接入写入和 worker，等在途调用收敛；备份数据库、原件、接收清单和密钥存储，并实际验证备份可读及摘要。记录 Fabric/CA 已发生操作，备份不代表其状态可以回滚。
3. 检查更正链、重复版本、跨组织引用和来源映射；009 检测到异常时停止，不自动修复。使用独立迁移入口 `IsolatedMigration` 运行 DDL、基线回填及权限白名单，保留执行日志。
4. 用真实应用账号验证不能修改或删除历史，检查旧行数量/摘要、版本基线、当前指针和业务查询一致性。安装匹配新结构的 JAR；先只读探测，再开放写入和 worker。
5. Fabric 链码升级独立协调和验收；未升级期间不得宣称历史策略查询已生效。GitHub 推送、数据库迁移、应用切换、链码升级和双端验收分别记录状态。
6. 新结构接受写入前可按演练结果恢复数据库及文件备份。`identity-release.py` 在首次安装留存制品时持久记录 `runtime/releases/retention-required`，拒绝自动 rollback 和无 009 的旧 JAR；该保护早于开放新写入。接受写入后禁止直接换回旧 JAR；采用兼容新结构的修复版本前向恢复。恢复数据库不能撤销已完成的 Fabric 交易或 CA 撤销。

通用源码不含实例配置、凭据和现场验收材料。具体基线摘要、测试日志、制品校验和及发布状态仅保存于 `.local/`。
