# C10 离线同步实施对话提示词

你现在开始独立实施 **C10：离线同步 / UI-010**。这是编码执行任务，不是重新讨论方案。设计规格与实施计划已经确认；使用 `superpowers:executing-plans` 按 Task 1 到 Task 8 顺序执行。除非用户明确要求，不使用子代理。

## 一、工程、基点与分支

- 工程：`E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`
- C09 基点：`de81e5a22cf1972133cbdee03e3b281723aca2da`
- 设计：`docs/superpowers/specs/2026-07-22-c10-offline-sync-design.md`
- 计划：`docs/superpowers/plans/2026-07-22-c10-offline-sync.md`
- 分支：`demo/c10-offline-sync`

保持分支不合并、不推送。实施前确认 C09 是祖先、工作区干净、merge commit 数为 0，并执行六份冻结 hash 与 C09 回归核验。

## 二、冻结基线闸门

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

任一 hash 不匹配时停止，不修改、不重建、不接受新基线。

## 三、唯一业务范围

路由：

```text
/operations/offline-sync
```

只使用：

- DO-011 `OfflinePacket`
- API-018 `GET /mock/offline-packets`
- API-019 `POST /mock/offline-packets/:id/command`

DO-011 仅包含：

```text
id
offlinePackageNo
terminalId
workOrderNo
packageVersion
serverVersion
validation
mergeStatus
version
createdAt
updatedAt
```

不实现真实终端、数据库、文件上传协议、包解析、增量同步、后台队列或自动冲突合并。不写回 WorkOrder、WorkNode、DispatchException、Interlock、Resource、Plan 等对象。

## 四、fixture 与场景口径

- `OFF-001`：CONFLICT，版本差异与 `VERSION_CONFLICT`。
- `OFF-002`：REJECTED。
- `OFF-003`：RETRY。
- `OFF-004`：CACHED。

场景口径固定：

- SCN-06 是“冲突识别与恢复引导”。只演示 OFF-001 触发 `TOS-OFF-001` 以及页面恢复提示；不修改 fault、fixture 或 OFF-001。
- SCN-01 是“标准离线包处理闭环”。OFF-004 演示 CACHED→PENDING_UPLOAD→VALIDATING→MERGED；reset 后 OFF-001 可演示 RETRY→PENDING_UPLOAD→VALIDATING→MERGED 或 REJECTED。

既有 mock 可能让 API-018 或 API-019 返回 SCN-06 fault。页面仍以唯一 Store 投影展示 OFF-001 冲突事实，不因 API 错误清空台账。

## 五、API 与命令 body

API-018：

```text
operationId = GET_mock_offline_packets
x-api-id = API-018
```

API-019：

```text
operationId = POST_mock_offline_packets_id_command
x-api-id = API-019
```

C10 feature 内部 body：

```ts
{
  action: 'UPLOAD' | 'VALIDATE' | 'MERGE' | 'REJECT' | 'RETRY';
  reason: string;
  validation?: Record<string, unknown>;
}
```

通过既有 feature-local handler Schema 扩展点实现。禁止修改公共 `src/contracts/requests.ts`、OpenAPI 或 baseline。Gateway 只验证 transport/envelope/API/scenario/object identity，不覆盖 Store。

## 六、动作与写入

```text
OS-01 UPLOAD:   CACHED/RETRY -> PENDING_UPLOAD       offline:retry
OS-02 VALIDATE: PENDING_UPLOAD -> VALIDATING         offline:resolve
OS-03 MERGE:    VALIDATING -> MERGED                 offline:resolve
OS-04 REJECT:   VALIDATING/CONFLICT -> REJECTED      offline:resolve
OS-05 RETRY:    CONFLICT/REJECTED -> RETRY           offline:retry
```

MERGE 只允许 `validation.valid === true`，并令 `serverVersion = packageVersion`。VALIDATE 写入严格 validation；RETRY 和 REJECT 保留冲突证据。每次成功只递增一次 DO-011 version，使用共享 Demo 时间更新 updatedAt。

固定顺序：

```text
对象/版本捕获 -> 权限/数据域/请求/状态校验 -> API-019
-> scenario/object identity -> 二次版本检查 -> DO-011 transition
-> 单次原子 Store commit -> 审计与反馈
```

失败不修改 DO-011。相同 commandId 不重复 API、审计、commit、version 或 workflow 更新。

## 七、页面要求

支持 query：`packetId`、`terminalId`、`workOrderNo`、`mergeStatus`、`scenarioId`、`from`。这些值只作筛选/上下文，不写入 DO-011，不形成外键。

页面必须包含：

- 七项 KPI：缓存、待上传、校验中、已合并、冲突、已驳回、重试。
- 离线包台账：包号、终端、作业单文本、包版本、服务器版本、状态。
- 详情：校验结果、冲突原因、版本差异、更新时间、状态流。
- 动作：reason、上传、校验、合并、驳回、重试、reset 到 SCN-01。
- 反馈：traceId、auditLogId、幂等、版本拒绝、业务错误、恢复引导。
- loading、empty、forbidden、not-found、network、malformed、business-error。

1440×900 使用三栏；1280×720 把紧凑动作条提升到 KPI 下方，并保持状态、版本差异、动作和最近反馈在首屏可见。不得横向滚动、裁切、重叠或中文缺字。

## 八、TDD、E2E 与证据

严格先红后绿：

- `docs/evidence/C10/offline-core-red.txt`
- `docs/evidence/C10/offline-page-red.txt`
- `docs/evidence/C10/offline-e2e-red.txt`

E2E 文件：`e2e/ui-010-offline-sync.spec.ts`，覆盖四组：标准闭环、SCN-06 冲突与 reset、原子失败/幂等/上游不变、network/malformed/业务错误恢复。

只保存以下 8 张截图：

```text
C10-UI010-SCN01-CACHED-1440x900.png
C10-UI010-SCN01-CACHED-1280x720.png
C10-UI010-SCN01-UPLOADING-1440x900.png
C10-UI010-SCN01-UPLOADING-1280x720.png
C10-UI010-SCN01-CONFLICT-1440x900.png
C10-UI010-SCN01-CONFLICT-1280x720.png
C10-UI010-SCN01-MERGED-1440x900.png
C10-UI010-SCN01-MERGED-1280x720.png
```

截图必须使用视口尺寸，逐张记录原始分辨率、溢出、裁切、文字、按钮和关键状态可见性。

## 九、最终验收

必须执行并记录：C10 focused Vitest、全量 Vitest、build、C10 Playwright、全量 Playwright、TypeScript、六 hash、`git diff --check`、merge count、禁止范围和截图尺寸。既有 UI-002 timeout 必须隔离复跑并如实说明，不修改 timeout/config。

最终创建：

```text
docs/evidence/C10/preflight.md
docs/evidence/C10/tsc-before.txt
docs/evidence/C10/offline-core-red.txt
docs/evidence/C10/offline-page-red.txt
docs/evidence/C10/offline-e2e-red.txt
docs/evidence/C10/screenshot-index.md
docs/evidence/C10/coverage.json
docs/evidence/C10/verification.md
docs/evidence/C10/tsc-after.txt
docs/handoffs/C10-offline-sync.md
```

最终报告分支、HEAD、干净状态、完整提交链、focused/full 验证、8 张截图、证据路径、C10→C11 handoff 和已知限制。不合并、不推送。
