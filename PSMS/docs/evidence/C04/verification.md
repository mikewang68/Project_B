# C04 最终验证记录

## 结论

C04 的聚焦测试、全量 Vitest、生产构建和完整 Playwright 均以最终代码重新执行并通过；最终复核补丁后的完整 Playwright 仍为精确 `19/19`（C01 `14` + C04 `5`）。六份冻结基线哈希全部匹配，C01 截图改写已恢复，范围、随机/系统时钟、内联角色目录和 whitespace 检查均通过。

全局 TypeScript 检查必须如实记录为退出 `1`：仓库没有安装或声明 `@types/react` / `@types/react-dom`，新增共享 React hook 后最终输出为 `286` 条 React 根因/JSX 级联诊断，而不是前置 C01 的 `38` 条。C04 可修复的 primary diagnostics 保持 `0`；没有通过依赖、声明、`tsconfig`、`any` 或 suppression 绕过检查。

## 环境与时间

- 最终复核窗口：`2026-07-21 02:17`～`02:34`（Asia/Shanghai，Windows `China Standard Time`）。
- 最终环境采样：`2026-07-21T02:33:39+08:00`。
- Node：实际与仓库声明均为 `v24.18.0`。
- pnpm：实际与仓库声明均为 `11.10.0`。
- Vitest：`4.1.10`；Playwright：`1.61.1`；Vite：`8.1.4`；TypeScript：`7.0.2`。
- Node 未在继承 PATH 中暴露；每条 Node 命令只在当前进程前置既有 Codex runtime 目录，不写项目配置。
- 构建保留既有 `>500 kB` chunk advisory。

## 命令与结果

| 命令 | 退出码 | 最终结果 |
| --- | ---: | --- |
| coverage JSON/count 校验 + PNG/spec 精确计数 | 0 | 2 pages / 7 selectors / 5 APIs / 3 flows / 6 UI states / 4 roles / 5 E2E / 12 PNG / 53 transitions |
| `node_modules\.bin\playwright.CMD test e2e/ui-001-dispatch-overview.spec.ts e2e/ui-002-dispatch-plans.spec.ts` | 0 | 精确 `5/5`；生成 12 张命名 PNG |
| Task 8 冻结的精确 12-file Vitest 命令 | 0 | `12/12` 文件，`83/83` 测试；final review closure 后最终复跑 |
| `pnpm test -- --run` | 0 | `32/32` 文件，`257/257` 测试；final review closure 后最终复跑 |
| `pnpm build` | 0 | Vite 转换 `1860` modules，生产构建成功 |
| `pnpm test:e2e` | 0 | `19/19`，单 worker；final review closure 后最终复跑 |
| `node_modules\.bin\tsc.CMD --noEmit` | 1 | `286` 条仅 React/JSX 根因级联；C04 primary `0` |
| `git diff --check` | 0 | 无 whitespace error |
| 六文件 `Get-FileHash -Algorithm SHA256` | 0 | `6/6 MATCH` |
| 从设计提交起的 forbidden-scope 脚本 | 0 | baseline/package/lock/UI-003～UI-013 business page `0` |
| C04 production clock/random 扫描 | 0 | `Date.now/new Date/Math.random/crypto.randomUUID` `0` |
| C04 production inline/local role catalog 扫描 | 0 | 角色数组/目录定义 `0`；选项复用冻结 `routePolicies` |
| Task 8/hardening type-escape 扫描 | 0 | `any/@ts-ignore/@ts-nocheck` `0` |
| `git diff --name-only -- docs/evidence/C01`（restore 后） | 0 | `0` |

精确 12-file Vitest 命令为：

```powershell
node_modules\.bin\vitest.CMD run src/features/plan-entry/__tests__/gateway.test.ts src/runtime/__tests__/runtime.test.tsx src/features/plan-entry/__tests__/query.test.ts src/features/plan-entry/__tests__/workflow.test.ts src/features/plan-entry/__tests__/selectors.test.ts src/features/plan-entry/__tests__/commands.test.ts src/pages/__tests__/OverviewPage.render.test.tsx src/pages/__tests__/OverviewPage.permission.test.tsx src/pages/__tests__/OverviewPage.action.test.tsx src/pages/__tests__/PlanLedgerPage.render.test.tsx src/pages/__tests__/PlanLedgerPage.permission.test.tsx src/pages/__tests__/PlanLedgerPage.action.test.tsx
```

最终复核的 `13/13` 相关文件、`86/86` 测试以及 direct-reload SCN-02/SCN-03 定向浏览器复验均先行通过；这些结果不替代上述冻结 12-file、全量 Vitest 与完整 Playwright。

## 最终复核闭环

代码提交 `b9e09d99f596d6c1b0d769f42a75a61fdf602c94` 对最初六项复核意见逐项闭环；独立补丁 `a1c97826c411fc08d9ae743d7c8ea9356a3e4c85` 闭环随后发现的 UI-001 网络态投影问题：

