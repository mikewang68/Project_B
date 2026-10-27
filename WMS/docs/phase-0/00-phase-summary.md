# 阶段 0 总结

状态：已完成

## 本阶段基线

1. `MT-WMS重构实施计划.md`：实施范围与技术约束。
2. `mt-wms-demo`：旧后端行为证据。
3. `mt-wms-demo-frontend`：旧页面、菜单、配置和接口调用证据。

本阶段不使用B项目技术基线或需求规格说明书扩展旧系统功能；相关提取文件已经删除。

## 盘点结果

| 对象 | 源码实测 | 说明 |
|---|---:|---|
| SQLAlchemy 模型 | 41 | 另有 1,063 个字段、9 个显式外键 |
| Flask 路由 | 166 | 全部路径唯一；计划中的 156 是早期估算，以源码实测为准 |
| Vue 路由 | 57 | 覆盖 55 个左右页面组件及复用路由 |
| 前端 HTTP 调用证据 | 544 | 包含同一接口在不同动作中的重复调用 |
| 菜单/权限注册证据 | 71 | 含声明项与模板可见性条件 |
| 前端配置默认项 | 22 | 另有服务端动态配置，迁移时逐项确认 |
| 旧角色证据 | 10 | 系统角色与公司内业务角色并存 |
| 状态字面量证据 | 242 | 已归纳为入库、出库、库存作业、异步任务等状态轴 |

可复查原始清单：

- `generated/model-inventory.csv`、`model-column-inventory.csv`、`model-relation-inventory.csv`
- `generated/api-inventory.csv`、`frontend-api-usage.csv`
- `generated/page-route-inventory.csv`、`menu-inventory.csv`
- `generated/config-inventory.csv`、`permission-inventory.csv`
- `generated/state-literals.csv`、`inventory-summary.json`

## 结论

- 新系统继续覆盖本地登录权限、仓库资料、库存、入库、出库、财务、统计、异步导入导出和奇门接口。
- 第一轮按旧行为还原，不做业务流程再造，不添加散料、设备联锁、数字孪生等旧系统不存在的功能。
- 旧系统的两个数据库连接、前端直连地址、Vue 2/Vuex/Ant Design Vue不直接照搬；三个租户字段保留业务语义并由后端统一过滤。
- 新工程使用模块化单体、前端内嵌、单JAR和独立openGauss数据库。
- 登录使用Spring Security服务端Session，不依赖S0、OIDC、模块注册或跨模块契约。
