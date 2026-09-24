# EHM 1.3.0 服务器版部署说明（openGauss + openGemini）

> 本仓库为公开脱敏示例。`192.0.2.74`、`node4.example.internal`、
> `node6.example.internal` 和 `/opt/b-project/ehm` 均为示例值，部署前必须按现场环境替换。

适用环境：openEuler 24.03、BiSheng/OpenJDK 17.0.19、Maven 3.9.16、
openGauss 6.0.5、openGemini 1.5.2、Nginx 1.24。公开仓库不提交预编译JAR，
服务器无需Docker；请使用附带后端源码在应用节点构建。

## 1. 部署形态

- 应用节点（示例 `node4.example.internal`）：Java 17后端，默认端口18083；业务聚合存入openGauss。
- Web/时序节点（示例 `node6.example.internal`）：Nginx发布前端；设备测点时序写入openGemini 8086。
- 示例访问地址为 http://192.0.2.74:18090/ehm/ ，其中 `192.0.2.0/24` 是文档专用保留地址。
- 当前服务器基线不依赖Docker；MongoDB方案已退出服务器交付和后续开发基线。

openGauss保存设备台账、BOM、告警、健康评估、工单、点检、备件、规则、知识库和审计等事务数据。
openGemini保存温度、振动、电流、压力、载荷等测点采样以及后续健康趋势和模型时间线。

## 2. 部署前必须取得的参数

向环境负责人取得openGauss的数据库、用户名、密码及账号权限，并确认应用节点到时序节点的8086端口可达。
真实密码只填写在服务器的 config/ehm-server.env，不得写入源码、压缩包说明或聊天记录。

## 3. 初始化

1. DBA在EHM业务库执行 `sql/001_init_opengauss.sql`。
2. 给EHM应用账号授予ehm schema的USAGE权限，以及aggregate_store的SELECT、INSERT、UPDATE权限。
3. 在时序节点执行 `bin/init-opengemini.sh`，创建ehm_telemetry。
4. 从 `config/ehm-server.env.example` 复制出 `config/ehm-server.env` 并填写真实参数。

## 4. 构建与目录

在应用节点执行以下命令生成 `app/ehm-service.jar`：

    cd source/backend
    mvn clean package
    mkdir -p /opt/b-project/ehm/{app,config,logs,run,web,bin}
    cp target/ehm-demo-0.1.0.jar /opt/b-project/ehm/app/ehm-service.jar

把交付包中的web目录内容发布到Web节点的 `/srv/b-project/ehm/web/`。

脚本默认从PATH执行java；若服务器Java不在PATH，在config/ehm-server.env中增加
JAVA_BIN=/实际路径/bin/java。systemd模板中的用户和安装路径也必须按现场账号修改。

如openGauss运行在与应用同机的iSula容器中，且容器IP可能随重建变化，
可将`EHM_OPENGAUSS_HOST` 设为 `isula-auto`。`start-ehm.sh`会从当前gaussdb容器网络命名空间
发现实际私网地址；外部数据库或固定服务名场景仍直接填写主机名或IP。

## 5. 启停

普通账号联调：

    chmod +x /opt/b-project/ehm/bin/*.sh
    /opt/b-project/ehm/bin/start-ehm.sh
    /opt/b-project/ehm/bin/check-ehm.sh

管理员可以采用 `systemd/ehm-service.service` 注册systemd服务。Nginx只把
`nginx/ehm-location.conf` 中的location片段合并到现有统一入口18090的server块，
必须先执行 `nginx -t`；不能覆盖其他站点或整份 `/etc/nginx/conf.d/b-project-unified.conf`。

## 6. 验收

    curl -fsS http://127.0.0.1:18083/health/live
    curl -fsS http://127.0.0.1:18083/health/ready
    curl -fsS http://127.0.0.1:18083/api/ehm/v1/system/status
    chmod +x bin/*.sh
    ./bin/smoke-crud.sh

就绪结果必须同时显示openGauss与openGemini为UP。浏览器进入系统运行页时应显示：

- 业务存储：openGauss
- 时序存储：openGemini
- 联合就绪：openGauss+openGemini

1.3.0还提供4套主题、3种布局、实时告警批量分派，并将分派结果持久化到openGauss。
服务器运行能力页会根据实际适配器显示openGauss、openGemini与MongoDB状态。

`smoke-crud.sh` 会建立一组带时间戳的临时设备、部件和测点，验证新增、查询、
编辑、时序样本写入、质量判定和归档，结束后自动清理。出现 `CRUD_SMOKE_OK`
才表示基础业务链路通过。

## 7. 当前边界

服务器版已经完成数据库适配和部署形态切换，但上线前仍需完成统一IAM、Easegress正式路由、TLS证书、
RocketMQ事件总线、现场数据接口、备份恢复策略和容量压测。上述未完成项不影响本阶段三节点联调。
