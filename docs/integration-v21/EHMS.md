# EHMS 源码更新与对接

本包交付设备健康系统的 IAM V2.1 接入、治理动作和后台范围检查。
版本、分支及提交号见总 README。逐文件合并，保留自己的诊断、工单及报表实现。
源码增量需要完整仓库和已有数据库基础结构，不是独立启动包。

## 需要合并的内容

- `EHMS/source/backend`：SSO、可信主体映射和每次请求的中央权限复验。
- 关系数据、聚合记录与时序访问的范围适配；列表和详情采用同一归属规则。
- 设备、告警、工单及治理动作的读取/写入/确认权限和越界拒绝。
- `EHMS/web`：真实接口调用、权限刷新、操作按钮与只读展示。
- `SimulationBackgroundPolicy`：模拟器启动及执行前拒绝未适配的 V2 后台模式。
- 原有正常主体供给与业务数据兼容保留，未知历史归属不自动回填。

## 公共依赖与构建

后端使用 Java `17`、Maven；需要两个本地公共依赖。
`IAM/context-client` 为 `2.1.0`；`AUDIT/client` 为 `audit-client:1.0.1`，父项目是 `AUDIT/pom.xml`。
在实验室从完整仓库根目录执行：

```sh
mvn -f IAM/context-client/pom.xml clean install
mvn -f AUDIT/pom.xml -pl client -am install
mvn -f EHMS/source/backend/pom.xml -DskipTests clean package
```

上述 EHMS 命令只构建；按测试配置另外选择范围/权限单元测试。
不要把 `SimulationIntegrationTest` 等会清表的测试指向真实业务库。
后端产物为 `EHMS/source/backend/target/ehm-demo-0.1.0.jar`。
`EHMS/web` 是直接发布的 HTML/JS/CSS，当前没有前端 npm 构建步骤。
应发布完整且同版本的 `web` 静态资源，避免新 HTML 引用旧脚本。

## 数据库与配置

先核对历史结构与 `EHMS/sql/027_project_scope.sql` 的项目基础。
再核对 `EHMS/source/backend/db/v21/001-central-scope.sql`。
该增量补聚合表区域/系统字段并扩展主键；执行前确认原主键、引用和 schema。
在同一受控连接先执行 `SELECT set_config('ehm.migration.schema','<已核准schema>',false);`，并设置 `SET search_path TO "<已核准schema>";`，两值必须一致；不要在源码中写入真实名称。
脚本在修改前检查表、主键和已有列，并获取 `NOWAIT` 排他锁；缺配置、结构差异或占用即失败，保留备份并核查后再执行，不循环重试。
未核实历史保留 `UNASSIGNED`，新数据从可信会话继承归属。

| Spring 配置项 | 填写规则 |
|---|---|
| `spring.profiles.active` | 使用既有 `server` 运行配置 |
| `ehm.auth.mode` | `iam` |
| `ehm.sso.iam-base-url` | `<IAM地址>/api/v1/iam` |
| `ehm.sso.service-token` | 受保护配置注入 |
| `ehm.sso.bindings-file` | `<受保护主体映射文件>` |
| `ehm.simulation.auth-mode` | 当前只支持 `LEGACY_ONLY` |

旧模拟器区域只允许空或 `UNASSIGNED`，不能配置人员 `subject-id/session-id` 借用会话后台运行。
数据库、时序库、MongoDB 与审计配置继续采用本环境已有受保护设置。
新增区域应逐项落实关系/聚合/时序归属，不能只给页面加一个区域选框。

## 更新后的最小检查

1. 门户进入 EHMS，读取获权设备和相关工单、告警。
2. 跨项目/区域的列表、详情、统计、导出及关联资源都不泄漏数据。
3. 普通与只读用户分别操作同一治理页面，写动作按明确权限拒绝或通过。
4. 撤销角色、停用人员或统一退出后，用旧会话再次请求必须失效。
5. 旧模式模拟器状态按原权限读取；V2 人员不能借旧模拟接口取得全局数据或产生全局写入。
6. 核对审计接收和最终存证分别的状态，不把接收成功等同链上完成。

## 负责人继续完成

真实设备映射、健康诊断、预测算法、健康报表和真实时序数据由 EHMS 负责人推进。
旧模拟器的范围守卫不表示完整 V2 后台已实现；新增任务需服务身份和明确数据范围。
新增接口应同时约束关系对象、聚合数据和时序查询，不能仅过滤设备列表。
合并后反馈提交号和最小检查结果；中央身份与公共审计接入问题由公共能力维护方协助。
