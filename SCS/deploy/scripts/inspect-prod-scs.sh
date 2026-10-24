#!/usr/bin/env bash
# SCS 正式环境部署前勘察（只读，不修改任何状态；不输出任何密码类值）
echo "== 1. systemd 服务状态 =="
systemctl status project-b-scs --no-pager -l 2>/dev/null | head -12
echo ""
echo "== 2. 当前 JAR 指纹与属主 =="
stat -c '%n | %U:%G | %a | %s bytes | %y' /opt/project-b/SCS/safety-gate-service.jar 2>/dev/null
sha256sum /opt/project-b/SCS/safety-gate-service.jar 2>/dev/null
ls -la /opt/project-b/SCS/ 2>/dev/null
echo ""
echo "== 3. service.env（已过滤密码类行） =="
grep -vE 'PASSWORD|SECRET|TOKEN|KEY|PASS' /etc/project-b/SCS/service.env 2>/dev/null
echo ""
echo "== 4. systemd unit 定义 =="
grep -vE '^#|^$' /etc/systemd/system/project-b-scs.service 2>/dev/null
echo ""
echo "== 5. 运行进程 =="
ps -ef | grep safety-gate | grep -v grep | head -3
