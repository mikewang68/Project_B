#!/usr/bin/env bash
set -euo pipefail

EHM_HOME="${EHM_HOME:-/opt/b-project/ehm}"
PID_FILE="${EHM_HOME}/run/ehm-service.pid"
[[ -f "${PID_FILE}" ]] || { echo "EHM is not running"; exit 0; }

PID="$(cat "${PID_FILE}")"
if ! [[ "${PID}" =~ ^[0-9]+$ ]]; then
  echo "Invalid pid file: ${PID_FILE}" >&2
  exit 1
fi
if ! kill -0 "${PID}" 2>/dev/null; then
  rm -f "${PID_FILE}"
  echo "Stale pid file removed"
  exit 0
fi
if [[ -r "/proc/${PID}/cmdline" ]] && ! tr '\0' ' ' < "/proc/${PID}/cmdline" | grep -q 'ehm-service.jar'; then
  echo "PID ${PID} is not EHM; refusing to stop it" >&2
  exit 1
fi

kill "${PID}"
for _ in {1..30}; do
  kill -0 "${PID}" 2>/dev/null || break
  sleep 1
done
if kill -0 "${PID}" 2>/dev/null; then
  echo "EHM did not stop within 30 seconds; administrator review is required" >&2
  exit 1
fi
rm -f "${PID_FILE}"
echo "EHM stopped"
