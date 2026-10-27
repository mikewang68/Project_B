# Android C 设备模拟终端

## GitHub 源码与安装包

本项目位于仓库 `WMS/simulator/android-terminal`，版本为0.5.0。
直接下载安装：[WMS-Android-C-Terminal-0.5.0.apk](releases/0.5.0/WMS-Android-C-Terminal-0.5.0.apk)，
对应[构建信息](releases/0.5.0/apk-build-0.5.0.json)和[SHA256](releases/0.5.0/SHA256SUMS.txt)。
它是此前已验证的实验安装包，不是本次新编译的APK；最低Android8.0。

这是独立Android设备协议模拟工具，不是WMS业务管理客户端。它可模拟扫码、称重和工艺仪表，
**扫码本身不会直接增加服务器库存**；不包含已完成的统一登录、手写签名及真实业务落库验收。
公开源码不含私密签名密钥、业务账号、底座令牌、运行队列或本地配置。
重新编译会使用开发者自己的本地签名密钥；不同密钥不能直接覆盖升级原签名安装包。
不要为解决签名冲突直接卸载保有未发送队列的应用，应先按受控方案备份和处理数据。

当前源码的协议客户端单测可在电脑上运行，不需要连接手机、实验室服务器或业务数据库：

```powershell
cd WMS/simulator/android-terminal
python -B -m unittest discover -s tests -v
```

## 0.5.0：独立有限握手参考

扫码页默认业务持久队列保留；可选择独立19065参考事务，ACK完成、NAK有限发送、超时不重试丢弃。总发送3次含首发是明确参考解释，准确原厂计数仍待核。见[握手模式与边界](scanner-reference-profile.md)。新增页仅在文字变化时刷新状态，不改现有队列或110点。

## 0.4.0：VEGAPULS 6X雷达独立参考

仪表页可选EE600差压或VEGAPULS 6X雷达。新增19064／站246的只读RTU字节通道，明确状态、单位与PV字序，只有显式距离语义和空满标定才计算百分比；无效PV为null/BAD，正常仍UNCERTAIN，不计算吨数。原110点及扫码ABI不改。见[雷达配置与边界](vega-profile.md)。

## 0.3.0：EE600独立原厂参考配置

新增“仪表”页及19063的EE600只读RTU字节通道，站号43，使用原厂测量地址和CDAB字序；原有五通道、实验点表和扫码持久文件格式保留。EE600设置独立保存，缺标定时不开放风量；严重错误保留NaN／0x8000并解码为null/BAD，状态地址矛盾仍禁用。

安装WMS-Android-C-Terminal-0.5.0.apk后使用`ee600_client.py`读取。完整地址、参数、边界及命令见[EE600配置说明](ee600-profile.md)。原110点计数和GOOD范围不提升，EE600输出单列ee600.reference.v1，不直接提交公共底座或WMS。

## 一键本地HTTP闭环（0.2.1起提供）

该工具在0.2.1版本加入，原有验证使用APK0.2.0；0.5.0继续保留该入口。本轮未重复生成扫码或重跑旧HTTP场景。链路为：**Android模拟器内真实C模型 → 独立gateway.py进程读取 → 本地SQLite/HTTP服务 → 独立客户端逐条查询并核对首次原文字节、摘要与事件ID**。

先安装APK并启动Android模拟器，应用里保留空扫码队列、停止设备服务；选择默认SIM-STEEL-B001标签。不要对个人手机运行自动演示。命令在本源码目录执行，每次使用全新输出目录：

```powershell
python run_local_demo.py --serial emulator-5554 --scenario normal --output runtime/local-normal-1
python run_local_demo.py --serial emulator-5554 --scenario bad-checksum --output runtime/local-bad-checksum-1
python run_local_demo.py --serial emulator-5554 --scenario timeout --output runtime/local-timeout-1
```

`--adb`可指定ADB完整路径，否则使用ANDROID_HOME/platform-tools或PATH。工具先检查ro.kernel.qemu，再进行界面操作；遇到实体手机、已有待发扫码或运行中的服务即拒绝，不清队列、不卸载、不恢复出厂。正常演示仅产生一条专用模拟器扫码并在持久保存后ACK，其余四类读取当前C模型参数，不修改称重和工艺设置。手机摄像头扫码仍由用户自行操作。

正常演示保存5个事件，共186行（S2 110＋S1关联76），分别按event_id逐条HTTP读回，并验证重复提交不增行、同ID异字节409且原事件未覆盖。bad-checksum和timeout只针对汽车衡：先读取真实C称重帧，再由明确标记的本地传输代理损坏校验位或扣留响应，不能将其描述为原厂固件主动故障。坏帧原文和超时证据保留，输出null/BAD；这些场景的PASS表示异常处理正确。