1. 携带 `reviewerId` 的 `adjust` 与 `confirm` 共用职责分离上下文；同人确认在 Gateway 前返回 `TOS-AUTH-001`，领域状态不变且只写一条 `DENIED` 审计。
2. `selectPlanDetails` 在读取 Plan、Waybill 与 audit 前同时校验 session scope 和 query `workArea`；AREA-A session + AREA-B query + `planId` 不挂载 Drawer。
3. API-001/API-002 经共享 read-state hook 驱动 pending/data/business/network；成功信封对象不写入 Store，TOS-EXT-002 仅在 UI-002 作为可恢复数据投影。
4. 生产启动按 URL scenario 先执行严格 API-025，再创建并注入唯一 session-aware runtime；SCN-02/SCN-03 direct reload 均由 E2E 证明。
5. 台账行的冻结 `exceptionTypes` 成为 query、筛选控件和 selector 的统一语义，覆盖 missing-field、scenario fault 与 data conflict。
6. 异常筛选在 UI 中 replace URL；从该 URL remount 后筛选投影保持一致。
7. API-001 返回 `TOS-EXT-001` 时，UI-001 同时保留唯一网络重试面板、接口不可用/最后成功时间与 TOS 风险投影；业务错误仍保持整页错误态。

最终复核期间未改动 baseline、依赖/锁文件、`tsconfig` 或 UI-003～UI-013 业务页面；`.superpowers/**` 保持唯一允许的未跟踪材料。

## TypeScript 实际状态

### 前置与第一次后置分类

`tsc-before.txt` 在 C04 实现前记录 `38` 条：

```text
TS2604 = 3
TS7016 = 25
TS7026 = 10
```

C04 页面完成后的第一次 direct TSC RED 为 `300` 条：

```text
TS2604 = 13
TS2740 = 4
TS7006 = 11
TS7016 = 50
TS7026 = 222
```

根因检查确认：`node_modules/.pnpm/@types+react*`、`node_modules/@types/react` 及本地 pnpm store 均无 React declaration。`tsconfig.json` 使用 `strict: true`、`jsx: react-jsx`、`types: ["vitest/globals"]`，并 include `src`、`e2e`、Vite/Playwright 配置。因此新增 TSX 会扩展同一个缺失 React declaration 根因的 `TS7016 -> TS7026/TS2604` 级联，不能在禁止新增依赖、声明或修改配置的前提下维持字面量 `38`。

第一次后置中仍有 `15` 条可修复 primary：11 条无上下文 callback `TS7006`，以及 4 条 Drawer `Element -> HTMLElement` 的 `TS2740`。它们通过现有 `RoleCode`、`DemoScenario['id']`、`PlanLedgerRowViewModel`、`SupplementPlanValues`、`number`、最小 DOM structural types 和 `.closest<HTMLElement>()` 显式收窄；没有行为变更。

### 最终 fresh capture

最终 fresh TSC 精确分类：

```text
TOTAL = 286
TS2604 = 13
TS7016 = 51
TS7026 = 222
TS7006 = 0
TS2740 = 0
C04_PRIMARY_DIAGNOSTICS = 0
```

按文件范围分类：

```text
src/features/plan-entry/**                    182
src/runtime/**                                  7
UI-001/UI-002 production pages                 53
UI-001/UI-002 page tests                        8
C04 shared App/main                             4
pre-existing/other                             32
```

这些 `286` 条只包含前置已存在的三种 React/JSX 根因类别；共享 hook 新增的 1 条为同一缺失 React declaration 的 TS7016。全局 TSC 未通过，不得把 C04 primary `0` 表述为全局通过。

## 冻结哈希

| 文件 | SHA-256 | 结果 |
| --- | --- | --- |
| `docs/baseline/README.md` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` | MATCH |
| `docs/baseline/package-baseline.json` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | MATCH |
| `docs/baseline/openapi.yaml` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` | MATCH |
| `docs/baseline/demo-fixtures.json` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` | MATCH |
| `docs/baseline/page-task-matrix.csv` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | MATCH |
| `docs/baseline/traceability.csv` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | MATCH |

## 截图与 C01 恢复

- 最终 PNG 数：精确 `12`；6 张宽 `1440`，6 张宽 `1280`；coverage 文件集合与磁盘集合完全一致。
- 最终完整 Playwright 后再次以原始分辨率打开 `12/12`：无裁剪、重叠、不可读表格、Drawer 横向溢出、缺字或瞬态消息残留；SCN-03 circuit 证据显示 network panel 与唯一健康控制条并存。UI-001 的 SCN-03 定向 E2E 另外断言网络面板、接口不可用、TOS 风险和唯一重试同时存在。
- document/body 横向溢出由正式 E2E 在 `1440×900` 与 `1280×720` 直接断言。
- `pnpm test:e2e` 改写了以下四张既有 C01 PNG，验证后只恢复这些精确文件：
  - `docs/evidence/C01/C01-404-1280x720.png`
  - `docs/evidence/C01/C01-404-1440x900.png`
  - `docs/evidence/C01/C01-overview-1280x720.png`
  - `docs/evidence/C01/C01-overview-1440x900.png`
- restore 后 C01 diff 为 `0`。12 张 C04 逐项结果见 `screenshot-index.md`。

## 范围审计

- 最终复核基点 `50f203e854772642b713d154841752641cf2d1b6` 与设计提交 `151004ae53dcd88c4910380bf4d2b6b27ef81b4a` 均仍为 HEAD 祖先。
- `docs/baseline/**`、`package.json`、`pnpm-lock.yaml`、UI-003～UI-013 业务页面无差异。
- C04 production 未使用系统时间或随机源；确定性时间来自 C03 session/scenario。
- 页面角色选项从 `routePolicies` 读取；selector/command 中的 `DISPATCHER` / `INTERFACE_OPS` 字面比较是设计批准的精确职责收窄，不是第二套角色目录。
- C04 E2E 使用唯一 session key `production-dispatch-demo:c03-session` 和冻结角色；主交互使用可访问名称，没有 `force` 或 CSS primary interaction。
- `.superpowers/**` 只作为未跟踪编排/报告材料，不进入交付提交。
