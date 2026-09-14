# 工程目录与依赖约定

顶层按工程职责组织，工程内部按业务功能组织，外部组件的协议实现放在适配器中。目录只承载已有职责，不预建空模块。

```text
TRUST/
├─ backend/                       Java 应用、数据库迁移和 Java 测试
│  └─ src/main/java/com/bproject/trust/
│     ├─ TrustApplication.java    启动与组件扫描
│     ├─ events/                  事件接收、幂等、更正、导入和关联索引
│     ├─ evidence/                证据暂存、文件校验与授权下载
│     ├─ archiving/               持久任务、补办和存证记录规则
│     ├─ trace/                   正反向关联查询
│     ├─ verification/            分项核验和证据包导出
│     ├─ identity/                登录配置、角色与当前组织范围
│     ├─ audit/                   操作审计写入和查询
│     ├─ operations/              运行状态汇总
│     ├─ ports/                   证据存储与账本接入接口
│     ├─ adapters/ipfs/           Kubo HTTP 协议、CID 与容量读取
│     ├─ adapters/fabric/         Gateway、TLS、证书与交易提交
│     └─ shared/                  JSON 规范化与统一 HTTP 错误处理
├─ frontend/
│  └─ src/
│     ├─ App.vue                  登录态、页面选择与跨页面事件连接
│     ├─ app/                     工作台外壳与导航
│     ├─ features/                auth、events、evidence、archiving、trace、
│     │                           verification、audit、operations
│     └─ shared/                  HTTP/CSRF、反馈、轮询、通用显示与样式
├─ contracts/                     独立 Go 合约工程
├─ deploy/                        组件部署、构建、启停和服务器验证工具
├─ scripts/                       本机同步、访问、备份、样例与离线校验入口
├─ tests/                         跨工程组件、浏览器和恢复验证
├─ samples/                       明确标注的模拟材料与导入模板
└─ docs/                          接口、需求映射、运行说明与验证记录
```

功能目录中就近放置控制器、服务、输入模型；简单功能不再额外套一层 `controller/service/model` 目录。事件导入与普通录入共同调用事件服务，继续使用相同的幂等与事务规则。查询、审计、身份解析分别由对应功能维护。

业务服务依赖 `ports/EvidenceStorage` 和 `ports/LedgerGateway`；只有适配器引用 Kubo 协议、Fabric SDK 和证书文件。存证记录比较属于 `archiving/AttestationRecord`，业务核验不再调用 Fabric 实现类的静态方法。IPFS 和 Fabric 的节点配置继续由现有部署配置管理。

当前数据库操作仍由功能服务通过 JDBC 完成，驱动、连接池和迁移在应用配置中。后续增加复杂查询时，在对应功能目录内提取 Repository；只有存在数据库专属接入逻辑时才建立相应适配器，避免空包装。

前端每个功能就近放置页面、组件、API 调用及需要复用的状态逻辑。工作台外壳通过插槽承载页面，`App.vue` 仅负责装配。页面缓存保留查询条件，隐藏页面暂停轮询；重新登录时重建页面状态。证据列表和核验结果分别由各自功能维护。现有动态 API 投影字段仍通过 `ApiRecord` 表达，本次不改变服务端数据格式。

Java 单元测试放在对应功能包；跨事件、证据、任务和权限的数据库测试放在 `backend/src/test/java/com/bproject/trust/integration`。跨进程、跨服务器和无界面浏览器测试继续留在顶层 `tests`。

新增功能时，先确定所属业务目录，再补该功能的 API、处理逻辑和适当的验证。新增外部实现应实现现有端口；业务类不能直接依赖 `adapters`，也不把功能代码集中回全局控制器、`App.vue` 或通用工具目录。

目录迁移不改变 `/api/v1` 路径、数据库迁移文件、摘要格式、合约名称或节点分工。同步会校验并备份迁移清单中的旧源码，再删除旧路径，防止远端同时扫描到两套控制器；构建使用 `clean` 清除旧包的编译缓存。旧源码若已被服务器端修改，同步明确停止并提示核对。

前端使用 `pnpm format` / `pnpm format:check` 维护排版。Java 使用 `deploy/format-java.sh`；从服务器格式化回传时应以本机当前源码为准，避免覆盖尚未同步的修改。

## 仓库与配置边界

本模块位于 Project_B/TRUST，使用父仓库的 Git 历史。服务器工程目录由私有部署配置决定；本机主目录变化不要求移动服务器账本和存储。

部署参数由 deploy/deployment.py 统一校验；PowerShell、Python 和 Bash 入口读取相同配置。模板保存在 deploy，真实配置位于 .local 或服务器 runtime/secrets。打包工具按模块顶层职责白名单遍历并排除凭据、运行数据、依赖和编译制品。

详细测试资料写入 .local/test-results，公开的 docs/test-results 只保留脱敏摘要。旧工程目录与原始证据留在本机作为迁移备份，后续代码维护统一从本模块进行。
