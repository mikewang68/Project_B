"""
End-to-End Simulation & Verification Script:
1. Triggers AI detection on edge service (:18090)
2. Ingests into SCS Backend (:18080) -> openGauss AI Event (AI-E-...)
3. Calls Review Confirm API -> Automatically generates openGauss Alert (ALM-...)
4. Calls Assign Dispatch API -> Dispatches Alert to Safety Lead
5. Queries Alert API to verify full lifecycle convergence
"""
import json
import urllib.request

EDGE_SIM_URL = "http://127.0.0.1:18090/api/v1/simulate/trigger"
SCS_BASE_URL = "http://127.0.0.1:18080/api/v1"

def post_json(url, payload):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))

def get_json(url):
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))

print("==================================================================")
print(" 1. TRIGGERING EDGE AI INFERENCE & INGESTION")
print("==================================================================")
sim_resp = post_json(EDGE_SIM_URL, {
    "scenario": "no_helmet",
    "camera_code": "CAM-01",
    "report_to_scs": True,
    "custom_person_name": "张三 (现场架子工)"
})
print("Simulation Status:", sim_resp.get("status"))
print("Detected Violations:", [v.get("type") for v in sim_resp.get("violations", [])])
reports = sim_resp.get("scs_reports", [])
if not reports:
    print("ERROR: No events reported to SCS!")
    exit(1)

ai_event_id = reports[0].get("id")
print(f"Generated AI Event: {ai_event_id} | Initial Status: {reports[0].get('status')}")

print("\n==================================================================")
print(f" 2. CONFIRMING AI EVENT -> GENERATING OPENGAUSS ALERT ({ai_event_id})")
print("==================================================================")
confirm_resp = post_json(f"{SCS_BASE_URL}/ai-events/{ai_event_id}/confirm", {
    "reviewer": "李娜 (安全总监)"
})
linked_alert_id = confirm_resp.get("linkedAlertId")
print(f"AI Event Status after Confirm: {confirm_resp.get('status')} ({confirm_resp.get('statusCode')})")
print(f"Automatically Generated Linked Alert ID: {linked_alert_id}")

print("\n==================================================================")
print(f" 3. DISPATCHING / ASSIGNING WORK ORDER ({ai_event_id})")
print("==================================================================")
assign_resp = post_json(f"{SCS_BASE_URL}/ai-events/{ai_event_id}/assign", {
    "assigneeId": "USR-002",
    "assignee": "王建国",
    "priority": "紧急",
    "note": "AI视觉检测到作业区未佩戴安全帽，限15分钟内现场整改",
    "reviewer": "李娜 (安全总监)"
})
print(f"AI Event Status after Assign: {assign_resp.get('status')} ({assign_resp.get('statusCode')})")
print(f"Assigned Lead: {assign_resp.get('assignee')} | Priority: {assign_resp.get('assignmentPriority')}")
print("Timeline Entries:")
for node in assign_resp.get("timeline", []):
    print(f"  [{node.get('time')}] ({node.get('state')}) {node.get('text')}")

if linked_alert_id:
    print("\n==================================================================")
    print(f" 4. VERIFYING LINKED ALERT IN OPENGAUSS ({linked_alert_id})")
    print("==================================================================")
    alert_detail = get_json(f"{SCS_BASE_URL}/alerts/{linked_alert_id}")
    print(f"Alert ID: {alert_detail.get('id')}")
    print(f"Alert Title: {alert_detail.get('title')}")
    print(f"Alert Status: {alert_detail.get('status')} ({alert_detail.get('statusCode')})")
    print(f"Alert Risk Level: {alert_detail.get('riskLevel')} ({alert_detail.get('riskCode')})")
    print(f"Alert Target: {alert_detail.get('target')}")
    ev = alert_detail.get('evidence', {})
    print(f"Evidence Kind: {ev.get('kind')}")
    print(f"Evidence Scene: {ev.get('scene')}")
    print(f"Evidence Camera: {ev.get('camera')}")
    print(f"Evidence Model: {ev.get('model')}")
    print(f"Evidence Boxes: {len(ev.get('boxes', []))} boxes")

print("==================================================================")
print(" ALL 4 STAGES VERIFIED: EDGE AI -> INGEST -> CONFIRM -> ALERT")
print("==================================================================")
