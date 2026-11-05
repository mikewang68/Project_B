# EMCS 源码更新与对接

本包交付能源系统的 IAM V2.1 接入、资源动作及范围快照增量。
版本、分支及提交号见总 README。逐文件合并，保留既有能源采集、成本与算法实现。
本包源码增量需放回完整仓库构建，不能用它覆盖成员整份工程。

## 需要合并的内容

- `EMCS/backend-java`：可信 SSO、会话复验、中央范围与原生权限共同生效。
- 设备补传、告警、节能建议、费用申请、报表导出等资源的精确动作检查。
- 隐式确认独立要求 `CONFIRM`，导出独立要求 `EXPORT`，只读不得借 POST 绕过规则。
- 巡检汇总、费用申请快照持久化归属；JSON 和日期按数据库实际类型绑定。
- `EMCS/web`：权限刷新、按钮可见性及实际失败状态展示。
- `EnergyBackgroundPolicy`：拦截尚未适配的 V2 后台模式及错误范围配置。

## 公共依赖与构建

需要 `IAM/context-client`，Maven 坐标 `com.bproject.iam:context-client:2.1.0`。
后端使用 Java `17`、Maven；前端要求 Node `>=24.18.0`，使用包中 pnpm `10.34.5`。
在实验室从完整仓库根目录执行：

```sh
mvn -f IAM/context-client/pom.xml clean install
mvn -f EMCS/backend-java/pom.xml clean package
pnpm --dir EMCS/web install --ignore-workspace --frozen-lockfile
pnpm --dir EMCS/web run build:prod
```

后端产物在 `EMCS/backend-java/target`，前端产物在 `EMCS/web/dist`。
测试使用专门配置；数据库、Redis 与服务凭据由环境提供，不写入源码。
保留锁文件；成员合并造成版本变化时先审查依赖差异，再更新锁文件。

## 数据库与配置

核对既有业务表后，按顺序检查 `EMCS/backend-java/db/v21/001-central-scope.sql`、`002-scoped-snapshots.sql`。
`002` 为两类快照补可空归属字段，无默认归属、无历史数据回填。
已经存在的列先逐一核对类型和约束，不要再次盲目执行非幂等 `ADD COLUMN`。
具体结构说明见同目录 `README-scoped-snapshots.md`。

| Spring 配置项 | 填写规则 |
|---|---|
| `emcs.sso.enabled` | IAM 登记及原生映射完成后设为 `true` |
| `emcs.sso.iam-base-url` | `<IAM地址>/api/v1/iam` |
| `emcs.sso.service-token` | 受保护配置注入 |
| `emcs.sso.bindings-file` | `<受保护主体映射文件>` |
| `b-demo.background.auth-mode` | 当前只支持 `LEGACY_ONLY` |
| `b-demo.background.project-id/region-id` | 仅允许旧模式规定的空值或旧归属 |

不得把人的 `subject-id/session-id` 配给旧定时任务；现有任务开关按负责人决定保留。
业务能耗分区与统一区域分别建模，通过明确映射关联，不能互换编码。
程序回退保留新增字段及已产生数据，不以旧整库备份覆盖新业务记录。

## 更新后的最小检查

1. 门户进入 EMCS，读取本人范围的设备、告警和报表。
2. 列表、详情、统计、导出及写入均拒绝跨项目/区域数据。
3. 只读账号可执行已授权导出；补传、审批、确认、修改等按各自动作检查。
4. 巡检与费用申请记录携带可信归属，历史无归属快照不对区域用户开放。
5. 撤销岗位或统一退出后，用旧会话重放请求应失效。
6. 未实现的算法返回实际状态，不把申请受理或计数汇总展示成计算完成。

## 负责人继续完成

真实能耗采集、日聚合、动态能源数据、成本重算及节能算法由 EMCS 负责人推进。
巡检当前是 `summary_only`，费用重算受理为 `REQUESTED_NOT_EXECUTED`。
缺少节能核验算法的操作应保持明确失败，不能为了页面流程填充成功结果。
后台范围守卫只防误配，完整 V2 后台需逐任务服务身份、范围授权和撤权检查。
合并后反馈提交号、迁移结果及失败复现；中央身份/动作/范围问题由公共能力维护方协助。
