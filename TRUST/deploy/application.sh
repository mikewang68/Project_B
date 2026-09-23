#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
application_health() {
  curl --connect-timeout 1 --max-time 2 -fsS http://127.0.0.1:28182/actuator/health 2>/dev/null
}
wait_for_application() {
  local deadline=$((SECONDS + 60))
  local proc; proc=$(cat "$ROOT/runtime/pids/application.pid")
  echo 'Waiting for application readiness (up to 60 seconds)...'
  while (( SECONDS < deadline )); do
    if ! kill -0 "$proc" 2>/dev/null || [[ "$(awk '{print $3}' "/proc/$proc/stat" 2>/dev/null)" == Z ]]; then
      echo "Application exited before becoming ready. Check $ROOT/runtime/logs/application.log" >&2
      return 1
    fi
    if application_health | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
      echo 'Application ready: http://127.0.0.1:28182'
      return 0
    fi
    sleep 1
  done
  echo "Application is not ready after 60 seconds; process retained. Check $ROOT/runtime/logs/application.log" >&2
  return 1
}
case "${1:-status}" in
 build)
  cd "$ROOT/frontend"
  pnpm install --frozen-lockfile
  pnpm run build
  mkdir -p "$ROOT/backend/src/main/resources/static"
  cp -a "$ROOT/frontend/dist/." "$ROOT/backend/src/main/resources/static/"
  cd "$ROOT/backend"
  mvn -B -ntp -Dmaven.repo.local="$ROOT/tools/m2" clean verify
  cp "$ROOT/backend/target/trust-platform-0.1.0.jar" "$ROOT/artifacts/trust-platform.jar"
  ;;
 start)
  set -a; source "$ROOT/runtime/secrets/db.env"; set +a
  export TRUST_ROOT="$ROOT" TRUST_DB_USER=trust_app
  listen_address=127.0.0.1
  if [[ -f /etc/b-project-trust-http/application-listen-address ]]; then
    IFS= read -r listen_address < /etc/b-project-trust-http/application-listen-address
    [[ "$listen_address" == 0.0.0.0 ]] || { echo 'Invalid managed HTTP listen address' >&2; exit 1; }
  fi
  dev_quick_login=false
  [[ ! -f "$ROOT/runtime/secrets/dev-quick-login.enabled" ]] || dev_quick_login=true
  start_process application java -Djava.net.preferIPv4Stack=true -Xms128m -Xmx768m -jar "$ROOT/artifacts/trust-platform.jar" "--server.address=$listen_address" "--trust.dev-quick-login.enabled=$dev_quick_login"
  wait_for_application
  ;;
 stop) stop_process application ;;
 status)
  if health=$(application_health); then
    echo "$health"
  else
    echo "Application is stopped, starting, or unhealthy. Check $ROOT/runtime/logs/application.log" >&2
    exit 1
  fi ;;
 *) echo 'Usage: application.sh build|start|stop|status';exit 2 ;;
esac
