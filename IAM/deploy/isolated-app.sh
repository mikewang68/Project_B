#!/usr/bin/env bash
set -euo pipefail
umask 077
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
[[ -f "$ROOT/runtime/secrets/isolated.env" ]] || { echo 'Private isolated.env required' >&2; exit 2; }
set -a
source "$ROOT/runtime/secrets/isolated.env"
set +a
[[ "$ROOT" == "$IAM_ROOT" && "$ROOT" != /data/app/* ]] || exit 2
[[ "${IAM_DB_URL:-}" =~ ^jdbc:postgresql://127\.0\.0\.1:25432/iam_identity_[a-z0-9_]+\?currentSchema=iam$ ]] || exit 2
[[ "${IAM_DB_USER:-}" =~ ^iam_identity_[a-z0-9_]+_app$ ]] || exit 2
[[ "${IAM_SERVER_PORT:-}" =~ ^[0-9]+$ && "$IAM_SERVER_PORT" -ge 1024 && "$IAM_SERVER_PORT" -le 65535 ]] || exit 2
[[ "$IAM_SERVER_PORT" != 28182 && "$IAM_SERVER_PORT" != 28183 && "$IAM_SERVER_PORT" != 18080 && "$IAM_SERVER_PORT" != 18091 ]] || exit 2
export SERVER_ADDRESS=127.0.0.1 IAM_ISOLATION_ENABLED=true
jar="$ROOT/artifacts/iam-backend.jar"
pidfile="$ROOT/runtime/pids/identity-app.pid"
owned() {
  [[ -f "$pidfile" ]] || return 1
  pid=$(cat "$pidfile")
  [[ "$pid" =~ ^[0-9]+$ && -r "/proc/$pid/cmdline" ]] || return 1
  [[ $(readlink -f "/proc/$pid/cwd") == "$ROOT" ]] || return 1
  tr '\0' '\n' < "/proc/$pid/cmdline" | grep -Fxq -- "$jar"
}
case "${1:-status}" in
status) owned && curl --connect-timeout 1 --max-time 3 -fsS "http://127.0.0.1:$IAM_SERVER_PORT/api/v1/iam/health" ;;
start|debug)
  if owned; then echo 'This isolated instance is already running'; exit 0; fi
  python3 - "$IAM_SERVER_PORT" <<'PY'
import socket,sys
with socket.socket() as s: s.bind(('127.0.0.1',int(sys.argv[1])))
PY
  mkdir -p "$ROOT/runtime/pids" "$ROOT/runtime/logs"
  cd "$ROOT"
  if [[ "$1" == debug ]]; then echo $$ > "$pidfile"; exec java -Xmx512m -jar "$jar"; fi
  nohup java -Xmx512m -jar "$jar" > runtime/logs/identity-app.log 2>&1 </dev/null &
  echo $! > "$pidfile"
  ;;
stop)
  if owned; then kill "$pid"; else echo 'No owned process; nothing stopped'; fi
  ;;
bootstrap)
  java -Dloader.main=com.bdemo.iam.config.IamBootstrap -cp "$jar" org.springframework.boot.loader.launch.PropertiesLauncher
  ;;
migrate)
  source "$ROOT/runtime/secrets/migration.env"
  export IAM_MIGRATE_PASSWORD
  java -Dloader.main=com.bdemo.iam.config.IamMigration -cp "$jar" org.springframework.boot.loader.launch.PropertiesLauncher
  ;;
*) echo 'Usage: isolated-app.sh status|start|debug|stop|migrate|bootstrap' >&2; exit 2 ;;
esac
