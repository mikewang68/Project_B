#!/usr/bin/env python3
"""Explicit one-time lab bootstrap. Admin credentials enter via stdin, never argv/files."""
import argparse
import copy
from decimal import Decimal
import json
import os
from pathlib import Path
import re
import secrets
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import simulator as sim


def prepare(args, credentials):
    if not args.authorize_lab_writes or not re.fullmatch(r"SIM-[A-Za-z0-9_-]{1,35}", args.batch):
        raise sim.SimError("须明确批准实验室写入，并提供安全 SIM- 批次")
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    config_file, secret_file = output / "config.local.json", output / "credentials.env"
    if config_file.exists() or secret_file.exists():
        raise sim.SimError("已存在配置或凭据；不覆盖、不重置账号。先核对已有批次")
    cfg = sim.load_config(Path(__file__).resolve().parents[1] / "config.example.json")
    cfg["mode"] = "wms"
    cfg["state_dir"] = args.state_dir
    cfg["target"].update(environment="authorized_lab", write_enabled=True,
        base_url=args.base_url.rstrip("/"), approved_base_url=args.base_url.rstrip("/"),
        ca_file=args.ca_file, company_code=args.company, warehouse_code=args.warehouse, owner_code=args.owner,
        lab_write_ack="WRITE_SIM_DATA_TO_LAB_WMS", username_env="WMS_SETUP_USERNAME", password_env="WMS_SETUP_PASSWORD")
    auth_cfg = copy.deepcopy(cfg)
    auth_cfg.pop("additional_orders", None)
    auth_cfg["order"]["lab_approval_sha256"] = sim.lab_plan_digest(auth_cfg)
    os.environ["WMS_SETUP_USERNAME"] = credentials["username"]
    os.environ["WMS_SETUP_PASSWORD"] = credentials["password"]
    client = sim.NativeWms(auth_cfg, lab_enabled=True)
    client.scope()

    def write(path, body):
        csrf = client.call("GET", "/api/v1/auth/csrf")
        # Never auto-retry setup POSTs: an uncertain result requires API inspection.
        return client.call("POST", path, body, {csrf["headerName"]: csrf["token"]})

    def ensure(resource, item):
        rows = client.call("GET", "/api/v1/master-data/" + resource)
        found = [r for r in rows if r.get("code") == item["code"]]
        if found:
            if len(found) != 1 or found[0].get("status") != "ENABLED" or (resource == "goods" and found[0].get("unit") != item["unit"]):
                raise sim.SimError("已有模拟主数据冲突；不覆盖")
            return found[0]
        return write("/api/v1/master-data/" + resource, {"status": "ENABLED", "remark": args.batch + " 模拟采集，非真实货运", **item})

    ensure("categories", {"code": "SIM-PDA-MATERIAL", "name": "模拟终端物料"})
    ensure("areas", {"code": "SIM-PDA-STORAGE", "name": "模拟采集存储区", "type": "STORAGE"})
    ensure("work-areas", {"code": "SIM-PDA-RECEIVE", "name": "模拟采集收货区", "type": "NORMAL"})
    ensure("partners", {"code": "SIM-PDA-SUPPLIER", "name": "模拟采集供应商（虚构）", "type": "SUPPLIER"})
    orders = [cfg["order"], *cfg.get("additional_orders", [])]
    summaries = client.call("GET", "/api/v1/stockin")
    for order in orders:
        ensure("goods", {"code": order["good_code"], "name": "模拟采集·" + order["good_name"], "unit": order["unit"],
               "type": "ZC", "parentCode": "SIM-PDA-MATERIAL", "specification": "模拟数据，非真实货运", "price": 0})
        ensure("locations", {"code": order["location_code"], "name": order["location_code"], "type": "L3",
               "parentCode": "SIM-PDA-STORAGE", "secondaryCode": "SIM-PDA-RECEIVE", "quantity": 100000})
        external = args.batch + "-" + order["good_code"].removeprefix("SIM-")
        quantity = (sum((Decimal(str(w["gross"])) - Decimal(str(w["tare"])) for w in order["weighings"].values()), Decimal(0))
                    if sim.measurement_mode(order) == "weight" else Decimal(len(order["tags"])))
        existing = [s for s in summaries if s.get("externalOrderCode") == external]
        if len(existing) > 1:
            raise sim.SimError("模拟单号不唯一，需要人工核对")
        if existing:
            actual = client.call("GET", f"/api/v1/stockin/{existing[0]['id']}")
        else:
            actual = write("/api/v1/stockin", {"inboundType": "PURCHASE", "source": "CUSTOM", "externalOrderCode": external,
                "partnerCode": "SIM-PDA-SUPPLIER", "remark": args.batch + " 模拟手持终端 / 称重，非真实货运", "idempotencyKey": external,
                "lines": [{"goodCode": order["good_code"], "plannedQty": sim.json_quantity(quantity),
                    "preferredLocationCode": order["location_code"], "supplierCode": "SIM-PDA-SUPPLIER", "qualityType": "ZP",
                    "batchCode": external, "unitPrice": 0, "remark": "模拟采集有限计划"}]})
        lines = actual.get("lines", [])
        if len(lines) != 1 or lines[0].get("goodCode") != order["good_code"] or Decimal(str(lines[0].get("plannedQty"))) != quantity or Decimal(str(lines[0].get("receivedQty"))) != 0 or actual.get("state") not in ("DRAFT", "RECEIVING"):
            raise sim.SimError("已有订单明细 / 数量 / 状态不符或已收货，不建立新队列")
        old_tags = order["tags"]
        order["tags"] = [args.batch + "-" + e.removeprefix("SIM-") for e in old_tags]
        if "weighings" in order:
            order["weighings"] = {new: order["weighings"][old] for old, new in zip(old_tags, order["tags"]) if old in order["weighings"]}
        order.update(id=actual["id"], line_id=lines[0]["id"], external_order_code=external, batch_code=external)

    username = "sim_pda_" + args.batch.removeprefix("SIM-").lower().replace("-", "_")
    if any(u.get("username") == username for u in client.call("GET", "/api/v1/system/users")):
        raise sim.SimError("模拟账号已存在；不重置密码，请恢复受限配置后核对")
    password = secrets.token_urlsafe(32)
    # Persist generated secret before the uncertain user-creation request. Reruns never overwrite it.
    with secret_file.open("x", encoding="utf-8") as f:
        os.chmod(secret_file, 0o600)
        f.write(f"WMS_SIM_USERNAME={username}\nWMS_SIM_PASSWORD={password}\n")
    account = write("/api/v1/system/users", {"username": username, "displayName": "模拟终端收货专用", "password": password,
        "roleCodes": ["SIMULATOR_RECEIVE"], "warehouseCodes": [args.warehouse], "ownerCodes": [args.owner]})
    cfg["target"].update(username_env="WMS_SIM_USERNAME", password_env="WMS_SIM_PASSWORD")
    for order in orders:
        task_cfg = {**cfg, "order": order}
        order["lab_approval_sha256"] = sim.lab_plan_digest(task_cfg)
    sim.validate_config(cfg)
    for order in orders:
        sim.validate_target({**cfg, "order": order}, False, True)
    with config_file.open("x", encoding="utf-8") as f:
        os.chmod(config_file, 0o600)
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    print(json.dumps({"prepared": True, "account_id": account["id"], "tasks": [
        {"id": o["id"], "code": o["external_order_code"], "good": o["good_name"], "unit": o["unit"], "labels": len(o["tags"])} for o in orders]}, ensure_ascii=False))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--authorize-lab-writes", action="store_true")
    for arg in ("batch", "base-url", "ca-file", "output", "state-dir", "company", "warehouse", "owner"):
        p.add_argument("--" + arg, required=True)
    args = p.parse_args()
    try:
        # sudo may be NOPASSWD/cached and leave its supplied line unread.
        # Accept only the JSON envelope; never log ignored lines or credentials.
        credentials = None
        for _ in range(4):
            line = sys.stdin.readline(8192).lstrip("\ufeff \r\n")
            if line.startswith("{"):
                credentials = json.loads(line)
                break
        if not isinstance(credentials, dict) or not credentials.get("username") or not credentials.get("password"):
            raise sim.SimError("未收到安全 stdin 身份封装")
        prepare(args, credentials)
        return 0
    except Exception as exc:
        detail = str(exc) if isinstance(exc, sim.SimError) else type(exc).__name__
        print("LAB_SETUP_FAILED：" + detail + "；不自动重试写请求、不覆盖已有凭据。", file=sys.stderr)
        return 2
    finally:
        os.environ.pop("WMS_SETUP_USERNAME", None)
        os.environ.pop("WMS_SETUP_PASSWORD", None)


if __name__ == "__main__":
    raise SystemExit(main())
