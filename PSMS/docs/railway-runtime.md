# 铁路工单自动导入与持久化运行

每天北京时间12:30，常驻服务自动生成不同的铁路模拟工单，导入系统并启动执行，不需要邮箱和手工上传。工单、自动确认、设备状态和回执均标记为模拟，不代表真实现场作业。

## 使用

原界面顶部“铁路工单运行台”；本机地址 `http://localhost:5173/?railway=1`。
原“调度总览”顶部新增“最新铁路工单 · 数据库实时读取”区域，每5秒更新实际工单、业务日期、状态、进度和数据库更新时间，并明确标记服务器库或本机验证库。原固定场景日期标为“历史样例日期”，不冒充最新工单日期。工单详情和数据库流水可由该区域进入运行台查看。

后台每15秒推进一个阶段：接收计划、模拟确认、生成工单及4个任务、分配模拟资源、模拟接单、开始作业、到达核验、卸车、转运、入库复核、工单完成和计划归档，共16阶段，约4分钟。页面每3秒查询数据库。

每天生成稳定唯一编号，重复调度不会重复执行。当天12:30之后启动服务时补建当天工单，不批量补历史日期。未完成的历史工单按数据库游标继续推进，不伪造停机期间流水。关页面不影响后台，关闭主机则暂停。手工生成和导入按钮仅作为额外验证入口。

## 本机与服务器

首次在 `PSMS/backend` 执行 `pnpm --ignore-workspace install --frozen-lockfile` 安装依赖。之后正常启动前端 `pnpm dev` 即会自动启动工单后台，异常退出后自动重试，日志在 `backend/.data/railway-runtime.log`。关闭浏览器页面不会停止后台；关闭开发服务或关机则暂停，下次启动时接续运行，并在12:30以后补建当天工单。也可手工执行后端 `pnpm dev:railway`，前端会复用已运行的服务。本机服务仅监听127.0.0.1:3101，使用 `backend/.data/railway-demo/` 下持久化的 PGlite PostgreSQL 内核数据库，不使用共享数据库连接，也不提供外部数据库端口。

服务器完整后端支持既有认证下的 `/api/railway` 管理接口。SSO模式保留IAM授权约束。私有 `backend/.env.railway`（Git忽略）配置：

```dotenv
PSMS_RAILWAY_ENABLED=true
PSMS_RAILWAY_ALLOWED_DATABASE=已确认的目标数据库名
PSMS_RAILWAY_AUTO_IMPORT=true
PSMS_RAILWAY_HOUR=12
PSMS_RAILWAY_MINUTE=30
```

只允许独立 psms_demo_* / codex_psms_* 数据库或显式允许的目标库。数据库目标检查先于完整后端迁移和播种。部署前应备份并核对现有服务和依赖，不能复用固定旧日期部署脚本。

服务器独立服务配置见 `deploy/psms-railway-worker.service`：部署编译后的 `dist/scripts/railway-worker.js` 及服务模块，然后安装并启用为 `project-b-psms-railway.service`。它由 systemd 开机自启、异常自动重启，使用 Asia/Shanghai 时区，每天12:30自动建单并写入服务器数据库。不依赖本机、浏览器或网页服务进程。网页后端启用 `PSMS_RAILWAY_ENABLED=true` 提供查询接口，同时设 `PSMS_RAILWAY_INLINE_WORKER=false`，调度交给独立服务。服务器或数据库停机时无法建单，恢复后按当天补建规则接续，不能把停机期间伪造成正常运行。

计划、工单、四项任务和专属模拟设备写入既有public业务表。计划supplements记录simulated=true和PSMS-RAILWAY-SIMULATOR来源，编号统一SIM-RAIL前缀。每个阶段以同一事务提交业务状态、游标、流水和审计；失败回滚并显示ERROR，外部修改状态时停止，不跳过任务强行完成。

## 数据库核验

```sql
SELECT id,status,step,created_at,updated_at,error FROM psms_runtime.runs ORDER BY created_at DESC;
SELECT run_id,step,stage,detail,occurred_at FROM psms_runtime.events ORDER BY occurred_at DESC;
SELECT id,business_date,payload FROM psms_runtime.tickets ORDER BY business_date DESC;
SELECT id,status,actual_start_time,actual_end_time FROM public.work_orders WHERE id LIKE 'SIM-RAIL-%';
SELECT work_order_id,name,status,started_at,completed_at FROM public.tasks WHERE id LIKE 'SIM-RAIL-%';
SELECT trace_id,after_state,occurred_at FROM public.audit_logs WHERE object_type='SIMULATED_RAILWAY_ORDER';
```

本机验证通过不等于负责人openGauss部署完成。本功能不调用真实设备控制接口，不替代现场确认和安全联锁；上线验收结果需单独记录。
