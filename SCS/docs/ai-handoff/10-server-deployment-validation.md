# 10 - SCS 首次真实服务器部署与实机验收报告

环境事实归档：记录 Project_B/SCS 后端在 bpoc-node4 与前端在 bpoc-node6 的首次真实服务器部署配置、初始化结果与全链路验收指标。所有生产密码以 <SECRET> 脱敏处理。

---

## 一、服务器拓扑与部署路径

| 节点 | 内网 IP | 职责组件 | 部署根路径 | 监听端口 / URL |
|---|---|---|---|---|
| bpoc-node4 | 192.168.101.57 | SCS 后端 (Spring Boot)<br>openGauss 6.0.5 (bpoc-opengauss)<br>Kvrocks 2.16.0 (bpoc-kvrocks) | /home/jingchanglong/Project_B/SCS | 后端: 18080<br>openGauss: 5432<br>Kvrocks: 6666 |
| bpoc-node6 | 192.168.101.74 | SCS 前端 (Vue 3)<br>Nginx 1.24 反向代理 (首次独立验证) | 源码: /home/jingchanglong/Project_B/SCS<br>Nginx Root: /var/www/scs | 前端: http://192.168.101.74:18088<br>Nginx: 18088 (避免冲突 80) |
| bpoc-node5 | 192.168.101.73 | RocketMQ 5.5 / Easegress / Worker | — | 保持现状，本轮不修改 |

---

## 二、部署包物料与内容审计

| 包名 | 大小 | 文件总数 | 审计规则核验 (node_modules / target / .tools / .git / dist) |
|---|---|---|---|
| SCS-backend-deploy.tar.gz | 280 KB | 342 | PASS（0 违规目录，纯净源码与配置） |
| SCS-frontend-deploy.tar.gz | 271 KB | 265 | PASS（0 违规目录，含锁文件与配置） |

---

## 三、基础设施与数据库初始化结果

### 1. openGauss 6.0.5 初始化
* 容器名称：bpoc-opengauss（端口 5432）
* 数据库名：b_project
* Schema 名：safety
* 应用用户：safety_admin
* 密码：<SECRET>
* DDL 来源：backend/src/main/resources/db/design/openGauss-schema-final-draft.sql
* 对象数量核验：
  - 活动表（BASE TABLE）：25 / 25 PASS
  - 物理序列（SEQUENCE）：21 / 21 PASS
  - 授权与 search_path：GRANT ALL ON ALL TABLES/SEQUENCES IN SCHEMA safety TO safety_admin;，ALTER USER safety_admin SET search_path TO safety, public;
* 隔离实例保护：127.0.0.1:25432 / 25433（b-project-trust）保持未触碰。

### 2. Kvrocks 2.16.0 接入
* 容器名称：bpoc-kvrocks（端口 6666）
* 认证状态：AUTH <SECRET> -> PASS
* 心跳探测：PING -> PONG PASS

---

## 四、后端构建、Spike 测试与 Profile 运行

### 1. Maven 构建
* 命令：mvn clean verify
* 结果：BUILD SUCCESS（211 测试运行，0 失败，0 错误，严禁跳过测试）

### 2. 实机 openGauss Spike 兼容性测试套件
* 触发条件：RUN_OPENGAUSS_SPIKE=true mvn test -Dtest='OpenGauss*Test'
* 测试用例结果：
  - OpenGaussConnectionSpikeTest: PASS（数据库版本、时区、25 表、21 序列系统目录核验）
  - OpenGaussJsonbSpikeTest: PASS（PGobject 方案 A 与 Types.OTHER 方案 B）
  - OpenGaussTimestampSpikeTest: PASS（TIMESTAMPTZ 读写与 OffsetDateTime 毫秒级零漂移）
  - OpenGaussSequenceSpikeTest: PASS（DEFAULT nextval 自增、RETURNING id、UNIQUE 违反 23505、NOT NULL 违反 23502、FK 违反 23503）

