# Spring Boot / RuoYi 重构基线

> 本文是迁移执行记录，不替代 PRD。业务含义仍以知识库 PRD、需求基线和
> `docs/mock-contracts.md` 为准。

## 已确认决策

| 项目 | 决策 |
|---|---|
| 后端基座 | 若依 RuoYi-Vue 3.9.2 `springboot3` 版本线 |
| Java / Spring Boot | Java 17 / Spring Boot 3.5.16 |
| 安全 | Spring Security + JWT，验证码和会话能力使用 Redis |
| 数据访问 | MyBatis 3.0.5；复杂聚合允许在 mapper 中显式 SQL |
| 数据库 | 保留现有 MySQL 8.4 Docker、库名 `b_demo`、既有 DDL 与构造数据 |
| 前端 | 保留现有 Vue 3 + Vite + Element Plus，Node/pnpm 按服务器清单 |
| 迁移策略 | 契约保持，逐端点替换；FastAPI 在全部契约测试通过前只作对照 |

## 迁移闸门

1. Java 接口保持当前 URL、请求方式、camelCase 字段和若依响应包裹。
2. 每个业务 controller/service/mapper 标注对应 REQ；无锚点不迁入新功能。
3. 统计结果必须查 MySQL 实测；`DEMO_NOW` 固定为 2026-07-12 23:59。
4. `e_alert_event` 继续由规则引擎计算，不允许 seed。
5. 所有写接口通过 datagen reset + pipeline bootstrap 验证可复位。
6. 同一页的 list/detail/count/summary 复用相同的数据权限范围。

## 端点迁移状态

| 批次 | 范围 | 状态 |
|---|---|---|
| M0 | 工程骨架、统一响应、异常处理、健康检查 | 已完成 |
| M1 | 验证码、登录、用户信息、动态路由、退出 | 已完成；MySQL/Redis 实测通过 |
| M2 | 第一幕总览、聚合管道 | 已完成；隔离库全量重建 36,960 小时行、1,750 日行、75 月行，汇总值一致 |
| M3 | 第二幕原始数据质量、补传、重算 | 已完成 |
| M4 | 第三幕告警、设备画像 | 已完成；R01-R11 从数据重算结果为 6/4/0/1/1/1/0/1/3/1/2 |
| M5 | 第四幕建议闭环 | 已完成 |
| M6 | 第五幕成本、报表归档 | 已完成；空成本表生成 75 条不可变 v1，Excel 文件实测通过 |
| M7 | 系统管理、监控、定时任务和 AI Agent | 已完成运行切片；管理扩展接口按若依契约保留 |

## 退场条件

M0-M7 的 Java 单元测试、MySQL/Redis 集成测试、前端测试与生产构建、五幕浏览器走查均已通过。
隔离库还验证了从空派生表执行 bootstrap：发布 3 条六桶基线、回填 42 条基线偏差、
生成 75 条成本记录且无缺失/重复 current key，并由原始数据重新产出 20 条告警。
为避免破坏历史对照和用户已有改动，旧 `backend/` 暂按只读参考保留；运行入口已完全切到 `backend-java/`，生产运行不再依赖 Python。
