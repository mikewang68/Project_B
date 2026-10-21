# 日志服务等待阶段部署与验收

本轮服务AUDIT 1.1.0、SDK 1.0.1、EHM 1.7.3-server。该文档和脚本是部署准备，不表示已经获得实验室资源或已经完成服务器部署。继续使用openGauss/openGemini，H2仅供本地隔离测试。

## 先做哪些无需外部权限的事

在AUDIT目录执行mvn install，再在EHMS/source/backend执行mvn package。新增EHM sql/003_audit_tasks.sql应随002_audit_outbox.sql一并审阅；已有库只运行尚未应用的迁移，不清库、不覆盖角色权限。

EHM新增真实任务记录覆盖健康评估、历史回放和后端证据导出。任务开始及终态与发件箱各自同事务保存；它不是业务事务的替代。若业务已提交而终态写入失败，响应标注RECONCILIATION_REQUIRED，不伪装成业务回滚。进程异常或截止时间到达后记录UNKNOWN和结果未知事件，不自动重做业务，不把未知当成成功/失败。它不是备份、恢复或训练执行器。

审计证据导出改为后端有界快照。默认关闭；启用需可信IAM用户ID专项allowlist。新任务列表只给已验证用户看其本人在最近30个全局任务范围内的记录，获专项导出授权者可查看该范围；未验证身份不返回任务身份。此门禁不替代整个EHM的业务RBAC。

## 私有配置

EHM沿用原变量，新增：

```text
EHM_AUDIT_TASK_MAX_SECONDS=900
EHM_AUDIT_EVIDENCE_EXPORT_ENABLED=false
EHM_AUDIT_EVIDENCE_EXPORT_ACTORS=
```

IAM契约确认后再设EHM_AUDIT_IAM_REQUIRED=true。获准导出时才启用开关并填写稳定用户ID；浏览器角色名、URL参数或伪造请求头不能授权。未配置时界面显示未授权，不提供前端伪造的成功文件作为后备。

公共服务继续AUDIT_CHAIN_ENABLED=false。不要为了让页面变绿自行打开来源enabled/archiveApproved，或借用WMS/身份接口Token。完整JSON非加密归档许可须另行取得。

## 静态预检

在解压后的工程根目录运行：

```sh
python3 AUDIT/scripts/preflight-config.py --audit-env /private/audit.env --ehm-env /private/ehm-server.env --report /private/preflight-result.json
```

脚本不source配置，不连接网络、不建库、不部署；检查必需变量、占位值、自动DDL/上链开关、作用域/本地写凭据对应及构建包SDK。Linux检查文件权限，Windows ACL需人工确认。报告不输出密钥/原值。缺配置返回2；静态通过不等于真实资源验证通过。传入文件应为可信配置原件；不会自动展开shell变量。

## 经批准后的实际部署顺序

1. 资源负责人确认AUDIT节点/目录/端口/内网代理、openGauss库/schema、独立迁移与运行账号；不复用数据库超级用户。
2. DBA在相应schema审阅并执行公共schema和所需迁移。新EHM的audit_task只授SELECT/INSERT及状态、结束时间、租约、错误码列UPDATE；不授原事件改写和DELETE。运行auto-DDL关闭。
3. 我方在约定scope下生成AUDIT业务凭据，私下交付。雷文完成IAM/SYS合并并给实际身份契约。真实私有配置不随包覆盖。
4. 公共服务可用JAVA_BIN指向Java17，AUDIT_ENV_FILE指向已批准私有环境文件，以scripts/start-server.sh前台启动；交给实验室批准的服务管理方式托管。默认绑定127.0.0.1，跨节点访问需获准代理或内网接口。不要以root或临时公开端口绕网络问题。
5. EHM按原bin/start-ehm.sh启动，确认003已应用；更新web文件及缓存版本。旧包保留用于代码回退，新迁移是增表，不通过删表回退。
6. 首先验收EHM→AUDIT真实日志入库、可信身份及拒绝；合成数据跑CRUD和导出/任务验证。取得来源、内容和链资源批准后，另开窗口验收AUDIT→TRUST，不在本阶段宣称已真实上链。

## 本地双进程验证

先构建服务，再运行：

```sh
python3 AUDIT/scripts/local-audit-e2e.py --java /path/to/java17/bin/java --h2-jar /path/to/h2-2.2.224.jar
```

该脚本仅绑定127.0.0.1，创建新的AUDIT/.local私有目录、合成服务身份和H2文件，启动两个真实Java进程及本地IAM桩，验证真实HTTP业务与日志、断服务补传、重启、幂等/409、跨域拒绝、实际后端导出与任务终态。结束会停止仅由它启动的子进程，不操作既有服务。无真实openGauss/openGemini、SSO、TRUST、IPFS或Fabric访问。报告只外发sanitized-report.json；目录中的连接、日志、数据库和Token不外发。

## 验收入口

- EHM GET /api/ehm/v1/system/audit-delivery：本地队列与最近20条事件，ACCEPTED不是上链。
- EHM GET /api/ehm/v1/system/audit-tasks：本人/专项授权的有界任务记录和导出门禁状态。
- EHM GET /api/ehm/v1/system/audit-evidence/export：专项授权的服务端JSON字节；附task ID、task state与文件SHA256头，默认拒绝。
- 公共API仍为/api/logs/v1；TRUST最终回执由公共服务读取，不把首次投递缓存当最新链状态。

前端在“操作日志”展示任务，在“审计证据包”使用后端导出；健康评估/回放依赖实际有效数据，缺数据应显示失败和真实失败日志，不能为演示制造成功。告警闭环请求已改为复用统一apiRequest，避免漏传IAM会话导致身份匿名或401。

## 仍不能单方完成的事项

真实资源和迁移批准、IAM/SYS合并与身份权限、其他业务模块实际采集、日志内容非加密归档许可、TRUST来源/凭据/链资源，以及真实端到端回执。全系统业务数据快照上链属于另一契约，不因操作日志通过而完成。新功能不冒充完整备份恢复、动态菜单或全面RBAC。
