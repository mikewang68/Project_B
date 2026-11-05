# SCS 源码更新与对接

本包交付安全管控系统的区域权限、范围实时刷新与可信机器更新入口。
版本、分支及提交号见总 README。逐文件合并现有分支，保留设备、模型和规则业务修改。
源码增量需放回完整仓库编译，数据库增量与后端代码一起核对。

## 需要合并的内容

- 中央 SSO 与每次请求的菜单动作、项目/区域及原生权限检查。
- 规则、围栏、告警、人员、设备、边缘节点等资源读取和原子写入范围约束。
- 浏览器 WebSocket 只推送获权范围的刷新通知，详情继续通过鉴权 HTTP 获取。
- 机器来源登记支持已有对象的人员位置、设备状态、区域状态、UWB 站点更新。
- 来源、对象白名单、有效期、停用及字段防篡改检查与前端刷新策略一起合并。
- 完整契约见 `SCS/docs/scoped-realtime-machine-contract.md`。

## 公共依赖与构建

需要同版本 `IAM/context-client`，Maven 坐标为 `com.bproject.iam:context-client:2.1.0`。
使用 Java `17`、Maven；前端要求 Node `>=24.18.0 <25`、pnpm `>=10.34.5 <11`。
从完整仓库根目录在实验室执行：

```sh
mvn -f IAM/context-client/pom.xml clean install
mvn -f SCS/backend/pom.xml clean package
pnpm --dir SCS install --frozen-lockfile
pnpm --dir SCS/frontend run test:unit
pnpm --dir SCS/frontend run build
```

前端使用 `SCS/pnpm-workspace.yaml` 与 `SCS/pnpm-lock.yaml`，安装从 SCS 工作区执行。
前端产物在 `SCS/frontend/dist`；发布时后端与前端应取同一提交。
后端测试中依赖数据库或消息服务的场景使用专门测试配置，不能套用业务库清理脚本。

## 数据库与配置

先检查 `SCS/backend/db/v21/001-central-scope.sql`，它只覆盖规则和围栏。
还必须具有九根资源表的可空三属性及 `safety.scope_runtime_ownership` 增量，执行 `SCS/backend/db/v21/002-resource-scope.sql`；前置核对见同目录 README。
缺少扩展资源增量时不能启用 V2；仅执行 `001` 不够。
旧数据保留 NULL 或明确旧归属；不得批量填某个项目/区域冒充已核定。

| 参数 | 填写规则 |
|---|---|
| `APP_SECURITY_IAM_BASE_URL` | `<IAM服务地址>`，按当前适配器的基础路径约定填写 |
| `APP_SECURITY_IAM_SERVICE_TOKEN` | 由受保护配置注入 |
| `APP_SECURITY_IAM_AUDIENCE` | `scs` |
| `app.security.machine-bindings-file` | `<受保护机器来源JSON>`，未登记时保持空 |

机器来源文件只允许服务读取，登记可信三属性、来源口令摘要、路由及精确对象 ID。
来源请求使用 `X-SCS-Source-ID` 与 `X-API-Key`；客户端不能提供范围或 SQL 条件。
旧共享机器凭据不能更新有归属或部分归属对象，不能用来建立浏览器会话。

## 更新后的最小检查

1. 门户登录并读取一个有归属资源；跨项目/区域读取、编辑、删除均拒绝。
2. 普通与只读账号都只能收到获准查看资源的刷新，不接收原始坐标或业务明细。
3. 在同一 WebSocket 连接撤销权限、切换允许范围、停用账号，旧范围不继续收消息。
4. 一个受保护机器来源只能更新白名单内已有对象，同区域的其他来源对象仍拒绝。
5. 停用或过期来源、错口令、伪造范围、混合批次均拒绝且无部分越权写入。
6. IAM 不可达或根对象归属查证失败时拒绝，不回退到旧数据广播。

## 负责人继续完成

登记真实设备和真实对象映射，核对协议、坐标系、传感数据及实际控制回执。
当前四条机器路由更新已有对象；radar/AI 新根派生需先确定来源及幂等 ID 归属契约。
模型训练、现场识别准确性及设备联动由 SCS 负责人继续验收。
新增实时事件应补明确根对象及菜单映射，不能向全体连接广播未解析数据。
合并后反馈提交号、结构检查与接口结果；身份/范围机制问题由公共能力维护方协助。
