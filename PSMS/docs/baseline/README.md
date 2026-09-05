# B项目生产调度管理模块网页 Demo

本附件包是技术设计文档的机器可读实施基线，只用于本地网页 Demo。

## 基线版本与冻结规则

- 契约基线版本：`0.3.0`
- 冻结提交：`demo/c13-config-contract-baseline` 的 C13A 契约基线修复提交
- 固定规模：15 个领域对象、25 个 Mock API、7 个演示场景、9 个公开错误码（15/25/7/9）
- `DO-015 ConfigVersion` 是系统配置版本的唯一权威身份；`DO-014` 保持用户角色语义。
- API-022 只读观测 DO-015；API-023 只接受 `edit/submit/approve/publish/rollback` 配置版本命令，其中仅 `edit` 可携带强类型白名单 `changes`。
- C13A 仅修复契约基线，`UI-012` 页面仍未实施。
- C13 及后续任务只能消费本基线；后续差异须另立变更任务，不得在页面内另定义配置契约。

## 环境

- Node.js 24.18.0 LTS
- pnpm 11.10.0
- Windows Edge/Chrome
- 推荐分辨率 1440×900，最低 1280×720

## 安装与启动

```powershell
corepack enable
corepack prepare pnpm@11.10.0 --activate
pnpm install --frozen-lockfile
pnpm dev
```

访问 `http://localhost:5173/dispatch/overview`。

## 测试与构建

```powershell
pnpm test
pnpm test:e2e
pnpm build
```

## 场景控制

通过顶栏“重置场景”恢复固定种子数据。完整演示从 SCN-01 开始，
异常分支使用 SCN-02～SCN-07。运行时不依赖外部网络，场区图只读取本地 GeoJSON。

## 页面清单

- `UI-001` `/dispatch/overview` → `src/pages/dispatch/OverviewPage.tsx`
- `UI-002` `/dispatch/plans` → `src/pages/dispatch/PlanLedgerPage.tsx`
- `UI-003` `/dispatch/plans/:planId/recommendation` → `src/pages/dispatch/ReceptionRecommendationPage.tsx`
- `UI-004` `/dispatch/plans/:planId/tasks` → `src/pages/dispatch/TaskDecompositionPage.tsx`
- `UI-005` `/dispatch/work-orders` → `src/pages/dispatch/DispatchBoardPage.tsx`
- `UI-006` `/yard/appointments` → `src/pages/yard/RoadAppointmentPage.tsx`
- `UI-007` `/monitor/operations` → `src/pages/monitor/OperationMonitorPage.tsx`
- `UI-008` `/monitor/exceptions` → `src/pages/monitor/ExceptionHandlingPage.tsx`
- `UI-009` `/safety/interlocks` → `src/pages/safety/SafetyInterlockPage.tsx`
- `UI-010` `/operations/offline-sync` → `src/pages/operations/OfflineSyncPage.tsx`
- `UI-011` `/reports/operations` → `src/pages/reports/OperationReportPage.tsx`
- `UI-012` `/settings/system` → `src/pages/settings/SystemSettingsPage.tsx`
- `UI-013` `/governance/audit` → `src/pages/governance/AuditLogPage.tsx`

## 文件权威顺序

需求分析文档 → v0.4 主技术设计 → Mock接口与数据契约 →
页面任务卡 → 本附件包。字段、枚举、接口路径和任务编号不得在页面内另行定义。
