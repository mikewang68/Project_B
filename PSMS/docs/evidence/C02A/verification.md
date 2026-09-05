# C02A 验证记录

验证日期：2026-07-19（Asia/Shanghai）

## 生成器与契约

- Python 全套测试：`python -m unittest discover -s work\demo_doc_pack\tests -v`，36/36 通过。
- 同步后工程基线直读语义测试：设置 `DEMO_BASELINE_ROOT=...\production-dispatch-demo\docs\baseline` 后运行 `python -m unittest work.demo_doc_pack.tests.test_contract_semantics -v`，12/12 通过。
- 两次连续生成的附件 ZIP SHA-256 一致；当时值为 `84a8017bfda3517e016ed5ef5216dd5b042a6b78a65d4d7bb9a34b38d97e8e3c`。README 冻结说明加入后重新生成的最终附件值为 `df4a8c275067cb1d1191751f274f0d587838d02997fae19bb54444b22f0441a0`，最终提交前还将再做一次一致性验证。

## 文档版式

- Microsoft Word COM 导出 PDF 后转换为 1275×1650 PNG。
- 主技术设计 23 页、页面任务卡 84 页、Mock 接口与数据契约 104 页、任务分解与验收清单 50 页，共 261 页。
- 自动检查：空白页 0、边缘溢出页 0、错误 0。
- 人工逐张 contact sheet 检查：标题、修订表、宽表、代码块、页眉页脚和末页均无阻断问题。
- 本机未安装 LibreOffice，按文档技能的允许回退方式使用已安装的 Microsoft Word 渲染。

## C01 回归

- 单元测试：Vitest 5/5 文件、9/9 断言通过（单 worker）。
- 构建：Vite 8.1.4 构建通过，1489 modules transformed。
- E2E：Playwright 14/14 通过，使用本机已安装 Chrome；临时 QA 配置只补齐 Chrome 会主动请求的 `/favicon.ico` 204 响应，未修改工程源文件或工程配置。
- 实际工具运行版本：Node.js v24.14.0、pnpm 11.9.0；工程冻结声明仍为 Node 24.18.0、pnpm 11.10.0，未修改依赖和锁文件。
- 已知限制：不声明 `tsc --noEmit` 通过；保留 C01 交接中记录的 React 类型声明限制，不安装或升级任何包。

## Git 与边界

- C01 基线提交：`eb8487fa76d6f506fe24650d40fd03137efd514c`。
- C02A 规格提交：`c95203b4a78958afde2932cc47e2c09e7655ed5f`。
- 分支：`demo/c02-contracts-mock`。
- `git diff --check`：通过。
- `src/contracts/**` 与 `src/mocks/**` 均不存在；本次没有改动任何 `src/**`、Store、状态机、RBAC、审计持久化或页面源码。
- E2E 自动更新的 C01 截图已还原到提交版本，C01 历史证据保持不变。
