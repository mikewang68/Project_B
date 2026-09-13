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
