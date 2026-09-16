#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
export IPFS_PATH="$ROOT/runtime/ipfs"
IPFS="$ROOT/tools/kubo/ipfs"
wait_for_ipfs() {
  python3 - "$IPFS_PATH" <<'PY'
import json,sys,time,urllib.request
deadline=time.monotonic()+30
while time.monotonic()<deadline:
    try:
        req=urllib.request.Request('http://127.0.0.1:5001/api/v0/repo/stat',data=b'')
        with urllib.request.urlopen(req,timeout=2) as response:
            if json.load(response)['RepoPath']==sys.argv[1]:
                print('IPFS ready; repository identity verified');break
    except (OSError,ValueError,KeyError):pass
    time.sleep(.25)
else:raise SystemExit('IPFS not ready after 30 seconds; inspect project logs')
PY
}
case "${1:-status}" in
  install)
    python3 "$ROOT/deploy/download.py" ipfs
    tar -xzf "$ROOT/artifacts/kubo_v0.43.0_linux-amd64.tar.gz" -C "$ROOT/tools"
    if [[ ! -f "$IPFS_PATH/config" ]]; then
      "$IPFS" init --profile=server
      "$IPFS" bootstrap rm --all
      "$IPFS" config Addresses.API /ip4/127.0.0.1/tcp/5001
      "$IPFS" config Addresses.Gateway /ip4/127.0.0.1/tcp/8080
      "$IPFS" config --json Addresses.Swarm '[]'
      "$IPFS" config --json Discovery.MDNS.Enabled false
      "$IPFS" config Datastore.StorageMax 10GiB
      python3 "$ROOT/deploy/configure-ipfs-offline.py" "$IPFS_PATH"
    fi
    ;;
  configure-offline) python3 "$ROOT/deploy/configure-ipfs-offline.py" "$IPFS_PATH" ;;
  start) start_process ipfs "$IPFS" daemon --offline; wait_for_ipfs ;;
  stop) stop_process ipfs ;;
  status) "$IPFS" version; curl -fsS -X POST http://127.0.0.1:5001/api/v0/repo/stat ;;
  pins) "$IPFS" pin ls --type=recursive ;;
  backup)
    was_running=false
    if curl --connect-timeout 1 --max-time 2 -fsS -X POST http://127.0.0.1:5001/api/v0/repo/stat >/dev/null 2>&1; then was_running=true; fi
    stop_process ipfs
    trap 'if $was_running; then start_process ipfs "$IPFS" daemon --offline; wait_for_ipfs; fi' EXIT
    tar -czf "$ROOT/runtime/backups/ipfs-$(date -u +%Y%m%dT%H%M%SZ).tar.gz" -C "$ROOT/runtime" ipfs
    ;;
  *) echo 'Usage: ipfs.sh install|configure-offline|start|stop|status|pins|backup'; exit 2 ;;
esac
