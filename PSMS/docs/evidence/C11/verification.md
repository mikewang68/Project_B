# C11 统计报表最终验证

## 结论

C11 / UI-011 功能、契约、构建、定向测试、浏览器回归和视觉证据通过，可以作为 C12 的上游基线。完整 Vitest 在单 worker 累积运行时仍有两项既有 30 秒慢测超时；两项均无断言失败，并分别隔离全绿。该限制与预检记录的 UI-002 时序抖动属于同一环境基线，本次没有修改超时、旧页面或测试配置。

API-021 未实现、未调用，冻结 baseline、依赖、公共契约、权限、状态机和 C10 业务实现均未改动。

## 范围与提交拓扑

| 项目 | 实测 |
| --- | --- |
| 分支 | `demo/c11-reporting-dashboard` |
| C10 基点 | `7fb529c50059b39f088859d42f421d423e7780f7` |
| 实现与 E2E 证据 HEAD | `7bb9f8c24958be79a0e41beb6dbd8d78f083d47a` |
| C10 之后 merge commit | `0` |
| 合并/推送 | 均未执行 |

最终证据与 handoff 由本文件所在提交补齐；最终分支尖端在交付消息中给出。

## TDD 证据

- 核心投影、查询和指标 RED：`report-core-red.txt`。
- 页面与交互 RED：`report-page-red.txt`，11 项失败、1 项 scaffold 烟测通过。
- E2E RED：`report-e2e-red.txt`，暴露缺少 13 指标可访问摘要，以及 React 开发模式下重复 API-020 请求掩盖 network 状态；两项均在生产代码中修复。
- C11 最终聚焦回归：12 个文件、71 项测试通过。
- 覆盖率回退所用聚焦回归：10 个文件、55 项测试通过。
- C11 Playwright 独立回归：2/2 通过，约 21.1 秒。

## 最终命令结果

| 闸门 | 结果 |
| --- | --- |
| `tsc --noEmit` | exit 1；693 条主诊断，仅 `TS7016=117`、`TS7026=518`、`TS2604=58`；其他诊断码 0，C11 非 React/JSX 诊断 0 |
| 全量 Vitest，单 worker | 95 个文件中 93 个通过；720 项中 718 项通过；2 项 30 秒 timeout，无断言失败 |
| `routeRender.test.tsx` 隔离复跑 | 3/3 通过；主路由用例 13.36 秒 |
| `PlanLedgerPage.render.test.tsx` 隔离复跑 | 11/11 通过；历史慢用例 16.42 秒 |
| production build | 通过；Vite 8.1.4，2,555 modules，约 1.11 秒 |
| C11 Playwright | 2/2 通过 |
| 全量 Playwright | 45/45 通过，1 worker，9.7 分钟 |
| `git diff --check` | 通过，无输出 |

全量 Vitest 的首次最终运行和再次补强运行都只出现同样两项慢测 timeout。第二次直接启动运行器的尝试在进入测试文件前挂起并于 600 秒边界终止；未遗留测试进程。隔离复跑证明两项业务断言均成立，C11 聚焦测试和全量浏览器回归均全绿，因此没有通过放宽测试、修改旧代码或调整配置掩盖环境抖动。

## 覆盖率说明

计划中的 V8 JSON 覆盖率命令在启动前失败，原因是冻结依赖中没有 `@vitest/coverage-v8`。依赖清单与 lockfile 不允许修改，因此未安装 provider。

使用 `NODE_V8_COVERAGE` 的只读回退时，10 个文件、55 项聚焦测试通过，但原始结果仅包含 Vitest 协调进程，不包含 worker 内的 C11 应用模块。无法从该结果诚实计算 line/function/branch/statement 百分比，因此 [coverage.json](./coverage.json) 明确记录 `reportedPercentages: null`，不伪造覆盖率数字。

## 契约与禁止范围审计

| 检查 | 结果 |
| --- | --- |
| 六份冻结文件 SHA-256 | `6/6 MATCH` |
| `docs/baseline/**` 改动 | `0` |
| `package.json` / `pnpm-lock.yaml` 改动 | `0` |
| `src/contracts/**` 改动 | `0` |
| 权限目录改动 | `0` |
| 状态机目录改动 | `0` |
| C10 feature/page/E2E 改动 | `0` |
| C11 production 中 `API-021` 或 `/mock/reports/export` 引用 | `0` |
| C11 command 对 C04-C10 上游 slice 的写引用 | `0` |
| C11 PNG 数量 | 恰好 `8` |

冻结 hash 与预检完全相同：

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

## 视觉证据

全量 Playwright 最后一次重拍后保留 C11 的最新 8 张 PNG；C01/C04-C10 历史截图差异已按白名单恢复。文件尺寸逐张读取：4 张为 1440×900，4 张为 1280×720，文件名与原始像素完全一致。

人工检查已覆盖 overview、filtered、generated、metrics 四个状态的两档视口：无横向页面溢出、裁切、重叠、中文缺字、残留 loading mask、图表空白或主操作遮挡。详细清单见 [screenshot-index.md](./screenshot-index.md)。

## 已知环境告警

- 仓库声明 Node 24.18.0 / pnpm 11.10.0；当前 pnpm shim 报告 Node 24.14.0 / pnpm 11.9.0。
- TypeScript 的 React/JSX 声明缺口为冻结依赖环境基线；C11 没有新增诊断类别。
- build 保留大 chunk 提醒；ECharts 已通过动态 tree-shaken runtime 分块加载。
- 全量浏览器输出保留既有 Ant Design `List` deprecated 和 render 中调用 `Message` 的 warning；45 项用例全部通过。
