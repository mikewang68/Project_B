#!/usr/bin/env bash
set -euo pipefail

PORT="${EHM_SERVER_PORT:-18083}"
BASE_URL="${EHM_LOCAL_BASE_URL:-http://127.0.0.1:${PORT}}"

echo "== liveness =="
curl -fsS "${BASE_URL}/health/live"
echo
echo "== readiness (openGauss + openGemini) =="
curl -fsS "${BASE_URL}/health/ready"
echo
echo "== adapters =="
curl -fsS "${BASE_URL}/api/ehm/v1/system/status"
echo
echo "EHM server checks passed"