输出包括summary.json、manifest.json、各设备gateway.sqlite3/events.jsonl、独立http-query读回、local-events.sqlite3、business-readback.json及异常代理证据。结束后停止本轮服务并核对Windows临时端口关闭；不删除生成的记录。失败时保留目录和未ACK队列供核对，不能用新ID重做来覆盖不确定结果。

演示HTTP只监听127.0.0.1，路由为`/api/local-demo/v1`，回执状态为`LOCAL_COMMITTED`、scope为`local-demo-only`，没有Broker ID。它不监听公共底座路由，不改变网关outbox的CAPTURED状态，不执行WMS收货。完整接口和分开启动方法见[本地演示契约](local-demo-contract.md)。

## 0.2.0：手机摄像头扫二维码

新增“摄像头扫二维码”入口。Android相机提供NV21亮度帧，C层通过quirc v1.2离线识别，成功后返回终端并调用原C持久队列；一次进入扫码页只接收一条，重复扫码需再次点击。标签仍限1–96位可打印ASCII，不自动打开网址或确认收货。相机只在扫码页使用，返回或切入后台立即释放，不保存和上传画面。首次使用按Android提示允许相机权限；拒绝后可返回继续手动输入。

当前摄像头支持QR二维码；一维条码摄像头识别和RFID尚未实现。quirc源码、ISC许可证及文件摘要在native/third_party/quirc，APK也包含许可证。Java继续只负责界面、相机采集、系统权限及生命周期，识别、协议和队列均在C层。

测试二维码内容为`SIM-STEEL-B001`，生成图位于交付包。运行无队列写入的识别测试：

```powershell
adb -s 实际设备序列号 shell am instrument -w cn.projectb.terminal/.QrInstrumentation
```

测试覆盖同一二维码四种旋转、空白画面、短帧、空帧、超大尺寸以及非法标签输入。实际用摄像头对着电脑二维码扫描的结果需另行记录，不能用此测试代替。

周绪敬，2026-10-24。C语言实现设备模型、原始帧、网络服务、ACK队列和持久化；Java仅负责Android界面、通知与生命周期。本版为个人安装调试APK，最低Android8.0，含ARM32/ARM64/x86/x86_64。

## 安装到自己的手机

1. 将`WMS-Android-C-Terminal-0.5.0.apk`传到手机，用文件管理器打开，按系统提示允许该文件来源安装。升级用同一签名覆盖安装，不卸载旧版。0.5.0本轮只在模拟器升级验证，个人手机由用户决定何时安装。
2. 打开“WMS设备模拟终端”，在“扫码”页点击“摄像头扫二维码”并允许相机权限，对准电脑上的测试二维码。也可选择预设标签或手动输入ASCII条码，点击“读取标签并保存”。无需电脑Python或账号即可操作手机设备模型。
3. “称重”页修改毛重／皮重和稳定状态；“工艺”页修改筒仓料位、压力、风量及占用／堵塞／泄漏。点击保存才改变C模型。
4. 首次联调时进入“连接”，启动“局域网设备服务”或“USB／仅本机服务”。通知栏可停止。后台运行有前台服务通知；从最近任务划掉应用会停止服务并保留队列。

采集方式为摄像头二维码、预设模拟标签和手动输入，未接RFID硬件。扫描与称重均不会直接增加WMS库存。卸载应用会移除本机私有队列和报文；升级使用同一签名覆盖安装以保留数据。

## 网关如何读取手机

局域网方式：手机和网关处于可互通网络，“连接”页显示手机IPv4。电脑执行以下命令，将示例IP替换为手机显示值。

```powershell
python gateway.py --device handheld --host 192.168.1.100 --count 1 --output runtime/phone-scan
python gateway.py --device scale --host 192.168.1.100 --output runtime/phone-scale
python gateway.py --device silo --host 192.168.1.100 --output runtime/phone-silo
python gateway.py --device unbox --host 192.168.1.100 --output runtime/phone-unbox
python gateway.py --device chute --host 192.168.1.100 --output runtime/phone-chute
```

USB方式在手机启用开发者选项和USB调试后，用ADB转发。手机选择“USB／仅本机服务”，电脑使用默认127.0.0.1。

```powershell
adb -s 实际设备序列号 forward tcp:19061 tcp:19061
adb -s 实际设备序列号 forward tcp:19062 tcp:19062
adb -s 实际设备序列号 forward tcp:1502 tcp:1502
adb -s 实际设备序列号 forward tcp:1503 tcp:1503
adb -s 实际设备序列号 forward tcp:1504 tcp:1504
python gateway.py --device handheld --output runtime/phone-scan
```

