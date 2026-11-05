# 设备公共接入

独立 Java 17 服务，接收 `b.device-event.v1` 模拟事件，完成持久接收、RocketMQ 提交、点值落库、设备范围授权、资产读取和 history 分页。Python 读取工具仅使用标准库。本目录不包含模拟器、设备协议采集器或业务系统消费者。

它与 TRUST 存证后端分别构建、分别运行。设备事件不会因进入本服务而自动写入 Fabric/IPFS；HTTP 202、公共 COMMITTED、业务系统事务完成是不同状态。

## 构建和本地验证

在本目录使用 Java 17、Maven 和 Python 3.9+：

```sh
mvn -B package
python -B -m unittest discover -s . -p test_history_read.py -v
python -B tests/run_security_tests.py --jdk /path/to/jdk17 --output .local/tests/fresh --download
python -B tests/run_security_tests.py --jdk /path/to/jdk17 --output .local/tests/reuse --cache .local/tests/fresh/test-libs --reuse
```

`package` 编译服务，输出 `target/device-ingress-0.1.0.jar` 及 `target/lib/`。HTTP/H2 测试源码由上面的 Python 入口编译并执行；Maven 打包本身不执行这些 main 方法测试。测试启动回环 HTTP/H2，不需要项目服务器、业务库或 Broker。测试令牌均为人工夹具。

范围授权的拒绝日志要求文件系统提供稳定的 `fileKey`。无法提供时启动拒绝；Windows/JDK 的 HTTP 测试使用显式测试注入，不代表生产启动条件通过。

## 配置和启动

将 `config/` 模板复制到私密目录，填写连接、独立技术账号及授权设备。模板中的 token 摘要占位符不可启动；令牌原文仅保存在调用方私密文件。策略和主体名称不能替代真实归属或服务器授权。

运行环境读取 `DEVICE_*` 变量，程序不会自动读取 env 文件。`config/receiver.env.example` 列出基础配置；由运行方的进程管理器注入后，使用：

```sh
java -jar target/device-ingress-0.1.0.jar --check-db
java -jar target/device-ingress-0.1.0.jar
```

`--check-db` 只检查数据库。新隔离数据库需由运行方预建 `bpoc` schema 并确认权限，再显式使用 `--init-schema`；旧库的 `--migrate-history` 为独立变更，不能因源码发布而自动执行。`scoped` 启动会创建/检查范围登记表；既有事件不会自动认领给新设备或新主体。

仅接收 `simulated=true` 和 `SIM-` 设备号；topic 和 producer/consumer group 固定为模拟接入用途。一个公共链路只运行其既定接收实例，不能用启动第二个同组 consumer 代替发布切换。停止服务保留数据库和待提交记录；重新运行会继续处理待提交记录。

默认回环绑定；外部调用经运行方批准的代理或隧道。`scoped` 按策略授予设备读写权限，并要求有界拒绝日志；日志不可用时写入关闭。`legacy` 仅兼容原共享令牌，不提供主体或设备隔离。资产访问默认关闭，只有明确设置 `DEVICE_SCOPED_ASSETS=enabled` 才按设备授权读写。连接复用和汇总耗时记录分别由 `DEVICE_JDBC_REUSE`、`DEVICE_TIMING` 显式开启。

## 读取与契约

接口、原文重试和游标约束见 [公共接口契约](contract.md)。凭据来自私密 token 文件，不通过 URL 传递。

```sh
python -B client_read.py --config config/reader.example.json --event-id EVENT_ID
python -B history_read.py --config config/reader.example.json --access-mode scoped --principal-id READER_ID --policy-revision POLICY_VERSION --device-id SIM-EXAMPLE --consumer-id READER_APP --journal-dir .local/history
```

history 工具按入口、设备、主体、策略版本和消费者隔离本地 SQLite 日志；只有验证公共 COMMITTED 和事件身份后才提交游标。业务方仍需在自己的事务内实现 inbox 与业务游标，不应把本地读取日志当作业务验收。
