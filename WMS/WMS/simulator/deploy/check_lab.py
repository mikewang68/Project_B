#!/usr/bin/env python3
"""Read-only verification of API receipts and daemon outbox. Never prints secrets."""
import argparse
from decimal import Decimal
import json
import os
from pathlib import Path
import sqlite3
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import simulator as sim


def check(config, credentials):
    cfg = sim.load_config(Path(config))
    for line in Path(credentials).read_text(encoding="utf-8").splitlines():
        key, _, value = line.partition("=")
        if key in ("WMS_SIM_USERNAME", "WMS_SIM_PASSWORD"):
            os.environ[key] = value
    client = sim.NativeWms(cfg, lab_enabled=True)
    client.scope()
    account = client.call("GET", "/api/v1/auth/me")
    if set(account.get("permissions", [])) != {"stockin:read", "stockin:write", "master:read"}:
        raise sim.SimError("模拟账号权限不符合最小约定")
    forbidden = {}
    for path in ("/api/v1/system/users", "/api/v1/stockout", "/api/v1/inventory/balances"):
        try:
            client.call("GET", path)
        except sim.WmsHttpError as exc:
            forbidden[path] = exc.status
        else:
            raise sim.SimError("模拟账号获得非必要权限")
        if forbidden[path] != 403:
            raise sim.SimError("受限接口未按预期拒绝")
    results = []
    for index, order in enumerate([cfg["order"], *cfg.get("additional_orders", [])]):
        actual = client.call("GET", f"/api/v1/stockin/{order['id']}")
        receipts = client.call("GET", f"/api/v1/stockin/{order['id']}/receipts")
        root = Path(cfg["state_dir"]) if index == 0 else Path(cfg["state_dir"]) / "tasks" / sim.task_id(order)
        counts = {}
        if (root / "handheld.sqlite3").exists():
            with sqlite3.connect((root / "handheld.sqlite3").as_uri() + "?mode=ro", uri=True) as db:
                counts = dict(db.execute("SELECT status,count(*) FROM jobs GROUP BY status"))
        results.append({"order_id": order["id"], "external_order": actual["externalOrderCode"], "material": order["good_name"],
            "good_code": order["good_code"], "unit": order["unit"], "state": actual["state"], "planned": actual["plannedQty"],
            "received": actual["receivedQty"], "receipt_count": len(receipts),
            "receipt_sum": sim.json_quantity(sum((Decimal(str(r["quantity"])) for r in receipts), Decimal(0))),
            "serial_count": sum(r["serialCount"] for r in receipts), "local_jobs": counts})
    return {"mode": "authorized_lab", "account_id": account["id"], "permissions": sorted(account["permissions"]), "forbidden_reads": forbidden, "tasks": results}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("--credentials", required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(check(args.config, args.credentials), ensure_ascii=False))
    except Exception as exc:
        print("CHECK_FAILED " + (str(exc) if isinstance(exc, sim.SimError) else type(exc).__name__), file=sys.stderr)
        raise SystemExit(2)
