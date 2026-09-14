#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
cid=${1:?CID required}
[[ "$cid" =~ ^[A-Za-z0-9]{20,120}$ ]] || exit 2
export IPFS_PATH="$ROOT/runtime/ipfs"
IPFS="$ROOT/tools/kubo/ipfs"
original_sha=$("$IPFS" cat "$cid" | sha256sum | cut -d' ' -f1)
bash "$ROOT/deploy/ipfs.sh" backup
backup=$(ls -1t "$ROOT/runtime/backups"/ipfs-*.tar.gz | head -1)
restore="$ROOT/runtime/ipfs-restore-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$restore"
tar -xzf "$backup" -C "$restore"
export IPFS_PATH="$restore/ipfs"
"$IPFS" config Addresses.API /ip4/127.0.0.1/tcp/15001
"$IPFS" config Addresses.Gateway /ip4/127.0.0.1/tcp/18080
start_process ipfs-restore "$IPFS" daemon --offline
trap 'stop_process ipfs-restore' EXIT
python3 - <<'PY'
import urllib.request,time
for i in range(50):
 try:
  urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:15001/api/v0/version',data=b''),timeout=1);break
 except Exception:time.sleep(.2)
else:raise SystemExit('Restore daemon did not become ready')
PY
restored_sha=$("$IPFS" cat "$cid" | sha256sum | cut -d' ' -f1)
[[ "$restored_sha" == "$original_sha" ]]
"$IPFS" pin ls --type=recursive | grep -q "$cid"
echo "PASS: independent IPFS backup restored; CID and SHA-256 unchanged: $cid $original_sha"
echo "BACKUP: $backup"
stop_process ipfs-restore
trap - EXIT
python3 - "$restore" "$ROOT" <<'PY'
import pathlib,shutil,sys
p=pathlib.Path(sys.argv[1]).resolve();r=pathlib.Path(sys.argv[2]).resolve()
assert p.parent==r/'runtime' and p.name.startswith('ipfs-restore-')
shutil.rmtree(p)
PY
