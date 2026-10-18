# 通用模拟发送规范与 TRUST 示例

本工具是向获准独立测试实例发送真实请求的模拟调用方；不是 `trust-selfcheck.jar serve` 本地接收端，也不证明外部业务写库与发件箱同事务。默认不启动、不连接。运行要求 Python 3.9+ 标准库；现有 Java 17 自检包的运行要求不变。

## 需求来源与边界

B 项目改进计划第 14 条要求多输入／多输出通道及数据库、落盘等目的地，并要求各 app 编写守候进程模拟发送、在本 app 体现。第 5 条是日志数据库和链双保存，第 8 条是健康管理心跳；三者不是一个固定“两个输出”协议。

通用底座设计只在本文约定，未建设跨模块平台。其他模块自行实现业务展示、输入适配和输出适配；TRUST 示例只适配通用 JSON 存证 API，不连接身份/WMS 入口、不部署消息中间件。所有实际写入必须先批准来源、通道、钱包、类型、配额和保留方式。

## 通用通道约定

输入适配器在发送前生成稳定来源事件号和不可变业务快照；持久化输入与所有目标待办后才能报告“已入队”。每个输出有独立目标、来源身份、状态、尝试次数、最近成功时间和错误类别。所有必需输出完成才报告总体完成；可部分成功，不通过删除成功记录来重复发送。

输出状态：待查询/待发、结果未知、已受理处理中、需要人工处理、拒绝、次数耗尽、完成。丢响应与超时均先按原事件号查询；查询成功沿用原回执，查无且从未受理才用原号原快照提交；已受理记录突然查无应停下来核查。202 不表示完成。需要人工处理的任务不自动补办，显式获准后恢复。多个输出失败时各自恢复，不能换号或覆盖已成功输出。

长期运行器应提供受限队列、速率、最大执行时长/次数、停止信号、错误退避和持久重启恢复。心跳独立于业务成功：显示在线、各输出状态、最早积压和最近业务完成。生产常驻服务及外部心跳接收器由底座负责人另行选型；此首版为有界前台运行器，可由经批准的服务管理器调用，默认不安装自启动。

## 示例配置与使用

将 `deploy/simulator.example.json` 复制到私有 `.local/simulator/`，启用前由维护方检查。配置中的相对文件名以该文件目录解析。`targets.json` 沿用 `deploy/generic-client-targets.example.json` 格式，必须明确启用 `purpose=dedicated-generic-test` 与预期 `instanceId`；凭据文件格式为 `{"token":"合成或专用测试凭据"}`，不能把真实 token 写入源码或命令行。多个输出各自列名称、目标与凭据。

只允许经明确批准的回环端口；远端测试资源通过已核实的专用隧道映射。拒绝代理、重定向及旧服务端口。每次 run 先验证各目标 `/capabilities` 的实例 ID、隔离开启与 schema 版本。凭据、输入、SQLite 状态和输出记录都放 `.local`，Unix 文件权限 0600，Windows 需预先限制目录 ACL。状态库含业务原文，应与凭据一样保管。

```sh
# 入队不连接网络，完整保存实际客户端生成的原文字节；可重复指定 --input。
python scripts/trust-simulator.py enqueue --state .local/simulator/state.sqlite --config .local/simulator/config.json --input examples/generic-maintenance.json
# 无 --send 只预演，零网络请求。
python scripts/trust-simulator.py run --state .local/simulator/state.sqlite --config .local/simulator/config.json
# 只有获得专用写入批准后使用 --send。
python scripts/trust-simulator.py run --state .local/simulator/state.sqlite --config .local/simulator/config.json --send --duration 120 --interval 2 --max-requests 100 --max-attempts 20
python scripts/trust-simulator.py status --state .local/simulator/state.sqlite
# ACTION_REQUIRED：修复问题并获准补办后将指定输出转为待 retry；此命令本身不连接。
python scripts/trust-simulator.py retry --state .local/simulator/state.sqlite --event synthetic-maintenance-001 --output evidence
```

周期心跳每 30 秒打印 JSON；结束打印 summary。正文和凭据不进入报告。间隔对每一次业务 HTTP 请求生效（含查询），错误也遵循相同有限间隔，不紧密循环；启动能力检查不计入业务次数。每个状态库最多 10000 条输入，一次 run 最长 600 秒，不并发多个实例修改同一状态库。Ctrl+C/SIGTERM 在当前请求结束后停止，已有待办不丢失；再次 run 查询恢复。能力检查或请求最长 15 秒，停止不是即时中断网络。

状态库固定目标及凭据摘要，改目标、增删输出或更换来源凭据后拒绝静默恢复，维护者必须先对账再用新状态库继续；首版不自动迁移凭据。入队同号要求精确原文字节一致，即使服务端可能将等价 JSON 视为幂等，本工具也不允许覆盖首次原文。

run/status 只有存在至少一项待办且所有输出均已完成才退出 0；未完成、拒绝、未知、超时、配置错误均退出 2。enqueue/retry 的 0 仅表示本地管理操作成功，不表示上链完成。空队列不能假通过。到达次数上限进入 EXHAUSTED，人工 review 后用 retry 恢复查询；ACTION_REQUIRED 只在显式 retry 后调用服务器补办。

## 接入验收

先用 Java 自检包检查实际请求，再运行独立测试来源。验证丢响应后按原号查回、重复输入、同号冲突、一个输出成功另一个失败、进程重启、202 未完成、未知状态拒绝、已受理记录消失、ACTION_REQUIRED 不自动重试、有限重试及空队列失败。调用方自己的业务状态断言不能被此工具的完成状态替代。

本仓库 `tests/test_trust_simulator.py` 使用本地替身验证上述调度与持久性，不构成真实 Fabric/IPFS 测试。真实授权与客户端联调必须另附私有验收记录。
