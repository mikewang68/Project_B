#!/usr/bin/env bash
# SCS 正式环境部署脚本（node4 /opt/project-b/SCS，systemd project-b-scs）
# 遵循既有惯例：备份命名 safety-gate-service.jar.bak_YYYYMMDD，属主 scszxj:scszxj 640
set -Eeuo pipefail

NEW=/home/jingchanglong/Project_B/SCS/backend/target/safety-gate-service.jar
DEST=/opt/project-b/SCS/safety-gate-service.jar
BAK="/opt/project-b/SCS/safety-gate-service.jar.bak_$(date +%Y%m%d)"

echo "== 1. 预检：新产物与当前服务 =="
ls -la "$NEW"
sha256sum "$NEW"
echo "current service: $(systemctl is-active project-b-scs)"
echo "current jar sha:"
sha256sum "$DEST"

echo "== 2. 备份当前 JAR → $BAK =="
cp -a "$DEST" "$BAK"
ls -la "$BAK"

echo "== 3. 安装新 JAR（scszxj:scszxj 640） =="
install -o scszxj -g scszxj -m 640 "$NEW" "$DEST"
sha256sum "$DEST"

echo "== 4. 重启 project-b-scs =="
systemctl restart project-b-scs
sleep 15
echo "after restart: $(systemctl is-active project-b-scs)"

echo "== 5. 启动日志尾部 =="
journalctl -u project-b-scs -n 12 --no-pager | tail -12

echo "== 6. 健康检查 =="
curl -s -o /dev/null -w 'health/ready -> HTTP %{http_code}\n' http://192.168.101.57:18082/health/ready || true
curl -s http://192.168.101.57:18082/health/ready | head -c 400 || true
echo ""
echo "DEPLOY SCRIPT DONE"
