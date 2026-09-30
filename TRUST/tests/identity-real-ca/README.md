# 真实 CA 故障验收

仅用于已批准的独立测试实例 `/srv/b-project-identity-test/TRUST`、`trust_iam_dev_test` 与 `trust-iam-dev-test`。驱动拒绝运行中的测试应用；开发应用及旧演示实例不参与。使用真实 openGauss、官方 Fabric CA client/server 和 Java Gateway；TLS 代理只注入通信故障，不替代 CA。

四项场景：CA 返回 503；CA 已签发后扣住响应超过官方客户端超时；真实签发及密钥托管后临时撤回测试应用角色对证书表的 UPDATE；CA 提交后杀死 worker/CLI 并等待持久租约过期。每项断言恢复为 READY、只有一个证书版本和一次真实 CA 签发。数据库权限在 finally 恢复，代理在 finally 停止。异常中断时须由操作员确认 UPDATE 已恢复，保留审计与恢复材料。

准备方式：

1. 用 Java 17 编译 `IdentityFaultProbe.java`，classpath 使用当前 TRUST 后端的编译依赖及 `target/classes`，输出到独立临时目录。将生成的 `IdentityFaultProbe*.class` 与 `ca-fault-proxy.py` 放入测试主机的私有 stage；不要上传凭据。
2. 测试实例必须已有正确迁移、官方 CA registrar、query 身份、受限 `identity-acceptance` 服务调用方及两 Peer 通道。操作员本机读取现有 `runtime/secrets/isolated.env`、`migration.env`。普通 worker 以 `identity-trust-test` 运行，不获得迁移密码。
3. 以已获准的操作员权限运行 `TRUST_FAULT_STAGE=/absolute/private/stage TRUST_FAULT_RUN_ID=<new-run-id> python3 run-fault-matrix.py`。run ID 只接受 1—32 位小写字母、数字和连字符，每次必须使用新值。
4. 私有证据位于 `runtime/real-ca-faults/evidence-<run-id>`，全部报告仅在本地保留，不提交 Git。代理请求记录不含请求体或注册秘密。不要删除数据库身份、证书或审计来重跑。

`../identity-real-ui.cjs` 使用独立 headless Chromium、临时回环 28188/28189 代理与真实 28184/28185 API。`IDENTITY_UI_STAGE` 下需有 `iam-ui/`、`trust-ui/`、`playwright-core/`、`browser-accounts.json`，浏览器路径通过 `PLAYWRIGHT_BROWSERS_PATH` 指定。账号密码仅在目标主机本地生成或读取，不上传本地凭据；凭据文件 mode 0600、父目录 0700，完成后删除。浏览器缺库/中文字体时仅在私有目录解包发行版验签包，通过 `LD_LIBRARY_PATH`、`FONTCONFIG_FILE` 配置。测试结束自动关闭代理和浏览器，截图与脱敏结果写入 `real-ui-results/`。其中一次 HTTP 503 为明确的查询错误注入，其余身份、历史、登录和权限数据均由真实后端返回。
