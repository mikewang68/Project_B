#!/usr/bin/env bash
# SCS 正式环境部署脚本（node4 /opt/project-b/SCS，systemd project-b-scs）
# 备份命名 safety-gate-service.jar.bak_YYYYMMDD_HHMMSS（秒级，同日多次部署互不覆盖），
# 属主 scszxj:scszxj 640；任一步失败（含重启失败）自动回滚到备份并 exit 1。
#
# 产物来源：优先 deploy/generated/<最新时间戳>/backend/safety-gate-service.jar（build-server.sh 的产物），
# 其次 backend/target/safety-gate-service.jar。可用 NEW=/path/to.jar 显式指定。
set -Eeuo pipefail

SCS_ROOT=${SCS_ROOT:-/home/jingchanglong/Project_B/SCS}
DEST=/opt/project-b/SCS/safety-gate-service.jar
SERVICE=project-b-scs
HEALTH_URL=${HEALTH_URL:-http://192.168.101.57:18082/health/ready}
HEALTH_RETRIES=${HEALTH_RETRIES:-6}
HEALTH_INTERVAL=${HEALTH_INTERVAL:-10}
LOCK_FILE=${LOCK_FILE:-/var/lock/scs-deploy.lock}

# ---- 1. 解析新产物：显式 NEW > 最新 generated > target ----
resolve_new_jar() {
  if [[ -n "${NEW:-}" ]]; then
    printf '%s\n' "$NEW"
    return
  fi
  local latest
  latest=$(find "${SCS_ROOT}/deploy/generated" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort | tail -n 1)
  if [[ -n "$latest" && -f "${latest}/backend/safety-gate-service.jar" ]]; then
    printf '%s\n' "${latest}/backend/safety-gate-service.jar"
    return
  fi
  printf '%s\n' "${SCS_ROOT}/backend/target/safety-gate-service.jar"
}

NEW_JAR=$(resolve_new_jar)
BAK="/opt/project-b/SCS/safety-gate-service.jar.bak_$(date +%Y%m%d_%H%M%S)"
INSTALLED=0

# ---- 2. 回滚：恢复备份并重启，重启后仍要做健康验证 ----
rollback() {
  # 防止 rollback 内的 exit 再次触发 EXIT 陷阱造成递归
  trap - EXIT
  echo "!! 部署失败，回滚到 $BAK"
  if [[ ! -f "$BAK" ]]; then
    echo "!! 回滚点不存在，无法自动回滚；服务当前状态：$(systemctl is-active "$SERVICE" || true)" >&2
    exit 1
  fi
  install -o scszxj -g scszxj -m 640 "$BAK" "$DEST"
  systemctl restart "$SERVICE" || {
    echo "!! 回滚重启失败，需人工介入（备份：$BAK）" >&2
    exit 1
  }
  local ok=0
  for _ in $(seq 1 "$HEALTH_RETRIES"); do
    sleep "$HEALTH_INTERVAL"
    if [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$HEALTH_URL" || true)" == "200" ]]; then
      ok=1; break
    fi
  done
  if [[ "$ok" == "1" ]]; then
    echo "回滚完成且健康检查通过（回滚点：$BAK）"
  else
    echo "!! 回滚后健康检查仍未通过，需人工介入（备份：$BAK）" >&2
  fi
  exit 1
}

# 任何非预期失败都走回滚；成功路径在末尾显式关闭该陷阱。
# 只有「已经装上新 JAR」之后的失败才需要回滚——预检失败不应动线上服务。
trap 'rc=$?; if [[ $rc -ne 0 && $INSTALLED -eq 1 ]]; then echo "!! 脚本在第 $LINENO 行失败 (exit=$rc)"; rollback; fi' EXIT

# ---- 3. 并发保护：同一时刻只允许一个部署 ----
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo "ERROR: 另一个部署正在进行（$LOCK_FILE），本次退出" >&2
  exit 1
fi

echo "== 1. 预检：新产物与当前服务 =="
if [[ ! -f "$NEW_JAR" ]]; then
  echo "ERROR: 找不到待部署 JAR：$NEW_JAR" >&2
  echo "       先执行 deploy/scripts/build-server.sh，或显式传 NEW=/path/to.jar" >&2
  exit 1
fi
ls -la "$NEW_JAR"
sha256sum "$NEW_JAR"
echo "current service: $(systemctl is-active "$SERVICE" || true)"
echo "current jar sha:"
sha256sum "$DEST" || true

# 新旧同 SHA 时提示（重复部署通常是忘了重新构建）
if [[ -f "$DEST" ]] && [[ "$(sha256sum "$NEW_JAR" | cut -d' ' -f1)" == "$(sha256sum "$DEST" | cut -d' ' -f1)" ]]; then
  echo "WARN: 待部署 JAR 与当前线上 JAR SHA 相同（是否忘记重新构建？）"
fi

echo "== 2. 备份当前 JAR → $BAK =="
cp -a "$DEST" "$BAK"
ls -la "$BAK"

echo "== 3. 安装新 JAR（scszxj:scszxj 640） =="
install -o scszxj -g scszxj -m 640 "$NEW_JAR" "$DEST"
INSTALLED=1
sha256sum "$DEST"

echo "== 4. 重启 $SERVICE =="
systemctl restart "$SERVICE"

echo "== 5. 健康检查（最多 $HEALTH_RETRIES 次，间隔 ${HEALTH_INTERVAL}s；失败自动回滚） =="
healthy=0
for i in $(seq 1 "$HEALTH_RETRIES"); do
  sleep "$HEALTH_INTERVAL"
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$HEALTH_URL" || true)
  echo "attempt $i/$HEALTH_RETRIES -> HTTP $code"
  if [ "$code" = "200" ]; then healthy=1; break; fi
done
if [ "$healthy" != "1" ]; then
  rollback
fi

trap - EXIT

echo "== 6. 启动日志尾部 =="
journalctl -u "$SERVICE" -n 12 --no-pager | tail -12
echo "health: $(curl -s --max-time 5 "$HEALTH_URL" | head -c 400)"
echo ""
echo "DEPLOY SCRIPT DONE（产物：$NEW_JAR；回滚点：$BAK）"
