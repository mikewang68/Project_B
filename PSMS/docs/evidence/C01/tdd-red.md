# C01 测试驱动红灯证据

- 执行时间：2026-07-18 23:19:39（Asia/Shanghai）
- 工具链：Node.js 24.18.0、pnpm 11.10.0、Vitest 4.1.10
- 命令：`pnpm test`（自动化环境设置 `CI=true`，实际调用 Corepack 生成的 `pnpm.cmd`）
- 结果：退出码 1，5 个测试文件中 3 个失败、2 个通过；8 个断言中 5 个失败、3 个通过。
- 预期失败原因：`src/app/App.tsx`、`src/app/router.tsx`、`src/app/routeCatalog.ts` 尚未创建，5 个实现相关断言均收到 `false`，而依赖基线和离线资源测试通过。
- 判定：红灯有效，失败由缺少 C01 生产实现导致，不是依赖、语法或测试装载错误。
