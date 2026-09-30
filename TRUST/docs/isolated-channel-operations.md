# 隔离通道运维边界

`deploy/wallet-channel.sh` 只接受显式 `trust-*` 通道名，拒绝旧 `trust`。参数校验在读取运行配置前完成。`status` 不创建目录、修改权限、打包、启动进程或提交 Fabric 定义。

```sh
bash deploy/wallet-channel.sh status trust-wallet-dev
# 以下仅用于另行选定的新隔离通道，不要对既有通道重复执行部署。
bash deploy/wallet-channel.sh create trust-example
bash deploy/wallet-channel.sh deploy-v1 trust-example 27459
bash deploy/wallet-channel.sh upgrade-v2 trust-example 27259
bash deploy/wallet-channel.sh upgrade-v21 trust-example 27659
```

两组织端口分别为给定端口与给定端口加 2000。脚本先检查两端口范围与占用，再产生制品。不同通道须选不同空闲端口；默认端口不保证空闲。v1 从已有原版二进制复制出独立制品并启动独立进程；v2 从当前合约源码构建。二进制、包、包 ID、PID 和日志均包含通道名，已有不同内容的隔离制品拒绝覆盖。

部署前读取两组织实际 `evidence` 定义并要求一致。v2 升级只接受 1.0/sequence 1；已经为 2.0/sequence 2 时只读取状态并退出。`upgrade-v21` 只接受 2.0/sequence 2，并提交 2.1/sequence 3，默认端口为 27659/29659，独立制品后缀为 v21。已为 2.1/sequence 3 时，各旧版本部署命令与 `upgrade-v21` 都只读取状态，不降级。此幂等路径不修复进程；服务缺失须另行检查根因，不能通过再次提交定义掩盖。

`package-chaincode.py` 保留原版 1–3 参数调用，供既有演示部署脚本维护。隔离脚本始终传入第 4 个通道参数，使用独立标签及文件名；生成内容固定时间戳，重复打包得到相同包 ID。不要在本轮隔离开发中运行旧 `fabric.sh` 或 `upgrade-wallet-contract.sh`。

`tests/fabric-sample` 在读取凭据及建立连接之前校验命令和完整参数数量。`get`、`history`、`get-signer` 可只读查询旧通道；`submit`、`set-signer` 只允许显式隔离 `trust-*` 通道。工具的入参保护不替代管理员身份和链码策略校验。
