# 铁路工单服务器独立运行部署记录

日期：2026-11-02，Asia/Shanghai。

## 已启用

- 应用服务器：node6（192.168.101.74）。业务数据库：node4 的 `psms`（openGauss）。
- 独立服务：`project-b-psms-railway.service`，已核验 `enabled`、`active`；`Restart=always`、失败后10秒重启。
- 调度：每天北京时间12:30自动生成不同的模拟铁路工单，导入并逐阶段执行。不依赖本机、浏览器、IDEA或网页服务进程。
- 完整网页服务已更新；`PSMS_RAILWAY_ENABLED=true`，`PSMS_RAILWAY_INLINE_WORKER=false`，由独立服务执行调度，网页后端提供查询及经授权的操作。
- 网页服务的 `AUTO_SEED=false`、`OPENGAUSS_AUTO_MIGRATE=false`，本次未播种或重置原业务表。独立执行器只创建运行表及 SIM-RAIL 前缀的模拟业务记录。

## 实测证据

- 服务器数据库工单 `SIM-RAIL-20261102-WO` 状态为 COMPLETED，对应运行步数16、阶段流水16条，无运行错误。
- 服务器本机经授权请求 `/api/railway` 返回HTTP 200，`source.kind=SERVER_DATABASE`、`source.database=psms`，返回上述同一工单。
- 独立服务重启后保留原工单及进度，没有重复建单；已修复旧连接助手在高频借用连接时重复增加监听器的问题。
- 自动建单、去重、持久化推进、失败回滚、服务停止后不再触发新建单等定向测试5项通过。

## 查看与运维

线上入口：`https://100.65.200.125:18443/production-dispatch/`；运行台加 `?railway=1`。使用原业务账号登录后查看服务器工单。本机localhost默认仍为独立验证库，界面明确区分来源，不能用本机记录代替服务器数据。

```sh
systemctl is-enabled project-b-psms-railway.service
systemctl is-active project-b-psms-railway.service
journalctl -u project-b-psms-railway.service -n 50 --no-pager
```

数据库核验SQL见 `docs/railway-runtime.md`。当天12:30之后恢复服务会补建当天工单，历史未完成工单继续推进，不批量伪造过去日期的运行流水。服务器和数据库需保持运行；工单及设备回执均明确标记为模拟，不代表真实现场执行。

## 备份

- 独立执行器：`/opt/project-b/PSMS/.deploy/20261102-independent-railway-worker`。
- 网页服务与静态资源：`/opt/project-b/PSMS/.deploy/20261102-persistent-railway`。
- 网页服务配置增量：`/etc/systemd/system/project-b-psms.service.d/railway.conf`。
- 独立服务配置：`/etc/systemd/system/project-b-psms-railway.service`。

回退网页程序不会停止独立调度服务。若需停止自动建单，应显式停止并禁用独立服务；不要删除已产生的业务记录或审计流水。
