# PSMS 服务器发布记录

2026-10-12，已将本次修复和千问接入发布至 node6（192.168.101.74）。

访问入口：https://100.65.200.125:18443/production-dispatch/

## 发布范围

- PSMS 前端：`/data/app/project-b-20260921/PSMS/production-dispatch`。
- PSMS 后端运行产物：`/opt/project-b/PSMS/backend/dist`。
- PSMS AI 配置：`/opt/project-b/PSMS/backend/.env.ai`，权限 600，所有者 psms。
- PSMS 数据库迁移：21 张表增加 valid 字段，子表增加历史版本及有效记录唯一索引。没有重置、物理删除现有业务数据。
- 仅重启 `project-b-psms.service`，没有修改其他模块、共享 Nginx 配置或数据库。

模型为 `qwen3.8-27b`，使用北京百炼接口。演示界面自动获取仅可用于 AI 分析的会话，不显示账号密码框；普通业务接口拒绝该会话。
AI 会话仅在组件内保存，不作为 CSV 入库的业务身份。界面业务数据仍使用模拟数据。

## 备份

- 发布备份目录：`/opt/project-b/PSMS/.deploy/20261012-1810`（私有权限）。
- 数据快照：`database-before.json`，包含原 21 表数据、旧 DDL、列和索引信息。
- 后端备份：`backend-before.tar.gz` 及 `/opt/project-b/PSMS/backend/.dist-backup-20261012-1810`。
- 旧服务配置：`service-before.env`。
- 原前端备份：`frontend-before.tar.gz` 及前端目录的 `.backup-20261012-1810` 后缀版本。
- HTTPS 兼容补丁前版本：前端目录的 `.backup-20261012-1829` 后缀版本。

临时上传的 AI 密钥副本已清理，密钥仅由后端读取，不在前端包中。
数据库快照含账号哈希等数据，应保留在服务器私有目录。
回退应用代码不会自动撤销数据库迁移；迁移保留旧记录，并在事务中提交。

## 线上验证

- 服务为 active；openGauss connected=true；openGemini reachable=true。
- 原业务记录计数保持一致：users 5、plans 5、work_orders 3、tasks 2、equipment 15、exceptions 2、interlocks 2、appointments 3、offline_packets 1、config_versions 1、audit_logs 5。
- 21 张表均存在 valid 字段；迁移试验 schema 已由事务回滚清理。
- 在服务器回滚事务内验证迁移、重复迁移、保留记录数量和 valid=0 逻辑删除。
- 通过负责人入口验证 13 个页面全部加载成功（按各页面允许的角色）。
- 在线浏览器验证 CSV 模拟导入、刷新保留、重复拒绝、计划确认、准备建议确认、任务拆解、派工入口、本地规则分析及默认计划选择。
- 在线浏览器验证 AI 自动登录、账号密码框隐藏、真实千问分析及结果显示。

本次发现服务器 HTTPS 证书使浏览器无法注册模拟数据 Service Worker，导致应用启动中断。
已在 PSMS 内加入 fetch 备用处理：仅处理同源 `/production-dispatch/mock/*` 模拟请求，AI 和业务后端 `/api/*` 继续走真实服务。
没有改动共享证书或其他模块。

## 本地检查

- 122 个测试文件、926 项前端全量测试通过（限制 2 个进程，单项超时 60 秒）。
- 后端 22 项测试通过，包括 AI 演示会话与业务权限隔离。
- HTTPS 备用处理和 AI 助手 6 项针对性测试通过。
- 前后端 TypeScript 检查及生产构建通过。
- 首轮高并发测试出现超时，随后正常运行环境的受限并发全量回归通过。

最终产物 SHA256：

```text
frontend index.html  3c755107b790a785afad8f5b004fe557f892f7f0f25257540559c9a4fd2134fd
backend dist/index.js 4ccffa2c7a1dfa8cae4806605156771faec8a705e55863d2f22faab28804f7c9
```

维护脚本在 `PSMS/scripts/deploy-psms.py`、`deploy-publish.sh` 和 `deploy-db-check.mjs`。
它们记录本次固定发布路径，重新发布需使用新的 release 标识；不要直接重复已切换阶段。
本机 `.env.deploy`、`.env.ai`、`.deploy/` 被 Git 忽略。
