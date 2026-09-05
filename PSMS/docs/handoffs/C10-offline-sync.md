# C10 离线同步 / UI-010 → C11 交接

## 交付结果

C10 已把 UI-010 从 scaffold 实现为可演示、可恢复、可审计的离线包工作台。页面提供来源上下文、七项 KPI、离线包台账、严格 DO-011 详情、版本差异、状态流、五项动作与命令/审计反馈，并覆盖 loading、empty、403、not-found、network、malformed 和业务错误。

对外口径固定为：

- SCN-06 是“冲突识别与恢复引导”。
- SCN-01 是“标准离线包处理闭环”。

SCN-06 只让 OFF-001 在用户触发 RETRY 时命中冻结 `TOS-OFF-001`，页面保持 CONFLICT 并给出“重置到 SCN-01”的可恢复提示。完整 MERGED 或 REJECTED 闭环必须先重置到 SCN-01。

## 稳定入口与公共导出

模块入口为 `src/features/offline-sync/index.ts`，统一导出：

- 查询：`parseOfflinePacketQuery`。
- 投影：`selectOfflinePacketBoard`、`selectOfflinePacketKpis`。
- 网关：`createOfflinePacketGateway`、`offlinePacketCommandRequestSchema`、`offlinePacketCommandActions` 与相关类型。
- 命令：`createOfflinePacketCommandService`、`offlinePacketCommandPayloadSchema` 与相关类型。
- UI 临时态：`createOfflinePacketWorkflowStore`。
- 类型与展示常量：`OfflinePacketBoard`、`OfflinePacketLedgerItem`、`OfflinePacketWorkflowState`、`OFFLINE_RECOVERY_GUIDANCE`、`OFFLINE_SOURCE_DISCLOSURE` 等。

共享运行时暴露 `runtime.offlineSync.gateway`、`runtime.offlineSync.workflow`、`runtime.offlineSync.commands`；React 侧通过 `useOfflinePacketWorkflow(selector)` 订阅 UI 临时态。

## 查询语义

`parseOfflinePacketQuery` 只接受展示/筛选上下文：

| 参数 | 语义 |
| --- | --- |
| `packetId` | 离线包 ID |
| `terminalId` | 终端文本标识 |
| `workOrderNo` | 作业单文本上下文，不是生产外键 |
| `mergeStatus` | 冻结 DO-011 状态；非法值忽略 |
| `scenarioId` | 演示场景标签与恢复提示来源 |
| `from` | 来源模块标签 |

所有字符串会去除控制字符、trim 并截断到 128 字符。数据范围检查先于筛选；无 AREA-A 可见范围时不会调用 API-018/API-019，也不会泄露对象是否存在。

## API-018 / API-019

- API-018：`GET /mock/offline-packets`，必须返回 `apiId=API-018`、`operationId=GET_mock_offline_packets`、当前 `scenarioId` 与严格 DO-011 数组。
- API-019：`POST /mock/offline-packets/:id/command`，body 严格为 `{ action, reason, validation? }`；action 只允许 `UPLOAD|VALIDATE|MERGE|REJECT|RETRY`，reason trim 后不能为空。
- API-019 成功响应必须恰有一个 DO-011 对象，且对象 ID 与编码后的 path ID 一致。
- 公共错误 envelope 原样解析；network、非法 JSON/结构、场景身份漂移与版本漂移均不会提交领域状态。

SCN-06 的冻结 fault 没有改动。mock 路由只把 `OFFLINE_VERSION_CONFLICT` 精确限定到 API-019 且 path ID 位于该场景 seedRefs 的请求，因此 API-018 可以先加载台账，而 OFF-001 RETRY 仍返回原有 `TOS-OFF-001`。

## OS-01..OS-05 与 DO-011

| 业务动作 | 领域命令 | 权限 | 冻结迁移 |
| --- | --- | --- | --- |
| OS-01 | `upload` | `offline:retry` | CACHED → PENDING_UPLOAD；RETRY → PENDING_UPLOAD |
| OS-02 | `validate` | `offline:resolve` | PENDING_UPLOAD → VALIDATING |
| OS-03 | `merge` | `offline:resolve` | VALIDATING → MERGED；要求 `validation.valid=true` |
| OS-04 | `reject` | `offline:resolve` | VALIDATING → REJECTED；CONFLICT → REJECTED |
| OS-05 | `retry` | `offline:retry` | CONFLICT → RETRY；REJECTED → RETRY |