先运行`adb devices`查看序列号，手机和模拟器同时连接时必须使用`-s`明确目标。扫码19061和汽车衡19062为串口原始字节隧道；筒仓1502、拆箱房1503、空气斜槽1504为Modbus TCP。每通道一个采集连接。点表中address是0起始PDU地址，不再减1。`gateway.py`是交给董博涵的参考客户端，不代替其网关交付。

扫码参考工具每次读取默认1条，5秒未收到完整帧会退出并保存BAD事件；开始前先产生一条扫码。它没有实现长期运行的生产网关。ASCII报文本身不含手机队列序号，ACK丢失后的重发与新一次同标签扫码无法仅凭此报文区分；参考工具只保证已保存outbox重试不换事件身份，不能宣称设备到网关恰好一次。

点表normal字段是静态备用样例，不代表所有C模型的实际默认输出：模型9使用料位×2500m³×1200kg/m³÷1000计算库存，65%输出1950t；模型10输出3000−1950=1050t。默认预期读数以实际C模型和附录12为准。

网关先将事件原文、SHA256及原始报文提交本地SQLite，之后才向扫码器ACK。普通重试保持原始事件字节和身份；新采集／新进程使用新boot_id。上次原始待发事件保留在原outbox中。超过5秒未得到完整帧按失败退出，并保存null/BAD证据。扫码发生在手机，source_ts_ms为null，网关观测时间单独保留。

## 公共底座和WMS接入

按配套文档06取得董博涵验证过的SSH配置、令牌和入口后再执行：

```powershell
ssh -N -L 18190:127.0.0.1:28110 -o ExitOnForwardFailure=yes bn4
python base_client.py --outbox runtime/phone-scan --base-url http://127.0.0.1:18190 --token-file C:/b-project-private/access.token
python wms_bridge.py --base-url http://127.0.0.1:18190 --token-file C:/b-project-private/access.token --event-id RUN:BOOT:SIM-PDA-01:1 --wms-config config.local.json
```

事件ID使用采集输出的实际值。base_client保持SQLite原始字节提交，核对202回执、首次wire_sha256、COMMITTED原事件和broker_message_id。失败保留原消息供再次执行，不换ID。令牌只读文件，不写日志；只接受文档06的显式回环HTTP隧道，拒绝代理和重定向。

WMS桥接按明确event_id查询，不用轮询latest承接不能漏的扫码事件。它复用原simulator.py的Store/Engine，source事件、完整质量和模拟标志留在WMS模拟后端的`device_source_events`表，并关联扫描ID及收货job_id。只暂存扫码，未自动确认收货。超过60秒、未来超过5秒、乱序或非GOOD扫码保留复核，不生成新收货。

人工确认使用`--confirm`，作用为确认当前任务中已暂存的所有扫描。真实WMS沿用既有`--enable-test-writes`／`--enable-lab-writes`、私密配置、原生登录、作用域和有限任务限制；没有新增账号、绕过鉴权或直写业务库。称重和其他遥测当前提供查询事件和点值映射，完整称重单／遥测业务页面仍待正式契约及业务实现。

本地收货演练使用原`config.example.json`的demo配置并指定全新state_dir；不把本地SQLite收货当作openGauss结果。HTTP契约测试的COMMITTED来自明确标记的本机夹具，不是公共底座。

## 编译与验证

需要JDK17、官方Android SDK platform34/build-tools32.0.0，以及Android NDK r27d（27.3.13750724）。不依赖Gradle、Python安卓环境或第三方Android库。

```powershell
python build.py --sdk D:/anddroidSDK --ndk D:/android-ndk-r27d --output outputs
python -m unittest discover -s tests -v
adb -s 实际设备序列号 install --no-incremental -r outputs/WMS-Android-C-Terminal-0.5.0.apk
adb shell am instrument -w cn.projectb.terminal/.ProtocolInstrumentation
# 以下测试只允许Android模拟器；会产生有限模拟数据，不用于个人手机：
python tests/android_smoke.py --serial emulator-5554 --output runtime/android-smoke
```

native/protocol_core.c为纯C模型与编解码，native/android_bridge.c为C端JNI/线程/套接字。C编译开启Wall/Wextra/Werror及16KiB ELF对齐，Java只做系统UI。私有签名密钥在`.private/`，不得发群或提交；发布包不含该目录。若需后续覆盖升级，由原编译环境保留该签名密钥。

详见[协议资料卡](protocol-cards.md)、`device-points.json`、交付包“点位映射.csv”和“验证记录”。摄像头扫码测试与公共底座、真实网关及线上WMS验收分别记录。