### 3. Spring Boot 后端实机运行
* Profile：server（加载 /home/jingchanglong/Project_B/SCS/runtime/backend.env）
* 运行端口：18080
* HikariCP 连接池：PASS（连接 jdbc:postgresql://127.0.0.1:5432/b_project?currentSchema=safety）
* DatabaseProbe：UP（SELECT 1 心跳）
* KvrocksProbe：UP（Lettuce Redis PING 心跳）
* Readiness 端点：GET http://127.0.0.1:18080/health/ready -> HTTP 200 UP

---

## 五、前端现场构建与 Nginx 路由 (独立 18088 端口)

### 1. 前端现场构建
* 安装依赖：pnpm install --frozen-lockfile -> PASS
* 类型检查与单元测试：pnpm --filter b-project-safety-gate-web test -> PASS（29 个测试套件全绿）
* 生产打包：pnpm --filter b-project-safety-gate-web build -> 现场生成 frontend/dist PASS

### 2. Nginx 配置与反向代理 (独立 18088 端口)
* 站点配置文件：/etc/nginx/conf.d/b-project-safety-gate.conf
* 独立端口：listen 18088;（完全不触碰现有 80 端口服务）
* 静态资源路径：/var/www/scs
* API 反代路由：/api/ -> http://192.168.101.57:18080
* 健康检查反代：/health/ -> http://192.168.101.57:18080
* WebSocket 反代：/ws/ -> http://192.168.101.57:18080（Upgrade: upgrade, Connection: "upgrade"）
* 配置校验：sudo nginx -t -> syntax is ok, test is successful

---

## 六、端到端全链路验收

| 验收维度 | 测试目标 | 预期响应 | 实测结果 |
|---|---|---|---|
| 前端静态 | GET http://192.168.101.74:18088/ | HTTP 200 HTML | PASS |
| 前端健康 | GET http://192.168.101.74:18088/health/frontend | HTTP 200 {"status":"UP"} | PASS |
| 后端探针 | GET http://192.168.101.74:18088/health/ready | HTTP 200 {"status":"UP"} | PASS |
| 业务字典 | GET http://192.168.101.74:18088/api/v1/meta/dictionaries | HTTP 200 JSON | PASS |
| 态势总览 | GET http://192.168.101.74:18088/api/v1/overview/summary | HTTP 200 JSON | PASS |
| 实时连接 | ws://192.168.101.74:18088/ws/live | WebSocket 101 Switching Protocols + Ping/Pong | PASS |
| 路由刷新 | 访问 /overview, /fences, /devices 刷新 | HTTP 200 (try_files 机制生效，无 404) | PASS |

---

## 七、业务仓储状态事实说明（特别强调）

当前 Step 0 完成后，数据库基础设施真实可用，Spring Boot 已建立真实 HikariCP 池与 Kvrocks 缓存通道。
但系统内部的 9 个业务仓储（Repository）当前依然为 InMemoryRepository 实现：
1. InMemoryAlertRepository
2. InMemoryAiEventRepository
3. InMemoryCollisionRepository
4. InMemoryFenceRepository
5. InMemoryPersonnelRepository
6. InMemoryRuleRepository
7. InMemoryEdgeNodeRepository
8. InMemoryEdgeEventQueueRepository
9. InMemoryOpsEventLogRepository

当前数据库业务表仓储使用数：0 / 9。
页面展示的业务数据暂时来自于 Demo 内存种子；将 9 个仓储逐一重构为 JDBC 并将数据持久化落盘至 safety schema 属于 Phase 1 / Step 1+ 的后续任务。

---

## 八、Phase 1 / Step 0 最终验收判定

```text
=============================================================================
SCS Initial Server Deployment (Phase 1 / Step 0)
RESULT: PASS
Ready for Phase 1 Step 1: YES
=============================================================================
```
