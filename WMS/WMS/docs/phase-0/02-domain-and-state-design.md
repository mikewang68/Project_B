# 领域与状态迁移设计

## 模块边界

新工程采用模块化单体。`warehouse` 管基础资料，`inventory` 管库存事实与库存作业，`stockin`/`stockout` 管单据流程，`finance` 只保留旧 WMS 已有财务辅助能力，`integration` 隔离异步与外部系统。

任何库存数量变化都必须产生库存流水，并与业务单据在同一事务内提交；聚合余额不能绕过流水直接修改。

## 入库状态

旧状态与迁移语义：

| 旧值 | 新枚举 | 含义 |
|---|---|---|
| `create` | `CREATED` | 新单，可编辑/接收 |
| `part` | `PARTIALLY_RECEIVED` | 部分入库 |
| `all` | `FULLY_RECEIVED` | 数量已全部接收，等待关闭 |
| `done` | `COMPLETED` | 已完成，终态 |
| `cancel` | `CANCELLED` | 已取消，终态 |

允许主路径：`CREATED -> PARTIALLY_RECEIVED -> FULLY_RECEIVED -> COMPLETED`；可跳过部分接收。只有非终态可以取消。具体撤销是否回冲库存按旧接口逐用例确认。

## 出库状态

出库必须保留四个状态轴，不能压成一个字符串：

- 单据：`CREATED -> IN_PROGRESS -> COMPLETED`，非终态可到 `CANCELLED`。
- 分配：`NOT_STARTED -> PARTIALLY_COMPLETED -> COMPLETED`。
- 拣货：`NOT_STARTED -> PARTIALLY_COMPLETED -> COMPLETED`。
- 发运：`NOT_STARTED -> PARTIALLY_COMPLETED -> COMPLETED`。

旧值映射：`create/doing/done/cancel` 对应单据轴；`no/part/done` 分别对应三个作业轴。出库只有发运数量满足旧规则时进入 `COMPLETED`；取消分配/拣货需要反向流水和状态重算。

## 库存类作业

盘点、调整、移库、补货统一使用：`CREATED`、`IN_PROGRESS`、`COMPLETED`、`CANCELLED`、`FAILED`。旧 `create/doing/done/cancel/fail` 一一映射；每类作业仍保留自己的合法转换规则。

库存维度不新增需求文档状态机，只迁移旧项目已有概念：

- 实存数量、分配数量、拣货数量、发运数量。
- 冻结/解冻与质量类型（正品、次品、冻结、在途）。
- 库位、批次、货主、仓库、货品、唯一码/RFID。

## 其他状态

- 用户/货品启停：`on -> ENABLED`，`ban/down -> DISABLED`，`delete -> DELETED`。
- 异步任务：`doing -> RUNNING`，`done -> SUCCEEDED`，`fail -> FAILED`。
- 财务单据保留 `create/doing/done/cancel/partdone` 语义，`partdone` 暂映射 `PARTIALLY_SETTLED`，业务名称待财务阶段复核。

## 状态实现约束

- Java 枚举写入数据库使用稳定代码，不使用枚举序号。
- Service 校验状态转换，Mapper 不承载状态机。
- 每次转换记录操作人、时间、原状态、新状态、业务原因、`request_id` 和租户上下文。
- 非法转换统一返回领域错误码；重复幂等请求返回首次结果。