命令进入既有 C03 管线：权限与范围 → 输入/版本/迁移校验 → API-019 → 场景与版本复核 → 单次原子 commit → 单条审计。相同 `commandId` 重放不会重复 API、commit、workflow 或 audit。

标准演示链：

- MERGED：RETRY（冲突/驳回包）→ PENDING_UPLOAD → VALIDATING → MERGED。
- REJECTED：RETRY（冲突/驳回包）→ PENDING_UPLOAD → VALIDATING → REJECTED。
- CACHED 包可从 PENDING_UPLOAD 开始同样进入 VALIDATING 分支。

## 字段所有权与隔离

C10 只拥有 DO-011 的 11 个冻结字段：`id`、`offlinePackageNo`、`terminalId`、`workOrderNo`、`packageVersion`、`serverVersion`、`validation`、`mergeStatus`、`version`、`createdAt`、`updatedAt`。

命令提交规则：

- 每次成功迁移只增加 `version` 并更新 `updatedAt`。
- OS-02 才写入 `validation`。
- OS-03 才把 `serverVersion` 对齐到 `packageVersion`。
- 审计写入既有 DO-013 ledger，action 为 OS-01..OS-05。
- 不写 WorkOrder、Exception、Interlock、Plan 或 Resource；集成与 E2E 均对这些上游切片做了快照不变断言。
- 不把 `workOrderNo` 扩展为真实关系，也不新增 fixture 来补造 OFF-001 完整闭环。

## 权限、页面与恢复

- 路由查看权限：`offline:view`。
- 上传/重试：`offline:retry`。
- 校验/合并/驳回：`offline:resolve`。
- 对象范围：AREA-A，`*` 与 `GLOBAL` 也可见。
- 业务按钮同时受权限、DO-011 可达迁移、有效校验与 pending 状态限制。
- API-018 失败保留 Store 投影的设计基础；页面给出重新加载或重试原动作入口。
- 延迟双击由页面 in-flight guard 与命令幂等共同防护。

`resetScenario('SCN-01')` 会恢复四个冻结包、清空 selected/reason/validation draft/pending/feedback，清空 C10 command/idempotency 状态，并让 `CMD-C10-001`、`TRACE-C10-001`、`AUD-C10-001` 重新开始；重置审计仍遵循既有 C03 单条审计语义。

## 验收证据

- [最终验证](../evidence/C10/verification.md)
- [机器可读覆盖](../evidence/C10/coverage.json)
- [截图索引](../evidence/C10/screenshot-index.md)
- [TypeScript 前基线](../evidence/C10/tsc-before.txt)
- [TypeScript 后输出](../evidence/C10/tsc-after.txt)
- [端到端测试](../../e2e/ui-010-offline-sync.spec.ts)

实测：C10 定向 55/55、全量 Vitest 663/663、C10 Playwright 4/4、全量 Playwright 43/43、production build 1951 modules、冻结哈希 6/6、merge 0、截图恰好 8 张且尺寸/视口检查通过。

## 已知限制与 C11 入口

本模块是 Demo 实现：不连接真实终端、文件协议、数据库、队列或自动冲突合并。TypeScript 仍受仓库冻结的 React 类型声明缺口影响，完整 `tsc --noEmit` 返回 1，但 C10 非 React `.ts` 诊断为 0。构建与浏览器输出保留既有大 chunk 和 Ant Design 告警。

C11 如需消费离线同步结果，应：

1. 通过 feature 导出与共享 Store 读取 DO-011 投影或 OS-01..OS-05 审计，不直接依赖组件内部结构。
2. 把 `workOrderNo`、`terminalId` 继续当作 Demo 文本上下文，不声明生产外键。
3. 保留 SCN-06/SCN-01 口径，不把 SCN-06 解释为完成闭环。
4. 不修改 C10 fixture、冻结 fault、权限或 DO-011 迁移来满足报表展示；缺少的展示字段应在 C11 自己的只读投影中派生。
5. 复用现有 reset 后确定性 ID 与 audit 证据，避免在报表层重复提交离线包命令。
