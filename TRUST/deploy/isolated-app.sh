#!/usr/bin/env bash
# A separate instance; never falls back to the demonstration database or port.
set -euo pipefail
umask 077
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
cmd=${1:-status}
config="$ROOT/runtime/secrets/isolated.env"
[[ -f "$config" ]] || { echo 'Missing runtime/secrets/isolated.env' >&2; exit 2; }
set -a
source "$config"
set +a
export TRUST_ROOT="$ROOT" TRUST_ISOLATION_ENABLED=true
[[ "$ROOT" != /data/app/trust && "$ROOT" != /home/*/projects/b-project-trust* ]] || exit 2
python3 "$ROOT/deploy/identity-instance.py"
[[ "${SERVER_PORT:-}" =~ ^[0-9]+$ && "$SERVER_PORT" -ge 1024 && "$SERVER_PORT" -le 65535 && "$SERVER_PORT" != 28182 && "$SERVER_PORT" != 28183 ]] || exit 2
export SERVER_ADDRESS=127.0.0.1 SPRING_LIQUIBASE_ENABLED=false
[[ "${TRUST_FABRIC_QUERY_KEY_REF:-}" =~ ^[A-Za-z0-9_-]{1,100}$ ]] || { echo 'Encrypted query identity reference required'; exit 2; }
pidfile="$ROOT/runtime/pids/isolated-app.pid"
jar="$ROOT/artifacts/trust-identity.jar"
owned() {
  [[ -f "$pidfile" ]] || return 1
  pid=$(cat "$pidfile")
  [[ "$pid" =~ ^[0-9]+$ && -r "/proc/$pid/cmdline" ]] || return 1
  [[ $(readlink -f "/proc/$pid/cwd") == "$ROOT" ]] || return 1
  [[ $(tr '\0' '\n' < "/proc/$pid/cmdline" | grep -Fx -- "$jar") == "$jar" ]] || return 1
  [[ $(awk '{print $3}' "/proc/$pid/stat") != Z ]]
}
health() { curl --connect-timeout 1 --max-time 2 -fsS "http://127.0.0.1:$SERVER_PORT/actuator/health"; }
case "$cmd" in
status)
  owned || { echo 'Isolated instance stopped (or PID ownership differs)'; exit 1; }
  printf 'root=%s port=%s database=%s channel=%s pid=%s\n' "$ROOT" "$SERVER_PORT" "${TRUST_DB_URL##*/}" "$TRUST_FABRIC_CHANNEL" "$pid"
  health
  ;;
build)
  mkdir -p "$ROOT/artifacts" "$ROOT/.local"
  cd "$ROOT/frontend"
  pnpm --ignore-workspace install --frozen-lockfile --offline
  pnpm --ignore-workspace run build
  mkdir -p "$ROOT/backend/src/main/resources/static"
  cp -a dist/. "$ROOT/backend/src/main/resources/static/"
  cd "$ROOT/backend"
  mvn -B -ntp -Dmaven.repo.local="${TRUST_MAVEN_CACHE:?}" verify dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
  cp target/trust-platform-0.1.0.jar "$jar"
  sha256sum "$jar"
  ;;
migrate)
  # Migration credentials are read only for this short-lived command.
  source "$ROOT/runtime/secrets/migration.env"
  export TRUST_MIGRATE_PASSWORD
  cd "$ROOT/backend"
  java -Dloader.main=com.bproject.trust.config.IsolatedMigration -cp "$jar" org.springframework.boot.loader.launch.PropertiesLauncher
  ;;
verify-crl)
  java -Dloader.main=com.bproject.trust.provisioning.CrlVerification -cp "$jar" org.springframework.boot.loader.launch.PropertiesLauncher "${2:?identityId required}" "${3:?private plan required}"
  ;;
start|debug)
  if owned; then echo 'Isolated instance already running'; exit 0; fi
  python3 - "$SERVER_PORT" <<'PY'
import socket,sys
with socket.socket() as s:
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(('127.0.0.1', int(sys.argv[1])))
PY
  mkdir -p "$ROOT/runtime/pids" "$ROOT/runtime/logs"
  chmod 700 "$ROOT/runtime" "$ROOT/runtime/secrets"
  cd "$ROOT"
  if [[ "$cmd" == debug ]]; then
    # Foreground execution keeps this PID across exec, including under systemd.
    echo $$ > "$pidfile"
    exec java -Djava.net.preferIPv4Stack=true -Xms128m -Xmx768m -jar "$jar"
  fi
  nohup java -Djava.net.preferIPv4Stack=true -Xms128m -Xmx768m -jar "$jar" >"$ROOT/runtime/logs/isolated-app.log" 2>&1 </dev/null &
  echo $! > "$pidfile"
  for i in {1..60}; do
    owned || { echo 'Startup failed; inspect isolated-app.log'; exit 1; }
    if health 2>/dev/null | grep -q '"UP"'; then echo "Ready on 127.0.0.1:$SERVER_PORT"; exit 0; fi
    sleep 1
  done
  echo 'Readiness timeout; process retained for inspection'; exit 1
  ;;
stop)
  owned || { echo 'No owned isolated process; nothing stopped'; exit 0; }
  kill "$pid"
  for i in {1..120}; do owned || { rm -f -- "$pidfile"; exit 0; }; sleep .25; done
  echo 'Shutdown timeout; PID retained'; exit 1
  ;;
*) echo 'Usage: isolated-app.sh build|migrate|start|stop|status|debug|verify-crl'; exit 2 ;;
esac
