# C10 离线同步 / UI-010 最终验证

## 验收结论

C10 在 `demo/c10-offline-sync` 上通过功能、回归、构建、浏览器、契约边界与视觉证据验收。SCN-06 仅承担“冲突识别与恢复引导”；OFF-001 在该场景执行 RETRY 时返回 `TOS-OFF-001`，不发生领域写入。标准处理闭环只在重置到 SCN-01 后演示。

- 验收日期：2026-07-22（Asia/Shanghai）
- C09 基点：`de81e5a22cf1972133cbdee03e3b281723aca2da`
- 验收时实现 HEAD：`976c7057ed0cb5afe4c4c094762469846271d560`
- 分支：`demo/c10-offline-sync`
- 仓库形态：普通仓库，`git-dir` 与 `git-common-dir` 均为 `.git`
- 合并 / 推送：未执行

## 新鲜验证结果

| 验证项 | 命令 | 退出码 | 实测结果 |
| --- | --- | ---: | --- |
| C10 定向 Vitest | `node_modules\.bin\vitest.CMD run src/features/offline-sync/__tests__ src/pages/__tests__/OfflineSyncPage.* --maxWorkers=1` | 0 | 9/9 文件、55/55 测试通过；60.59s |
| 全量 Vitest | `node_modules\.bin\vitest.CMD run --maxWorkers=1` | 0 | 85/85 文件、663/663 测试通过；571.67s |
| Production build | `node_modules\.bin\vite.CMD build` | 0 | Vite 8.1.4；1951 modules；1.16s |
| C10 Playwright | `node_modules\.bin\playwright.CMD test e2e/ui-010-offline-sync.spec.ts` | 0 | 4/4 通过；40.7s |
| 全量 Playwright | `node_modules\.bin\playwright.CMD test` | 0 | 43/43 通过；8.1m |
| TypeScript | `node_modules\.bin\tsc.CMD --noEmit` | 1 | 170 条既有 React/JSX 声明链诊断；其他诊断 0；C10 非 React `.ts` 诊断 0 |
| 冻结哈希 | `Get-FileHash ... -Algorithm SHA256` | 0 | 6/6 MATCH |
| 范围与图片 | Git diff、merge count、System.Drawing 原图尺寸检查 | 0 | 禁止范围改动 0；merge 0；8 张图尺寸全部匹配 |

全量 Playwright 中仍会输出既有 Ant Design 警告：`Message` 在 render 中触发，以及 `List` deprecated。两类告警不导致测试失败，C10 未扩大处理范围。全量测试会重拍历史证据 PNG；验收后已将 C01、C04–C09 和 C10 的自动重拍差异恢复到各自提交版本。

## TypeScript 基线比较

| 诊断 | C10 前 | C10 后 | 说明 |
| --- | ---: | ---: | --- |
| TS7016 | 38 | 39 | 缺少 React / `react/jsx-runtime` 声明 |
| TS7026 | 119 | 114 | 缺少 `JSX.IntrinsicElements` |
| TS2604 | 17 | 17 | React 组件声明链缺口 |
| 其他 | 0 | 0 | 无新增其他诊断 |
| 合计 | 174 | 170 | 净减少 4；仍属于同一冻结 React 类型基线 |

完整前后输出分别见 [tsc-before.txt](./tsc-before.txt) 与 [tsc-after.txt](./tsc-after.txt)。C10 的 `.ts` 投影、查询、网关、命令与运行时文件没有 TypeScript 主诊断。

## 冻结文件复核

| 文件 | SHA-256 | 状态 |
| --- | --- | --- |
| `docs/baseline/README.md` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` | MATCH |
| `docs/baseline/package-baseline.json` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | MATCH |
| `docs/baseline/openapi.yaml` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` | MATCH |
| `docs/baseline/demo-fixtures.json` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` | MATCH |
| `docs/baseline/page-task-matrix.csv` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | MATCH |
| `docs/baseline/traceability.csv` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | MATCH |

结果为 `6/6 MATCH`。没有修改冻结 fault、OFF-001..OFF-004 fixture、公共契约、权限目录、状态机目录或 C09 所属 feature/page。SCN-06 的路由修正仅把冻结 `OFFLINE_VERSION_CONFLICT` 精确限定到 API-019 的 seed `OFF-001`，从而保持 API-018 可读取并在用户触发 RETRY 时返回原有 `TOS-OFF-001`。

## 截图证据

- 数量：恰好 8 张。
- 1440 × 900：4 张。
- 1280 × 720：4 张。
- 状态：CACHED、UPLOADING/PENDING_UPLOAD、CONFLICT、MERGED，各 2 个视口。
- 自动断言：无横向溢出；5 个动作按钮均有正尺寸；选中行、版本比较、处置面板和命令反馈均与当前视口相交。
- 人工原图检查：未发现裁切、遮挡或重叠。

逐图字段与可见性记录见 [screenshot-index.md](./screenshot-index.md)，机器可读覆盖见 [coverage.json](./coverage.json)。

## 变更范围与已知限制

最终交接集包含 50 个相对 C09 基点的 C10 文件变更：新建 C10 feature、UI-010 页面测试、API mock 精确路由、共享 runtime 接入、E2E 与证据/交接文档。禁止范围匹配数为 0，C09 基点之后 merge commit 数为 0。

已知限制保持 Demo 边界：

- 使用内存 Store 与 mock API，不连接真实终端、数据库、文件协议、消息队列或自动冲突合并。
- `workOrderNo` 只是来源文本上下文，不声明生产外键。
- `tsc --noEmit` 因仓库冻结的 React 类型声明缺口仍返回 1；本次没有引入其他诊断。
- 构建保留既有大 chunk warning；环境实测 Node/pnpm 低于仓库声明，但本轮测试与构建均已通过。
