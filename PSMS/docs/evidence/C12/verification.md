# C12 审计日志最终验证

## 结论

C12 已按冻结基线完成 UI-013 `/governance/audit`。页面以共享 Store 为唯一领域事实源，严格投影 DO-013 与真实 runtime command audit，单独验证 API-024 读取身份，并提供组合筛选、严格详情、trace 聚合、错误恢复和只读说明。

DO-013 主键始终是 `id`；`auditLogId` 仅保留在 API envelope 读取观察中。冻结场景没有独立幂等审计记录，初始与 reset 后幂等命中均为 0；生产代码没有构造 `idempotent: true` 或伪造 DO-013。只有投影单测使用显式 future feedback 验证未来分类分支。

C12 聚焦 Vitest、生产构建、C12 Playwright 和全量 Playwright 均通过。项目原生全量 Vitest 为 764/765，唯一失败是范围外的历史 UI-002 SCN-02 用例在整套并发负载下超过 30 秒；该用例隔离复跑 19.87 秒通过，无断言失败。没有修改历史用例、全局 timeout 或测试配置。

## 范围与提交拓扑

| 项目 | 实测 |
| --- | --- |
| 分支 | `demo/c12-audit-trail` |
| C11 基点 | `507a715046dc7e9858e638b07bb56e920e70da37` |
| 最终实现/测试 HEAD | `c69bef30ffff06041f00343eebdf0db8244859c9` |
| C11 是否为祖先 | 是，`git merge-base --is-ancestor` exit 0 |
| C11 之后 merge commit | `0` |
| 合并/推送 | 均未执行 |

最终截图、verification、coverage 和 handoff 由本文件所在提交补齐；最终分支尖端在交付消息中给出。

## TDD 证据

- 核心投影与查询 RED：[`audit-core-red.txt`](./audit-core-red.txt)。两个测试文件因生产模块尚不存在而失败，未执行测试；随后实现严格 DO-013 投影、去重、冲突检测、分类、查询清洗和 trace。
- 页面 RED：[`audit-page-red.txt`](./audit-page-red.txt)。3 个文件中 7 项失败、1 项冻结路由拒绝测试通过；允许访问时仍是 scaffold，找不到审计台账、KPI、筛选、详情和链路。
- 视觉 RED：[`audit-e2e-red.txt`](./audit-e2e-red.txt)。自动化 3/3 通过，但原分辨率检查发现抽屉关闭动画残留遮罩和 1280 详情缺少首屏关闭入口；修复为等待抽屉完整卸载并恢复标准顶部关闭按钮。
- 最终 C12 聚焦回归：9 个文件、57/57 项通过，21.68 秒。
- 测试负载优化后，C12 筛选/详情/链路流程隔离复跑 1/1 通过，用例阶段 10.42 秒；断言未删减。
- C12 独立 Playwright：3/3 通过，约 26.8 秒；全量末次执行中的同三项仍全部通过。

## 最终命令结果

| 闸门 | 结果 |
| --- | --- |
| C12 focused Vitest | 9/9 文件、57/57 测试通过 |
| 项目原生全量 Vitest | 102/103 文件、764/765 测试通过；唯一历史 UI-002 30 秒 timeout |
| 历史 UI-002 慢测隔离复跑 | 1/1 通过；用例阶段 19.87 秒 |
| `tsc --noEmit` | exit 1；完整输出 909 行、783 条主诊断，仅三类 React/JSX 声明链诊断 |
| production build | 通过；Vite 8.1.4，2,569 modules，1.96 秒 |
| C12 Playwright | 3/3 通过 |
| 全量 Playwright | 48/48 通过，1 worker，8.0 分钟 |
| `git diff --check` | 通过，无输出 |

全量 Vitest 的最终有效运行使用项目原生 `pnpm test`。一次带字面 `--` 的运行不能正确限制 worker；随后两次真正的单 worker 运行在 5 分钟上限内未完成。超时命令留下的一个本轮 Node 进程已按启动时间精确识别并终止，未触碰其他后台进程。以上异常运行不计作业务验证结果。

## TypeScript 诊断口径

最终命令：`node_modules\.bin\tsc.CMD --noEmit`。

| 统计 | 数量 |
| --- | ---: |
| 输出行 | 909 |
| 主诊断 | 783 |
| TS7016 | 126 |
| TS7026 | 592 |
| TS2604 | 65 |
| 其他诊断码 | 0 |
| C12 相关路径主诊断 | 100 |
| C12 非 React/JSX 主诊断 | 0 |
| `audit-trail` 非 TSX 主诊断 | 0 |

