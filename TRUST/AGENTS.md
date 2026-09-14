# TRUST · 平台公共能力

本目录是 Project_B 的可信存证与溯源模块，也是后续开发主目录。继承仓库及工作区适用的指令。

## 部署与事实依据

- IPFS、数据库、Fabric、应用分别位于四台机器；IPFS 独立部署、独立启停。
- 真实环境由 Git 排除的 `.local/deployment.json` 配置；服务器读取 `runtime/secrets/deployment.json`。模板是 `deploy/deployment.example.json`，禁止把真实配置提交到 Git。
- 版本与操作步骤见 README 和部署脚本；历史测试摘要见 `docs/test-results/README.md`，不代表当前服务在线状态。
- 功能与验收边界见 `docs/implementation-status.md` 和 `docs/requirements-coverage.md`。真实组件、故障注入和模拟接口页面测试分别记录。
- 仅管理本工程的进程、实例和开发数据。SSH 放行脚本仅由管理员执行，保留主机身份核验和限定转发范围。

## 开发与验证

- 顶层按工程职责、内部按业务功能组织；外部协议位于 adapters，业务代码依赖 ports。详见 `docs/project-structure.md`。
- 接入事件与处理任务同事务提交；同号冲突、追加更正、证据摘要和组织范围是核心正确性约束。
- 构建命令与验证入口见 README。涉及事务/恢复时使用独立测试数据库；故障演练只在专门的开发实例执行。
- 详细测试结果、截图、证据包写入 `.local/test-results`，公开目录只保留脱敏摘要。变更 API 后更新 OpenAPI 与语义说明。
- 提交前检查配置、凭据、运行数据及内部环境信息。同步包必须仅包含 TRUST 模块源码，不包含父仓库。
- 不用桌面 GUI；需要界面检查时使用独立配置的无界面浏览器。
