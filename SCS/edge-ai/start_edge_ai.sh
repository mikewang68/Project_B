#!/usr/bin/env bash
set -e

echo "========================================================"
echo " SCS Edge AI Vision Inference Service Launcher (Linux)"
echo "========================================================"

export AI_STORAGE_BASE="${AI_STORAGE_BASE:-/home/jingchanglong/Project_B/SCS/edge-ai/storage}"
export SCS_BACKEND_URL="${SCS_BACKEND_URL:-http://127.0.0.1:18080}"

mkdir -p "${AI_STORAGE_BASE}/models" "${AI_STORAGE_BASE}/temp/snapshots"

cd "$(dirname "$0")"

PYTHON_BIN="python3"
if [ -f "./venv/bin/python3" ]; then
    PYTHON_BIN="./venv/bin/python3"
fi

echo "[INFO] Central SCS Backend: ${SCS_BACKEND_URL}"
echo "[INFO] Storage Base: ${AI_STORAGE_BASE}"
echo "[INFO] Using Python: ${PYTHON_BIN}"
echo "[INFO] Starting FastAPI service on port 18090..."

exec ${PYTHON_BIN} -m uvicorn main:app --host 0.0.0.0 --port 18090
