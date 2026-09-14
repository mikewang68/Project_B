#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
load_deployment
child=''
trap '[[ -z "$child" ]] || kill "$child" 2>/dev/null; exit 0' TERM INT
while true; do
 ssh -N -T -i "$ROOT/runtime/secrets/tunnel_ed25519" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$ROOT/runtime/secrets/known_hosts" -o ExitOnForwardFailure=yes -o ConnectTimeout=8 -o ServerAliveInterval=10 -o ServerAliveCountMax=3 -L "127.0.0.1:$1:127.0.0.1:$3" "$TRUST_SSH_USER@$2" & child=$!
 wait "$child" || true
 child=''; sleep 3
done
