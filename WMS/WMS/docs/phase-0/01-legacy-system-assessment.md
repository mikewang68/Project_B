# 旧系统评估与迁移边界

## 功能域

| 新模块 | 旧模型/蓝图/页面来源 | 第一轮迁移范围 |
|---|---|---|
| `auth` | `models/auth.py`、`blueprints/auth`、`components/system` | 本地登录、当前用户、角色、菜单、租户选择和配置 |
| `warehouse` | `models/warehouse.py`、`blueprints/warehouse`、`components/warehouse` | 仓库、库区、工作区、库位、货类、货品、合作伙伴、映射、唯一码生成 |
| `inventory` | `models/inv.py`、`blueprints/inv`、`components/inv` | 查询、预警、冻结、补货、移库、盘点、调整、RFID/唯一码 |
| `stockin` | `models/stockin.py`、`blueprints/stockin`、`components/stockin` | 入库单、快捷/条码/RFID、调拨、退货和关联入库 |
| `stockout` | `models/stockout.py`、`blueprints/stockout`、`components/stockout` | 出库、分配、拣货、扫码、发运、波次、调拨、退货 |
| `finance` | `models/finance.py`、`blueprints/finance`、`components/finance` | 旧系统已有费用、应收应付、汇总与出入库统计 |
| `dashboard` | `blueprints/index`、`components/dash` | 首页指标与 ECharts 展示 |
| `integration` | `blueprints/auth/views_async.py`、`blueprints/qimen` | Excel导入导出、异步状态、奇门接口；`mes`/`share` 调用迁移到对应阶段再确认 |

## 数据与关系

- 41 个模型及 1,063 个字段以 `generated/model-*.csv` 为迁移数据字典。
- 源码只声明 9 个数据库外键；更多关联依赖 `*_code`、`*_id` 约定，迁移时不能假设数据库已保证引用完整性。
- `u_company`、`u_user` 位于旧认证bind，业务表位于默认bind。新系统把认证和业务数据统一迁入独立 `mt_wms` 数据库。
- `company_code`、`warehouse_code`、`owner_code` 是旧租户过滤键。新系统保留其业务语义，并由服务端Session与租户上下文控制。

## 接口与页面

- 源码当前存在 166 个唯一 Flask 路由，不是 156 个；清单保留模块、蓝图、方法、函数和行号。
- 57 个 Vue 路由与 544 条 HTTP 调用证据可用于建立页面—接口回归矩阵。
- `mes`、`share` 以及动态拼接路径没有对应的本地后端首段，归为外部/待确认依赖，阶段 1 不模拟真实业务结果，只提供明确的不可用或 Mock 状态。
- 新接口不承诺逐URL兼容；按 `/api/v1/**` 重新设计，同时在迁移用例中记录旧函数来源。

## 必须修正的旧工程问题

- 浏览器端硬编码 `127.0.0.1:5002`，改为Axios相对路径。
- Vue Router升级到4并使用Hash模式，避免单JAR刷新回退配置。
- Flask登录改为Spring Security服务端Session；旧用户和密码迁移规则在阶段2确认。
- 菜单、角色、按钮条件散落在 `App.vue` 和组件内，改为统一路由注册源与权限指令。
- 精确数量和金额改用 `BigDecimal`；库存写操作使用显式事务、幂等键和条件更新/锁。
- 旧配置开关先迁移语义，不直接复制错误命名或前端默认值为最终生产配置。

## 明确不做

- 不修改旧项目。
- 不把 Python 逐行翻译为 Java。
- 不添加需求文档中但旧项目没有的功能。
- 不建设S0～S6跨模块体系。
- 不引入B项目技术基线中的平台、视觉和部署约束。
