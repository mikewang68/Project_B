# B项目设备健康管理系统（EHM）全栈Demo

## 1. 当前实现范围

本Demo优先打通以下业务闭环：

- 综合驾驶舱：从MongoDB实时汇总设备、健康、告警和工单指标。
- 设备台账：查询、新建设备、查看设备360°详情；删除操作采用归档而非物理删除。
- 告警中心：查询活动告警、人工确认和关闭告警。
- 维保工单：查询看板、新建工单并推进工单状态。
- 智能运维辅助：未配置AI API时使用可审计规则回答；配置兼容接口后由Java后端代发请求，API密钥不进入浏览器。

其余菜单继续保留原型展示，后续可逐个接入API。

当前版本属于“可联调Demo”，不是生产系统。登录鉴权、RBAC强制校验、审批签名、实时设备采集、消息队列、生产级时序存储和模型训练尚未接入。

## 2. 技术栈

- 前端：现有亮色HTML/CSS/JavaScript原型，Nginx 1.24发布。
- 后端：Java 17、Spring Boot、Maven。
- Demo主库：MongoDB 7.0。
- 已预留：openGauss、openGemini、Kvrocks、RocketMQ、Easegress、Nightingale/Categraf。

MongoDB用于快速承载设备台账、告警、工单和扩展字段。生产阶段是否拆分到openGauss和openGemini，可在数据模型与接口稳定后再决定。

## 3. 一键启动

电脑需已启动Docker Desktop。在本目录执行：

```powershell
Copy-Item .env.example .env
docker compose up -d --build
```

启动后访问：

- 系统页面：http://localhost:8088
- 后端就绪检查：http://localhost:8080/health/ready
- 后端接口状态：http://localhost:8080/api/ehm/v1/system/status

首次构建需要下载MongoDB、Nginx、Maven和Java镜像，耗时取决于网络。

可选的接口自检（会确认一条演示告警，并创建一次可重复使用的联调工单）：

```powershell
pwsh -NoProfile -File .\scripts\verify-demo.ps1
```

## 4. 主要接口

| 方法 | 地址 | 用途 |
|---|---|---|
| GET | `/api/ehm/v1/dashboard/summary` | 驾驶舱汇总 |
| GET/POST | `/api/ehm/v1/devices` | 查询或新建设备 |
| GET/PUT/DELETE | `/api/ehm/v1/devices/{code}` | 设备详情、修改、归档 |
| GET | `/api/ehm/v1/alarms` | 告警列表 |
| POST | `/api/ehm/v1/alarms/{alarmNo}/acknowledge` | 人工确认告警 |
| POST | `/api/ehm/v1/alarms/{alarmNo}/close` | 关闭告警 |
| GET/POST | `/api/ehm/v1/work-orders` | 工单列表或新建工单 |
| PATCH | `/api/ehm/v1/work-orders/{orderNo}/status` | 推进工单状态 |
| POST | `/api/ehm/v1/assistant/chat` | 智能运维问答 |

## 5. AI API配置

仅支持兼容OpenAI Chat Completions格式的服务。编辑`.env`：

```text
EHM_AI_ENABLED=true
EHM_AI_BASE_URL=https://你的服务地址/v1
EHM_AI_API_KEY=你的密钥
EHM_AI_MODEL=模型名称
```

修改后执行：

```powershell
docker compose up -d --build backend
```

不要把真实API密钥写入`.config.json`、`app.js`或提交到Git仓库。

## 6. 实验室统一环境部署建议

- node4：MongoDB容器、后端Java服务；后续可接openGauss和Kvrocks。
- node5：RocketMQ与后台Worker；Demo阶段可不启用。
- node6：Nginx前端、openGemini、Nightingale；Demo阶段前端只调用node4后端。
- Easegress统一暴露六套系统API，建议给EHM分配固定前缀`/api/ehm`。

六套系统统一时，至少统一以下内容：Java 17、接口前缀、配置文件位置、健康检查、日志目录、端口登记和容器命名规范。

## 7. 停止与清理

停止服务但保留数据：

```powershell
docker compose down
```

如需删除Demo数据库卷，必须明确执行`docker compose down -v`。该操作不可恢复，不应在保存有效测试数据后使用。
