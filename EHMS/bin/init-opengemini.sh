#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${EHM_OPENGEMINI_BASE_URL:-http://node6.example.internal:8086}"
DATABASE="${EHM_OPENGEMINI_DATABASE:-ehm_telemetry}"
AUTH_ARGS=()

if [[ -n "${EHM_OPENGEMINI_USERNAME:-}" ]]; then
  AUTH_ARGS=(-u "${EHM_OPENGEMINI_USERNAME}:${EHM_OPENGEMINI_PASSWORD:-}")
fi

curl -fsS "${AUTH_ARGS[@]}" -o /dev/null "${BASE_URL%/}/ping"
curl -fsS "${AUTH_ARGS[@]}" -G "${BASE_URL%/}/query" \
  --data-urlencode "q=CREATE DATABASE \"${DATABASE}\""
curl -fsS "${AUTH_ARGS[@]}" -G "${BASE_URL%/}/query" \
  --data-urlencode "q=SHOW DATABASES"

echo "openGemini database is ready: ${DATABASE}"
