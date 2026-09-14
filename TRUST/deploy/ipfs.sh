#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
export IPFS_PATH="$ROOT/runtime/ipfs"
IPFS="$ROOT/tools/kubo/ipfs"
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
    fi
    ;;
  start) start_process ipfs "$IPFS" daemon --offline ;;
  stop) stop_process ipfs ;;
  status) "$IPFS" version; curl -fsS -X POST http://127.0.0.1:5001/api/v0/repo/stat ;;
  pins) "$IPFS" pin ls --type=recursive ;;
  backup)
    stop_process ipfs
    trap 'start_process ipfs "$IPFS" daemon --offline' EXIT
    tar -czf "$ROOT/runtime/backups/ipfs-$(date -u +%Y%m%dT%H%M%SZ).tar.gz" -C "$ROOT/runtime" ipfs
    ;;
  *) echo 'Usage: ipfs.sh install|start|stop|status|pins|backup'; exit 2 ;;
esac
