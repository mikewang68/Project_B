# 隔离应用运维

仅维护本轮专用实例。旧演示制品、原数据库、旧通道和网关均不通过下述命令管理。真实路径、账号口令、节点地址和原始证据保存在排除目录，不能复制进Git。

## 配置与数据库

独立根目录中将 `deploy/isolated.env.example` 填写为 `runtime/secrets/isolated.env`，将 `deploy/integration.isolated.example.json` 填写为同目录 `integration.json`。凭据文件权限600，目录700。TRUST_ROOT固定为脚本所属根目录；回环应用端口、专用库、专用通道必须明确指定。模板中的服务凭据摘要须换成随机服务令牌的SHA256，不能直接使用占位符。令牌仅交给受限测试发送器。

隔离实例的 TRUST_FABRIC_QUERY_KEY_REF 必须指向已预置的加密 Org1 身份。查询和提交都从加密材料加载密钥，不在本实例保留 fabric/user.key 明文副本；tls-ca.crt 保留用于链连接。首次启动前先完成加密材料预置。旧演示模式的兼容路径不受影响。

模拟身份默认关闭，只有 `iamMode=simulated` 与 `isolationEnabled=true` 同时配置才启用。固定账号为 wallet-applicant、wallet-reviewer、wallet-self-reviewer、other-org-reviewer、no-permission、business-operator；这些是隔离测试身份，不能当正式IAM用户。测试口令由模拟器固定，仅在回环隔离服务使用。

若隔离演示需兼容原开发账号，在isolated.env中显式设置TRUST_DEV_QUICK_LOGIN_ENABLED=true，并在本实例runtime/secrets/users.json中配置admin、editor、viewer、external-viewer及其原有角色/组织。页面同时保留“模拟 IAM 测试账号”和“开发测试账号（本地）”，后者支持密码及快捷登录。本地账号不能取得钱包权限；IAM会话的撤权、失联和组织变化仍拒绝访问，不自动切换身份。切换为真实IAM模式时，本地入口即使保留开发开关也会被后端拒绝。

数据库管理员按经过审查的私有资源清单准备独立库与受限角色。现场专用初始化脚本仅在本地保管，不随源码发布；不得重置既有角色密码或自动改变既有业务库的 PUBLIC 权限。发现开发角色可连接旧库时，应停止并解决最小权限问题。

专用库和trust_data归迁移角色所有；运行角色只有业务DML。签名上下文仅允许SELECT/INSERT，签名尝试只允许更新结果字段，Liquibase表不授运行角色权限。迁移通过短生命周期命令执行，应用启动禁用自动迁移。迁移密码单独放 `runtime/secrets/migration.env` 的 TRUST_MIGRATE_PASSWORD；运行进程不加载该文件。

## 操作入口

```sh
bash deploy/isolated-app.sh build
bash deploy/isolated-app.sh migrate
bash deploy/isolated-app.sh start
bash deploy/isolated-app.sh status
bash deploy/isolated-app.sh stop
bash deploy/isolated-app.sh debug
```

build复用指定Maven缓存及pnpm离线缓存，执行类型检查、前端构建和后端verify。status只读取所属PID与目标端口健康，不创建目录或修改配置。stop要求PID的工作目录、完整jar参数均属于此实例，拒绝按进程名批量停止。debug前台运行同一制品并记录进程PID，以便观察日志和核对进程归属，不开放远程调试端口。交给systemd管理时使用Type=simple及debug入口，不让systemd通过用户目录中的PIDFile接管进程；必须另行等待应用健康后才切换访问入口。若服务配置自动重启，维护停止应通过对应systemd服务执行，避免直接停止应用后被自动拉起。

旧入口application.sh在发现isolated.env时转交此脚本，避免使用旧库和旧端口默认值。独立启动器与应用早期配置保护共同拒绝trust/trust_test、旧应用角色、旧通道、28182、非回环监听和运行期迁移。端口检查允许已停止进程的TIME_WAIT状态，但拒绝仍在监听的服务。

访问地址由本实例私有配置决定。回环地址属于应用主机；跨机访问须先核实 SSH 转发许可，再按最小范围放行，不能直接改为监听 0.0.0.0。

## 复验与恢复

`TRUST_DB_INTEGRATION=1` 启用专用库事务测试；TRUST_TEST_DB_URL显式选择trust_wallet_dev_test，运行角色仍为trust_dev_app，SPRING_LIQUIBASE_ENABLED=false。`TRUST_REAL_COMPONENTS=1` 另外启用真实Fabric/IPFS丢回执测试，前提是本节点有有效测试钱包材料，且专用通道已授权该证书。

`tests/managed-wallet-real.py` 分setup、events、lifecycle、finish阶段保存输入/结果；setup产生策略供专用通道管理员执行，不会自动赋予网络管理员权限。重复setup需要新的独立环境，不能覆盖已有钱包材料或绑定。`tests/restart-recovery-real.py`仅暂时改变本实例IPFS目标为未使用的回环端口，随后恢复原配置并重启本实例；不会停止共享IPFS或Fabric。`tests/browser-wallet-real.cjs`使用真实后端，页面桩测试另行计数。

发生故障先保留日志、专用库和链上回执。恢复应使用本实例制品和私有配置；不得把旧演示实例当备用写入目标，也不得把历史库回滚当作测试清理。状态不确定的交易先查询账本，确认已有记录后恢复结果，不重复构造业务事件。
