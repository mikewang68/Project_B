# 公共日志SDK/API接入基线

公共API仍为/api/logs/v1，schemaVersion=1；Java17依赖com.bproject.audit:audit-client:1.0.1，公共服务1.1.0。先mvn install公共工程，业务方再构建，不需要TRUST源码或机器凭据。

## 事件和权限

LogEvent有18个字段：schemaVersion、eventId、occurredAt、appCode、domainId、zoneId、userId、actorTrust、menuId、action、objectId、outcome、method、path、traceId、durationMs、sourceKind、summary。长度和格式以LogEvent.java及docs/openapi.json为准。

- actorTrust：AUTHENTICATED、ANONYMOUS、SYSTEM；SYSTEM是服务身份，不证明人类签名。
- outcome：SUCCESS、FAILED、DENIED、STARTED。STARTED不是完成，异步任务另记终态。
- sourceKind：BUSINESS、REQUEST、POLL、JOB、CLIENT；这里没有SYSTEM。
- action：CREATE、READ、UPDATE、DELETE、LOGIN、LOGOUT、EXPORT、IMPORT、UPLOAD、DOWNLOAD、BACKUP、RESTORE、TRAIN、EXECUTE、NAVIGATE。
- 已知来源编码ehealth、iam、sys；其他模块依统一注册清单，不凭目录缩写改码。

## 事务和身份

同一DataSource／事务管理器中，业务变更与JdbcLogOutbox.enqueue一起提交。控制器AOP不自动跨独立数据源、REQUIRES_NEW或异步任务，模块负责人应验证真实调用链。失败／拒绝／查询日志按实际边界独立持久保存，不以按钮点击或HTTP方法证明成功。

用户来自可信会话，未认证记anonymous；输入的尝试登录账号和浏览器用户名头不是可信身份。不记录请求正文、查询条件、密码、Token、模型Key或对话正文。SDK是有限校验，不是自动剥离所有个人信息的脱敏引擎。

JobAudit可辅助记录任务STARTED和终态，但不等于已实现备份／恢复执行器。异常退出后的终态需按jobId核对。

## LegacyLogMapper 1.0.1变化

denied／forbidden／unauthorized／401／403映射DENIED；http_status或status_code的401／403优先DENIED，4xx／5xx为FAILED。fail／failed／failure／error为FAILED；started保留STARTED；success／succeeded／successful／ok及明确2xx／3xx为SUCCESS。未知或缺失结果抛异常，来源方必须明确映射，不再默认成功。动作大小写使用Locale.ROOT。

旧版已误投的同号记录不能直接改内容重发，否则409。保留原记录，先制定更正清单与方案，不换号绕冲突。未知结果将阻止对应旧记录转换，需来源方提供真实取值字典。

## 回执和接入范围

持久队列由LogDispatcher调度；仅HTTP成功、stored=true且eventId和payloadHash均匹配才记ACCEPTED。同号同内容幂等，同号异文409，作用域／身份错误401／403，429／5xx有界补传。ACCEPTED只代表AUDIT入库，TRUST状态另查。

每个应用及domain/zone持独立最小权限凭据，WRITE／READ／EXPORT／RETRY分离。浏览器不领取服务写Token；SYS代理按可信用户选择授权作用域，不让超级管理员自动突破边界。现有初始化脚本是联调工具，不是最终权限批准。

公共CSV仍仅导出授权范围最近100条，不是全量备份。writelog如有老师指定表名／DDL，先统一确认再迁移，不在各模块重复建中央日志库。
