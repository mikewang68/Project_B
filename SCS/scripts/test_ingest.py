import json
import urllib.request

url = "http://127.0.0.1:18080/api/v1/ai-events/ingest"
payload = {
    "eventType": "未佩戴安全帽",
    "cameraCode": "CAM-01",
    "cameraName": "一号基坑全景球机",
    "areaCode": "AREA-A",
    "confidence": 97.5,
    "threshold": 80.0,
    "durationSec": 1.2,
    "modelCode": "YOLO-PPE-V8",
    "riskCode": "RISK-01",
    "sceneType": "基坑作业面",
    "ruleCode": "RULE-PPE-HELMET",
    "judgeText": "作业人员进入基坑未佩戴符合标准的安全帽",
    "snapshotUrl": "/snapshots/ai_sample_helmet.jpg",
    "boxes": [{"x": 120, "y": 150, "w": 50, "h": 80, "label": "no_helmet", "confidence": 0.975}],
    "relatedPerson": "李四 (焊工)",
    "relatedDevice": "起重机周边"
}

data = json.dumps(payload).encode("utf-8")
req = urllib.request.Request(
    url,
    data=data,
    headers={
        "Content-Type": "application/json",
        "Idempotency-Key": "test-ai-live-1"
    }
)

try:
    with urllib.request.urlopen(req) as resp:
        print("HTTP_STATUS:", resp.status)
        print("RESPONSE:", resp.read().decode("utf-8"))
except urllib.error.HTTPError as e:
    print("HTTP_ERROR:", e.code)
    print("ERROR_BODY:", e.read().decode("utf-8"))
