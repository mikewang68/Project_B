#!/usr/bin/env bash
# SCS 正式环境部署脚本（node4 /opt/project-b/SCS，systemd project-b-scs）
# 备份命名 safety-gate-service.jar.bak_YYYYMMDD_HHMMSS（秒级，同日多次部署互不覆盖），
# 属主 scszxj:scszxj 640；健康检查不过自动回滚到备份并 exit 1。
set -Eeuo pipefail

NEW=/home/jingchanglong/Project_B/SCS/backend/target/safety-gate-service.jar
DEST=/opt/project-b/SCS/safety-gate-service.jar
BAK="/opt/project-b/SCS/safety-gate-service.jar.bak_$(date +%Y%m%d_%H%M%S)"
HEALTH_URL=${HEALTH_URL:-http://192.168.101.57:18082/health/ready}
HEALTH_RETRIES=${HEALTH_RETRIES:-6}
HEALTH_INTERVAL=${HEALTH_INTERVAL:-10}

rollback() {
  echo "!! 部署失败，回滚到 $BAK"
  install -o scszxj -g scszxj -m 640 "$BAK" "$DEST"
  systemctl restart project-b-scs
  sleep 10
  systemctl is-active project-b-scs || true
  exit 1
}

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

echo "== 5. 健康检查（最多 $HEALTH_RETRIES 次，间隔 ${HEALTH_INTERVAL}s；失败自动回滚） =="
healthy=0
for i in $(seq 1 "$HEALTH_RETRIES"); do
  sleep "$HEALTH_INTERVAL"
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$HEALTH_URL" || true)
  echo "attempt $i/$HEALTH_RETRIES -> HTTP $code"
  if [ "$code" = "200" ]; then healthy=1; break; fi
done
if [ "$healthy" != "1" ]; then rollback; fi

echo "== 6. 启动日志尾部 =="
journalctl -u project-b-scs -n 12 --no-pager | tail -12
echo "health: $(curl -s --max-time 5 "$HEALTH_URL" | head -c 400)"
echo ""
echo "DEPLOY SCRIPT DONE（回滚点：$BAK）"
