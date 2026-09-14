#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
load_deployment
case "${1:-status}" in
 start)
  for entry in "ipfs 25001 $TRUST_IPFS_ADDRESS 5001" "database 25432 $TRUST_DATABASE_ADDRESS 25432" "fabric 27051 $TRUST_FABRIC_ADDRESS 27051"; do
    read -r name localport address remoteport <<<"$entry"
    start_process "tunnel-$name" bash "$ROOT/deploy/tunnel-loop.sh" "$localport" "$address" "$remoteport"
  done ;;
 stop) for n in ipfs database fabric; do stop_process "tunnel-$n"; done ;;
 status) ss -lnt | grep -E ':(25001|25432|27051) ' ;;
 *) exit 2 ;;
esac
