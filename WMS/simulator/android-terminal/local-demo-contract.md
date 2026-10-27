# 联调工具0.2.1本地HTTP演示契约

作用域固定`local-demo-only`，不是董博涵公共底座，也不是WMS业务提交。APK保持0.2.0；无需为本次工具更新安装新手机程序。

## 完整运行与输出

先在Android Emulator安装终端，保留空队列、默认标签、停止设备服务；运行README中的`run_local_demo.py`。工具验证目标是模拟器，读取当前称重／工艺设置，启动独立存储进程和独立网关采集进程，再启动独立客户端读取原始outbox提交并HTTP查询逐条比较。输出目录必须不存在；失败目录不删除、不复用，以保留不确定状态。

| 文件 | 说明 |
|---|---|
| summary.json | 实际写入和读回条数、去重与冲突检查、源码／APK摘要、清理结果与local-only范围 |
| manifest.json | 模拟器、原参数、临时端口、原始队列状态及结束状态；结束后临时地址不再可用 |
| gateway/设备/gateway.sqlite3 | 网关首次原文字节、SHA256、原始报文和原事件ID；状态仍为CAPTURED |
| gateway/设备/events.jsonl | 逐点值、质量、原点号、模拟标志和时间来源 |
| http-query/设备/http-readback.json | 独立HTTP客户端读回的原事件和原文字节base64 |
| http-query/设备/local-receipts.json | 本地存储回执，不能作为公共底座COMMITTED |
| local-events.sqlite3 | 独立本地演示库，应用标识防止误当网关／WMS库打开 |
| business-readback.json | 本地示例调用方读取的事件，名称沿用门吊示例；不是服务器WMS记录 |
| fault-proxy.json | 异常场景中真实C原帧、代理修改／扣留行为和关联event_id |

正常演示逐设备读取，共5事件／186行；故障演示只采汽车衡。bad-checksum与timeout是传输代理明确注入的故障，C模型原始帧另留，不宣称已经实现所有厂家故障。不会控制实体设备、打开个人手机相机、自动扫码个人手机或调用收货API。

## 单独启动与查询

下面是本地SQLite/HTTP存储的独立启动命令，适合查看已保存的演示数据。manifest使用新文件名；db可以重新打开本工具创建的数据库，拒绝其他SQLite库。选择空闲端口，不停止其他服务抢占。

```powershell
# 窗口1，Ctrl+C停止。端口示例18781，按本机空闲情况调整：
python local_demo_store.py --db runtime/local-normal-1/local-events.sqlite3 --port 18781 --manifest runtime/readback-session-1.json

# 窗口2，按summary中的实际event_id查询：
$id = Read-Host '输入要核对的event_id'
$encoded = [uri]::EscapeDataString($id)
Invoke-RestMethod "http://127.0.0.1:18781/api/local-demo/v1/events?event_id=$encoded"
Invoke-RestMethod 'http://127.0.0.1:18781/api/local-demo/v1/latest?device_id=SIM-PDA-01'
Invoke-RestMethod 'http://127.0.0.1:18781/api/local-demo/v1/health'
```

要提交已经由参考网关采集的全新本地outbox，可单独运行：

```powershell
python local_demo_client.py --outbox runtime/local-normal-1/gateway/handheld --store-url http://127.0.0.1:18781 --output runtime/manual-http-query-1
```

输出目录必须新建，outbox必须保持CAPTURED，不能用已经向公共底座提交的运行状态做演示。该客户端只读网关库，不把LOCAL_COMMITTED写回COMMITTED，也不改原事件ID或原文。

## HTTP接口

| 接口 | 行为 |
|---|---|
| POST /api/local-demo/v1/events | 原始UTF-8 JSON字节，Content-Type application/json，1～256KiB；验证模拟事件、身份、点位、单位、质量和来源；SQLite事务提交后返回200 |
| 同ID、相同首次字节 | 200，duplicate=true，事件数不增 |
| 同ID、不同字节 | 409，原记录不覆盖；本例按原文字节判定，不接受重新排版再重试 |
| GET /api/local-demo/v1/events?event_id=... | 原事件、原文字节base64、SHA256、接收时间；不存在404 |
| GET /api/local-demo/v1/latest?device_id=... | 指定设备最后插入的一条；不声称逐点源时刻最新 |
| GET /api/local-demo/v1/health | scope、事件数，以及public_base=false／WMS=false |

scope始终为local-demo-only，状态为LOCAL_COMMITTED，收据local_committed=true；不返回broker_message_id。输入仍为b.device-event.v1，以便比较原始事件内容，但不会冒充`/api/device-data/v1`路由或公共底座的COMMITTED证据。WMS桥接应拒绝直接使用这些回执。

SHA256针对首次POST的精确字节；独立读回客户端核对原文字节、SHA256、event_id和解析后的原对象。源时间保持原有null，接收时间另列；不修改观测时间来掩盖补传。不接受NaN/Infinity、重复JSON键、重复点号、非模拟事件或未知原点号。服务仅绑定回环地址，拒绝浏览器Origin及不符的Host，无跨域开放接口。

## 与正式验收的关系

本地PASS说明设备读取、采集原文、本地持久保存与HTTP查询链路可运行，并验证去重、冲突和异常数据保留。公共底座、RocketMQ/openGauss、服务器WMS页面／业务数据库、实体手机光学扫码及现场工艺点表仍分别验收。三类原厂点表和原厂扫码有限NAK／超时行为的缺项没有因本例通过而关闭。
