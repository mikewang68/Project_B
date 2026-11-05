# PSMS 源码更新与对接

本包交付生产调度系统的 IAM V2.1 接入增量。版本、分支及提交号见总 README。
请在自己的工作分支逐项合并，保留已有调度和 joint-inbound 业务改动。
源码增量需要放回完整 Project B 仓库编译，不能只解压本目录运行。

## 需要合并的内容

- `PSMS/backend`：可信 SSO、每次请求的中央权限复验、业务范围 SQL、写入归属与只读控制。
- 计划、工单、任务、异常、预约和治理接口的动作检查；任务关联计划时同时验证两个资源。
- 报表导出使用明确 `EXPORT` 权限；确认动作单独检查 `CONFIRM`。
- `PSMS/src`：页面动作、权限刷新、治理页面及已有入站业务的兼容修改。
- `PSMS/docs/baseline/openapi.yaml`：同步接口及状态契约，不只更新页面。
- 后台调度和模拟器加入 `LEGACY_ONLY` 启动/执行检查，防止误标成 V2 范围。

## 公共依赖

必须同时取得 `IAM/context-client-ts`，版本 `2.1.0`。
后端通过 `file:../../IAM/context-client-ts` 引用它，保留仓库相对目录结构。
先构建 SDK 的 `dist`，再安装后端本地依赖；不要沿用旧 SDK 的构建缓存。
完整仓库中的共享派单和入站实现也应保留，不能用旧包覆盖。

## 构建

建议在实验室构建。前端指定 Node `24.18.0`、pnpm `11.10.0`；后端要求 Node `>=20.19.0`。
下面从完整仓库根目录执行；SDK、后端和前端分别使用对应锁文件。

```sh
pnpm --dir IAM/context-client-ts install --ignore-workspace --frozen-lockfile
pnpm --dir IAM/context-client-ts run build
pnpm --dir IAM/context-client-ts test
pnpm --dir PSMS/backend install --ignore-workspace --frozen-lockfile
pnpm --dir PSMS/backend run typecheck
pnpm --dir PSMS/backend run build
pnpm --dir PSMS install --ignore-workspace --frozen-lockfile
pnpm --dir PSMS run build
```

先执行冻结安装检查；锁文件不匹配时审查依赖差异，不直接放宽安装条件。
`PSMS/backend` 的运行入口是 `pnpm start`，前端产物位于 `PSMS/dist`。

## 数据库与配置

先核对已执行迁移，再补 `PSMS/backend/db/v21/001-central-scope.sql`；治理增量见 `backend/sql/028_governance_operations.sql`。
旧行保留 `LEGACY / UNASSIGNED`，不能根据作业区名称批量推断归属。
业务表、共享派单接口与原生作业区约束共同生效；跨系统共享读取仍需明确授权。

| 参数 | 填写规则 |
|---|---|
| `PSMS_SSO_ENABLED` | 完成 IAM 登记和原生映射后设为 `true` |
| `PSMS_IAM_BASE_URL` | `<IAM服务根地址>`，代码会追加 `/api/v1/iam` |
| `PSMS_SSO_SERVICE_TOKEN` | 由受保护环境注入服务凭据 |
| `PSMS_SSO_BINDINGS_FILE` | `<受保护主体映射文件>` |
| `PSMS_RAILWAY_AUTH_MODE` / `PSMS_SIMULATOR_AUTH_MODE` | 当前只支持 `LEGACY_ONLY` |

旧 worker 的 `PROJECT_ID` 仅允许空或 `LEGACY`，`REGION_ID` 仅允许空或 `UNASSIGNED`，不可设置人员会话字段。
构建后可用 `node PSMS/backend/dist/scripts/background-preflight.js RAILWAY` 检查铁路 worker；模拟器参数为 `SIMULATOR`。
更新源码后安排相应进程重启，旧长期进程不会自动加载新检查。

## 更新后的最小检查

1. 门户进入 PSMS、读取本人权限，再读取一个已获权计划或工单。
2. 列表、详情、统计、导出均不能读取其他项目或区域数据。
3. 有权限的写入成功；只读、无 `CONFIRM`、关联计划越界等请求被拒绝。
4. 撤销岗位或退出统一会话后，用原 Cookie 再请求业务接口应失效。
5. 核对现有 PSMS → WMS 入站幂等和状态转换，保留自己分支的接口兼容性。
6. 使用专门样本并记录更新后的提交号、构建结果及失败项。

## 负责人继续完成

真实设备联动、邮件发送、调度算法及新区域业务数据由 PSMS 负责人接续。
后台目前提供防误配检查，完整 V2 后台仍需逐任务服务身份、范围与撤权设计。
新增接口需同时登记菜单动作和资源范围，未适配接口不能临时绕过中央校验。
IAM 授权、主体映射或 SDK 问题携接口、错误码和复现步骤反馈给公共能力维护方。
