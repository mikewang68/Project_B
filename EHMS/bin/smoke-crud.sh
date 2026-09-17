#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${EHM_BASE_URL:-http://127.0.0.1:18083}"
API="${BASE_URL}/api/ehm/v1"
CODE="QA-$(date +%m%d%H%M%S)"
COMPONENT="${CODE}-MOTOR"
POINT="${CODE}-TEMP"

cleanup() {
  curl -fsS -X DELETE "${API}/measurement-points/${POINT}" >/dev/null 2>&1 || true
  curl -fsS -X DELETE "${API}/components/${COMPONENT}" >/dev/null 2>&1 || true
  curl -fsS -X DELETE "${API}/devices/${CODE}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "[1/9] 检查联合就绪"
curl -fsS "${BASE_URL}/health/ready"
echo

echo "[2/9] 新建设备 ${CODE}"
curl -fsS -X POST "${API}/devices" -H 'Content-Type: application/json' -d "{
  \"code\":\"${CODE}\",\"name\":\"接口验收临时设备\",\"type\":\"测试设备\",
  \"area\":\"验收区\",\"condition\":\"待接入\",\"risk\":\"待评估\",
  \"riskClass\":\"limited\",\"ready\":\"待接入\",\"alarm\":\"无活动告警\",
  \"maintenanceDate\":\"2026-12-31\",\"owner\":\"验收组\"
}" >/dev/null

echo "[3/9] 查询设备"
curl -fsS "${API}/devices/${CODE}" >/dev/null

echo "[4/9] 编辑设备"
curl -fsS -X PUT "${API}/devices/${CODE}" -H 'Content-Type: application/json' -d "{
  \"code\":\"${CODE}\",\"name\":\"接口验收临时设备-已修改\",\"type\":\"测试设备\",
  \"area\":\"验收区\",\"condition\":\"运行\",\"health\":88,\"quality\":99.5,
  \"risk\":\"正常\",\"riskClass\":\"good\",\"ready\":\"已接入\",
  \"alarm\":\"无活动告警\",\"maintenanceDate\":\"2026-12-31\",\"owner\":\"验收组\"
}" >/dev/null

echo "[5/9] 新建BOM部件"
curl -fsS -X POST "${API}/assets/${CODE}/components" -H 'Content-Type: application/json' -d "{
  \"code\":\"${COMPONENT}\",\"name\":\"验收电机\",\"category\":\"电气部件\",
  \"criticality\":\"B类\",\"position\":\"测试位\",\"status\":\"在役\"
}" >/dev/null

echo "[6/9] 新建测点"
curl -fsS -X POST "${API}/assets/${CODE}/measurement-points" -H 'Content-Type: application/json' -d "{
  \"code\":\"${POINT}\",\"componentCode\":\"${COMPONENT}\",\"name\":\"验收温度\",
  \"metric\":\"温度\",\"unit\":\"℃\",\"sourceProtocol\":\"OPC UA\",
  \"sourceAddress\":\"qa://temperature\",\"sampleIntervalSeconds\":5,
  \"lowerLimit\":-20,\"upperLimit\":120,\"enabled\":true
}" >/dev/null

echo "[7/9] 写入时序样本并触发质量判定"
NOW_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
curl -fsS -X POST "${API}/data-quality/points/${POINT}/sample" -H 'Content-Type: application/json' -d "{
  \"value\":36.8,\"sourceTimestamp\":\"${NOW_UTC}\"
}" >/dev/null

echo "[8/9] 查询BOM、测点和质量汇总"
curl -fsS "${API}/assets/${CODE}/components?page=0&size=20" >/dev/null
curl -fsS "${API}/assets/${CODE}/measurement-points?page=0&size=20" >/dev/null
curl -fsS "${API}/data-quality/summary?assetCode=${CODE}" >/dev/null

echo "[9/9] 依次归档测点、部件和设备"
cleanup
trap - EXIT
echo "EHM CRUD_SMOKE_OK：openGauss业务CRUD和openGemini样本链路已通过。"
