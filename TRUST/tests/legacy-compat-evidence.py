"""Prepare real IPFS legacy fixtures, then verify unchanged Fabric receipts after upgrade.

Run on the isolated app host. Fabric submission/upgrade is deliberately a separate explicit
step using wallet-channel.sh and the protected fabric-sample tool. This script never writes
to a Fabric channel or a database.
"""
import argparse
import hashlib
import json
import pathlib
import urllib.parse
import urllib.request
import uuid


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def digest(value):
    return hashlib.sha256(value).hexdigest()


def rpc(api, operation, params, body=b"", headers=None):
    request = urllib.request.Request(api + "/api/v0/" + operation + "?" + urllib.parse.urlencode(params),
                                     data=body, headers=headers or {}, method="POST")
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def save(directory, name, value):
    path = directory / name
    if path.exists():
        raise RuntimeError("Refusing to overwrite existing evidence: " + name)
    path.write_bytes(encoded(value) + b"\n")


def check_ipfs(directory, api, name, record):
    manifest = rpc(api, "cat", {"arg": record["manifestCid"]})
    assert manifest == (directory / (name + ".manifest.json")).read_bytes()
    assert digest(manifest) == record["manifestSha256"]
    content = json.loads(manifest)
    canonical = encoded(content["event"])
    assert digest(canonical) == content["eventSha256"] == record["eventSha256"]
    assert content["event"]["id"] == record["id"]
    pin = json.loads(rpc(api, "pin/ls", {"arg": record["manifestCid"], "type": "recursive"}))
    assert pin["Keys"][record["manifestCid"]]["Type"] == "recursive"
    return {"id": record["id"], "cid": record["manifestCid"], "manifestSha256": digest(manifest),
            "eventSha256": digest(canonical), "pin": "recursive", "passed": True}


def prepare(directory, api):
    root = str(uuid.uuid4())
    for name, event_id, version, previous in [("root", root, 1, ""),
                                               ("correction", str(uuid.uuid4()), 2, root)]:
        if (directory / (name + ".record.json")).exists():
            raise RuntimeError("Use an empty evidence directory")
        canonical = {"schemaVersion": 1, "id": event_id, "orgId": "B-PROJECT", "rootId": root,
                     "version": version, "supersedesId": previous, "submittedBy": "legacy-compat-verifier",
                     "event": {"sourceSystem": "LEGACY-COMPAT", "sourceEventId": event_id,
                               "eventType": "LEGACY_SAMPLE", "quantity": 100 if version == 1 else 99},
                     "evidence": []}
        event_hash = digest(encoded(canonical))
        manifest = encoded({"schemaVersion": 1, "event": canonical, "eventSha256": event_hash, "evidence": []})
        boundary = "trust-compat-" + uuid.uuid4().hex
        body = ("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="manifest.json"\r\n'
                "Content-Type: application/json\r\n\r\n").encode() + manifest + ("\r\n--" + boundary + "--\r\n").encode()
        added = json.loads(rpc(api, "add", {"pin": "true"}, body,
                               {"Content-Type": "multipart/form-data; boundary=" + boundary}))
        record = {"id": event_id, "orgId": "B-PROJECT", "rootId": root, "version": version,
                  "supersedesId": previous, "eventSha256": event_hash, "manifestCid": added["Hash"],
                  "manifestSha256": digest(manifest), "submittedBy": "legacy-compat-verifier"}
        (directory / (name + ".manifest.json")).write_bytes(manifest)
        save(directory, name + ".record.json", record)
        save(directory, name + ".ipfs-before.json", check_ipfs(directory, api, name, record))


def verify(directory, api):
    findings = []
    originals = []
    for name in ["root", "correction"]:
        submitted = json.loads((directory / (name + ".record.json")).read_bytes())
        receipt = json.loads((directory / (name + ".receipt.json")).read_bytes())
        before = receipt["record"]
        after = json.loads((directory / (name + ".after.json")).read_bytes())
        assert before == after, "An original legacy record changed after upgrade"
        assert all(before[key] == value for key, value in submitted.items())
        assert before["txId"] == receipt["txId"] and before["ledgerIdentity"]
        originals.append(before)
        findings.append(dict(check_ipfs(directory, api, name, after), txId=before["txId"],
                             exactRecordMatch=True, ledgerIdentityMatch=True))
    history = json.loads((directory / "history.after.json").read_bytes())
    assert history == originals, "History does not match both original legacy receipts"
    save(directory, "verification.json", {"records": findings, "historyExactMatch": True, "passed": True})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("prepare", "verify"))
    parser.add_argument("--directory", required=True, type=pathlib.Path)
    parser.add_argument("--ipfs", default="http://127.0.0.1:25001")
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    (prepare if args.phase == "prepare" else verify)(args.directory, args.ipfs)
    print(args.phase + ": PASS")
