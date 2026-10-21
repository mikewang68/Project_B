"""Generate private per-app credentials. Never prints tokens or overwrites existing secrets."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets

parser = argparse.ArgumentParser()
parser.add_argument("--directory", default="runtime/secrets")
parser.add_argument("--domain", required=True)
parser.add_argument("--zone", required=True)
args = parser.parse_args()
if not all(re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", v) for v in (args.domain, args.zone)):
    parser.error("domain and zone must use the agreed identifier format")
target = Path(args.directory)
target.mkdir(parents=True, exist_ok=True)
clients_file = target / "clients.json"
if clients_file.exists():
    parser.error("clients.json exists; rotate credentials explicitly, not by overwriting")
os.umask(0o077)
clients = []
for app in ("ehealth", "iam", "sys", "pps", "wms", "ems", "safety", "dt"):
    token = secrets.token_urlsafe(48)
    (target / (app + ".token")).write_text(token, encoding="utf-8")
    clients.append(dict(id=app + "-producer", token=token, appCode=app, domainId=args.domain,
                        zoneId=args.zone, permissions=["WRITE", "READ"]))
token = secrets.token_urlsafe(48)
(target / "reader.token").write_text(token, encoding="utf-8")
clients.append(dict(id="audit-reader", token=token, appCode="*", domainId=args.domain,
                    zoneId=args.zone, permissions=["READ", "EXPORT"]))
clients_file.write_text(json.dumps(clients, ensure_ascii=False, indent=2), encoding="utf-8")
print("Private clients configuration created. Tokens are in separate files; none were printed.")
