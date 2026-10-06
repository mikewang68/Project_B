"""
Triggers AI violation simulation scenarios and inspects SCS backend response.
"""
import json
import urllib.request

url = "http://127.0.0.1:18090/api/v1/simulate/trigger"

for scenario in ["no_helmet", "intrusion"]:
    payload = {
        "scenario": scenario,
        "camera_code": "CAM-01",
        "report_to_scs": True,
        "custom_person_name": "张三 (现场架子工)"
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req) as resp:
        res = json.loads(resp.read().decode("utf-8"))
        print("========================================")
        print(f"SCENARIO: {scenario}")
        print("Status:", res.get("status"))
        print("Detected Violations:", [v.get("type") for v in res.get("violations", [])])
        print("Snapshot Path:", res.get("snapshot_path"))
        print("SCS Ingestion Response:")
        for r in res.get("scs_reports", []):
            print(f"  -> Generated SCS Event ID: {r.get('id')} | Status: {r.get('status')} | Risk: {r.get('risk')}")
print("========================================")
