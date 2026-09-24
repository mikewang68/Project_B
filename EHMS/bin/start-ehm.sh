#!/usr/bin/env bash
set -euo pipefail

EHM_HOME="${EHM_HOME:-/opt/b-project/ehm}"
ENV_FILE="${EHM_ENV_FILE:-${EHM_HOME}/config/ehm-server.env}"
APP_JAR="${EHM_HOME}/app/ehm-service.jar"
RUN_DIR="${EHM_HOME}/run"
LOG_DIR="${EHM_HOME}/logs"
PID_FILE="${RUN_DIR}/ehm-service.pid"

[[ -f "${ENV_FILE}" ]] || { echo "Missing env file: ${ENV_FILE}" >&2; exit 1; }
[[ -f "${APP_JAR}" ]] || { echo "Missing application jar: ${APP_JAR}" >&2; exit 1; }
if grep -q '__FILL_ME__' "${ENV_FILE}"; then
  echo "Please fill openGauss credentials in ${ENV_FILE}" >&2
  exit 1
fi

mkdir -p "${RUN_DIR}" "${LOG_DIR}"
if [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
  echo "EHM is already running, pid=$(cat "${PID_FILE}")"
  exit 0
fi

set -a
# shellcheck source=/dev/null
source "${ENV_FILE}"
set +a
export SPRING_PROFILES_ACTIVE=server

resolve_isula_opengauss_host() {
  local pid candidate port_hex
  port_hex="$(printf '%04X' "${EHM_OPENGAUSS_PORT:-5432}")"
  for pid in $(pgrep -x gaussdb 2>/dev/null || true); do
    grep -q '/isulad/' "/proc/${pid}/cgroup" 2>/dev/null || continue
    grep -qi ":${port_hex} " "/proc/${pid}/net/tcp" 2>/dev/null || continue
    candidate="$(awk '
      /\/32 host LOCAL/ && previous ~ /^(10\.|172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.)/ { print previous; exit }
      { previous=$2 }
    ' "/proc/${pid}/net/fib_trie" 2>/dev/null || true)"
    if [[ -n "${candidate}" ]]; then
      EHM_OPENGAUSS_HOST="${candidate}"
      export EHM_OPENGAUSS_HOST
      echo "Resolved openGauss iSula endpoint: ${EHM_OPENGAUSS_HOST}:${EHM_OPENGAUSS_PORT:-5432}"
      return 0
    fi
  done
  echo "Unable to discover the openGauss iSula endpoint" >&2
  return 1
}

if [[ "${EHM_OPENGAUSS_HOST:-}" == "isula-auto" ]]; then
  resolve_isula_opengauss_host
fi

JAVA_BIN="${JAVA_BIN:-java}"
nohup "${JAVA_BIN}" \
  -XX:InitialRAMPercentage=10 \
  -XX:MaxRAMPercentage=60 \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=Asia/Shanghai \
  -jar "${APP_JAR}" \
  > "${LOG_DIR}/ehm-service.out" 2>&1 &

echo $! > "${PID_FILE}"
sleep 2
if ! kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
  echo "EHM failed to start; inspect ${LOG_DIR}/ehm-service.out" >&2
  exit 1
fi
echo "EHM started, pid=$(cat "${PID_FILE}"), port=${EHM_SERVER_PORT:-18083}"
