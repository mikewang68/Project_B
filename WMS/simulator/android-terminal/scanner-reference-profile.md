# DS2208 独立握手参考（0.5.0）

本模式为原厂RS-232 ACK/NAK行为的有限参考实现。与19061的业务持久队列分开；不写protocol-state.bin，不改队列身份或110点映射。不是SSI，不是DS2208原生网络接口，不模拟RS232电气、硬件握手或原厂蜂鸣。

依据：[Zebra原厂手册](https://www.zebra.com/content/dam/support-dam/en/documentation/unrestricted/guide/product/ds2208-prg-en.pdf)，MN-002874-14EN Rev A，2025-10，423页；SHA256 87586d3f2f35a1554b06e005e482f3483deef5572003ce6a8ff62f94f11f1568。9-14页说明ACK/NAK、有限NAK失败和无应答超时丢弃，9-16页默认响应时间2秒；DATA＋SUFFIX1／CRLF须显式配置，ACK/NAK也不是原厂默认启用。

## 端口、状态与原厂差异

19065为独立ASCII＋CR/LF字节隧道，1–96位可打印ASCII沿用本模拟器标签限制。应用“扫码”页选择“原厂握手参考（不入队）”，参考客户端先连接后才能发送。一次连接只进行一笔临时事务；结束后关闭连接，下一笔须重连。参考标签只在内存中，进程重启不恢复。

ACK=06：完成；NAK=15：有限重发同一原文；超时：不重试，丢弃本笔临时标签并记录错误。**原文的三次失败没有明确是否含首次发送，本模式明确按总发送3次、含首次解释，准确计数仍待实机／厂家确认。** 这不是“3次重发＋1次首发”的证明。

每次完整发送后用CLOCK_MONOTONIC设置绝对2秒截止，收到其它字节不延长截止。这些未定义字节的忽略策略、TCP连接断开／服务停止时丢弃、单笔连接结束隔离迟到ACK均是本字节隧道的明确约定，不能冒充原厂断电、电缆断开或所有输入缓冲行为。先检查截止再受理ACK，因此恰好到期的ACK作为超时。

状态包括IDLE、READY、WAIT、ACKED、NAK_LIMIT、TIMEOUT、DISCONNECTED、STOPPED。结果写strict-handshake.log；原帧写raw-frames.log并带19065端口。本地记录时间不是设备测量源时间，没有增加设备事件ID。错误仅在页面和记录中显示，蜂鸣未实现。

## 怎么测试

先在“连接”页启动服务。电脑解压源码，进入simulator/android-terminal：

```powershell
adb -s 实际Android序列号 forward tcp:19065 tcp:19065
python scanner_reference_client.py --scenario nak-limit --output runtime/handshake-nak-1
```

看到“已连接19065”后，在原厂握手参考页发送默认SIM-HANDSHAKE-001。工具核对三次相同原文、回复三次NAK并观察连接关闭；报告为ds2208.handshake.reference.v1。可选ack、nak-then-ack、nak-limit、timeout、unexpected-timeout、disconnect，目录须新建；它不操作终端界面、不自动发送扫码、不连接公共底座或WMS。工具初次读帧等待15秒仅供操作者点击，不是设备ACK超时。

timeout和unexpected-timeout检查从收到帧到关闭约2秒；disconnect只触发并记录对端断连，需结合Android独立日志判断设备侧状态。原协议ASCII不含事务身份；有限重发与同标签新一次扫描仍不能仅凭报文字节区分。该模式不承诺可靠业务交付。

## 业务扫码继续使用原持久队列

19061仍是我方扩展：识别保存后持久排队，ACK后才移除，NAK会继续重发、断连保留；它没有被本模式的丢弃逻辑替换。默认相机二维码和“读取标签并保存”仍进入19061业务队列。本参考页没有相机入口，发送不调用wms_scan_add或wms_scan_ack；严禁用原业务队列清空来模拟原厂丢弃。

0.5.0补的是可检查的行为子集，原厂首发计数、蜂鸣及实物验证继续缺项。实体手机光学扫码由用户确认；不将模拟器参考事务写成公共底座COMMITTED或WMS收货。