冻结安装包含 React 19.2.7，但没有 `@types/react`；诊断均属于缺少 `react`/`react/jsx-runtime` 声明、缺少 `JSX.IntrinsicElements` 或由此导致的 Ant Design JSX 构造签名缺失。没有修改依赖、manifest 或 lockfile。

终端传输会截断 909 行原始输出。早期 `tsc-after.txt` 仅保存了尾段并错误得到 187 条；该数字已废弃。最终 [`tsc-after.txt`](./tsc-after.txt) 保存完整进程内计数、C12 分类、代表性诊断和截断说明。

## 覆盖率说明

聚焦 coverage 命令在启动覆盖收集前失败，原因是冻结安装缺少 `@vitest/coverage-v8`。依赖边界禁止安装 provider，因此没有生成或伪造 line/function/branch/statement 百分比。

[`coverage.json`](./coverage.json) 记录实际命令、exit 1、缺失 provider 和 `trustworthyPercentageProduced=false`；行为覆盖由 57 项聚焦测试、3 项 C12 E2E 与 48 项全量浏览器回归独立记录。

## Store、API 与 reset 事实

- `configAudit.audit` 与 `configAudit.commandAudit` 是领域审计事实；API-024 success items 仅做严格 identity/shape 校验，不覆盖 Store。
- API-024 `traceId`/`auditLogId` 保存在 feature-local read observation，不计入 DO-013 数量，也不拥有 `record.id`。
- network、malformed 和 business error 只更新 C12 workflow，已经显示的 Store 台账保持可见。
- 冻结 9 条无 metadata 的 DO-013 只分类为 `RECORDED`，不推断 `SUCCESS`。
- `SUCCESS`、`DENIED`、`VERSION_CONFLICT`、`BUSINESS_ERROR` 只来自现有 runtime metadata；`IDEMPOTENT_HIT` 只接受与真实审计 ID 匹配的明确 future feedback。
- 既有 C04 reset 行为受上游测试锁定：reset 后 `configAudit.audit` 为 9 条，并保留 1 条真实的 `AUD-C04-003 / execute / SCN-01 / SUCCESS` reset command audit。C12 未改变或隐藏该事实。
- reset 后 C12 workflow 精确回到空筛选、无详情/链路、`idle`、非 pending；幂等命中为 0，不新增独立幂等审计记录。
- 集成测试对 plan、recommendation、workOrder、resource、vehicle、exception、interlock、offline 和 report 深快照做前后不变断言。

## 权限与只读边界

- UI-013 路由沿用冻结角色目录 `AUDITOR/REGULATOR/SYS_ADMIN`；不修改权限目录。
- reset 单独使用既有 `demo:reset` 决策。AUDITOR 可查看台账但没有 reset 权限，按钮禁用并明确显示“本页保持只读”。
- 路由拒绝在 API-024 之前发生，不调用接口，也不泄露审计对象是否存在。
- 页面没有导出、打印、下载、归档、验签或幂等重放操作，也不承诺生产日志、合规归档或不可篡改证明。

## 冻结边界审计

| 检查 | 结果 |
| --- | --- |
| 六份冻结文件 SHA-256 | `6/6 MATCH` |
| `docs/baseline/**` 改动 | `0` |
| `package.json` / `pnpm-lock.yaml` 改动 | `0` |
| `src/contracts/**` 改动 | `0` |
| 权限目录改动 | `0` |
| 状态机目录改动 | `0` |
| C11 feature/page/E2E 改动 | `0` |
| C12 production/test artifact 中 `UI-012` | `0` |
| C12 production 中字面 `idempotent: true` | `0` |
| C12 PNG 数量 | 恰好 `8` |

最终 hash 与预检完全一致：

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

## 视觉证据

全量 Playwright 最后一次重拍后，只保留 C12 最新 8 张 PNG；C01/C04-C11 的历史截图差异已按明确目录恢复。尺寸逐张读取：4 张 1440×900，4 张 1280×720，文件名与像素一致。

8 张图均在原分辨率重新检查，覆盖 overview、filtered、detail 和 trace：无文档级横向溢出、中文裁切、控件重叠、抽屉遮罩残留、空白抽屉或链路内容截断。清单见 [`screenshot-index.md`](./screenshot-index.md)。

## 已知环境告警

- 仓库声明 Node 24.18.0 / pnpm 11.10.0；当前 pnpm shim 报告 Node 24.14.0 / pnpm 11.9.0。
- build 保留大 chunk 提醒，但构建 exit 0。
- 全量浏览器输出保留既有 Ant Design `List` deprecated 和 render 中调用 `Message` 的 warning；48 项用例全部通过。
- 完整 Vitest 的历史 UI-002 慢测仍可能在整套并发负载下跨过 30 秒；隔离业务断言通过，C12 没有放宽 timeout 掩盖该事实。
