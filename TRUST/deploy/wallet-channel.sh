#!/usr/bin/env bash
# Writes require an isolated channel. Loading/status never initialize runtime directories.
# Historical 2026-09-24 v1 setup shared legacy CCAAS; this corrected script never does so.
set -euo pipefail
cmd=${1:-}; ch=${2:-}
[[ $# -ge 2 && $# -le 3 ]] || { echo "usage: $0 create|deploy-v1|upgrade-v2|upgrade-v21|status <trust-channel> [base-port]" >&2; exit 2; }
case "$cmd" in create|deploy-v1|upgrade-v2|upgrade-v21|status) ;; *) echo "Unsupported command" >&2; exit 2;; esac
[[ "$ch" =~ ^trust-[a-z0-9][a-z0-9-]{0,59}$ ]] || { echo "Refusing protected/invalid channel: $ch" >&2; exit 2; }
base=${3:-27259}; [[ "$cmd" != deploy-v1 || $# == 3 ]] || base=27459
[[ "$cmd" != upgrade-v21 || $# == 3 ]] || base=27659
[[ "$base" =~ ^[1-9][0-9]{3,4}$ ]] && ((base >= 1024 && base <= 63535)) || { echo "Both base and base+2000 must be valid ports" >&2; exit 2; }
# Refusals above occur before reading configuration, certificates, or creating files.
source "$(dirname "${BASH_SOURCE[0]}")/fabric-env.sh"
block="$ROOT/runtime/$ch.block"
policy="AND('Org1MSP.peer','Org2MSP.peer')"

osnlist() {
  osnadmin channel list -o 127.0.0.1:27053 --ca-file "$ORDERER_TLS/ca.crt" \
    --client-cert "$ORDERER_TLS/server.crt" --client-key "$ORDERER_TLS/server.key"
}
definition() {
  peer_admin "$1"
  peer lifecycle chaincode querycommitted --channelID "$ch" --output json |
    python3 -c 'import json,sys; rows=[r for r in json.load(sys.stdin).get("chaincode_definitions",[]) if r["name"]=="evidence"]; assert len(rows)<=1; print(str(rows[0]["sequence"])+":"+rows[0]["version"] if rows else "0:none")'
}
status() {
  osnlist
  for org in 1 2; do
    peer_admin "$org"
    peer channel getinfo -c "$ch"
    peer lifecycle chaincode querycommitted --channelID "$ch" --output json
  done
}

if [[ "$cmd" == status ]]; then status; exit; fi
if [[ "$cmd" == create ]]; then
  initialize_runtime
  [[ -f "$block" ]] || configtxgen -profile Trust -channelID "$ch" -outputBlock "$block"
  joined=$(osnlist | sed -n '/^{/,$p' | python3 -c 'import json,sys; print("yes" if any(c["name"]==sys.argv[1] for c in json.load(sys.stdin).get("channels",[])) else "no")' "$ch")
  if [[ "$joined" == no ]]; then
    osnadmin channel join --channelID "$ch" --config-block "$block" -o 127.0.0.1:27053 \
      --ca-file "$ORDERER_TLS/ca.crt" --client-cert "$ORDERER_TLS/server.crt" --client-key "$ORDERER_TLS/server.key"
  fi
  for org in 1 2; do
    peer_admin "$org"
    channels=$(peer channel list)
    if ! grep -qx "$ch" <<< "$channels"; then peer channel join -b "$block"; fi
  done
  status; exit
fi

current=$(definition 1); second=$(definition 2)
[[ "$current" == "$second" ]] || { echo "Peer definitions differ; refusing changes" >&2; exit 1; }
if [[ "$cmd" == deploy-v1 ]]; then
  major=1; version=1.0
  case "$current" in 1:1.0|2:2.0|3:2.1) echo "Already committed $current; read-only return"; status; exit;; 0:none) ;; *) echo "Unexpected definition $current" >&2; exit 1;; esac
elif [[ "$cmd" == upgrade-v2 ]]; then
  major=2; version=2.0
  case "$current" in 2:2.0|3:2.1) echo "Already committed $current; read-only return"; status; exit;; 1:1.0) ;; *) echo "Upgrade requires evidence 1.0 at sequence 1; got $current" >&2; exit 1;; esac
else
  major=21; version=2.1
  case "$current" in 3:2.1) echo "Already committed $current; read-only return"; status; exit;; 2:2.0) ;; *) echo "Upgrade requires evidence 2.0 at sequence 2; got $current" >&2; exit 1;; esac
fi
sequence=$(( ${current%%:*} + 1 ))
binary="$ROOT/artifacts/$ch-evidence-chaincode-v$major"

# Check both ports before building, installing, approving, or starting either process.
for org in 1 2; do
  port=$((base+(org-1)*2000)); name="$ch-chaincode$org-v$major"
  python3 - "$ROOT/runtime/pids/$name.pid" "$binary" "$port" <<'PY'
import os,pathlib,socket,sys
pidfile,binary,port=sys.argv[1:]; port=int(port)
if pathlib.Path(pidfile).exists():
    raw=pathlib.Path(pidfile).read_text().strip()
    if not raw.isdigit(): raise SystemExit("Invalid scoped PID file")
    proc=pathlib.Path('/proc')/raw
    if proc.exists() and (proc/'stat').read_text().split()[2] != 'Z':
        if os.path.realpath(proc/'exe') != os.path.realpath(binary): raise SystemExit("Scoped PID belongs to another executable")
        env=(proc/'environ').read_bytes().split(b'\0')
        if f'CHAINCODE_ADDRESS=127.0.0.1:{port}'.encode() not in env: raise SystemExit("Existing scoped process uses a different port")
        raise SystemExit(0)
with socket.socket() as s:
    try: s.bind(('127.0.0.1',port))
    except OSError: raise SystemExit(f"Refusing occupied CCAAS port {port}")
PY
 done
initialize_runtime
if [[ "$major" == 1 ]]; then
  [[ -f "$ROOT/artifacts/evidence-chaincode" ]] || { echo "Missing original v1 binary" >&2; exit 1; }
  if [[ -f "$binary" ]]; then cmp -s "$ROOT/artifacts/evidence-chaincode" "$binary" || { echo "Existing scoped v1 binary differs" >&2; exit 1; }
  else cp -p "$ROOT/artifacts/evidence-chaincode" "$binary"; fi
else
  export GOTOOLCHAIN=local
  (cd "$ROOT/contracts"; go test -mod=readonly ./...)
  build=$(mktemp "$ROOT/artifacts/$ch-build.XXXXXX")
  trap 'rm -f -- "$build"' EXIT
  (cd "$ROOT/contracts"; go build -mod=readonly -trimpath -o "$build" .)
  if [[ -f "$binary" ]]; then cmp -s "$build" "$binary" || { echo "Existing scoped v2 binary differs; refusing overwrite" >&2; exit 1; }
  else mv "$build" "$binary"; fi
  rm -f -- "$build"; trap - EXIT
fi
for org in 1 2; do
  python3 "$ROOT/deploy/package-chaincode.py" "$org" "$major" "$base" "$ch"
  peer_admin "$org"
  pkg="$ROOT/artifacts/$ch-evidence-org$org-v$major.tar.gz"
  ccid=$(peer lifecycle chaincode calculatepackageid "$pkg")
  idfile="$ROOT/runtime/$ch-ccid$org-v$major"
  if [[ -f "$idfile" ]]; then [[ "$(cat "$idfile")" == "$ccid" ]] || { echo "Scoped package ID changed" >&2; exit 1; }
  else printf '%s\n' "$ccid" > "$idfile"; fi
  installed=$(peer lifecycle chaincode queryinstalled --output json)
  if ! python3 -c 'import json,sys; sys.exit(0 if any(r["package_id"]==sys.argv[1] for r in json.load(sys.stdin).get("installed_chaincodes",[])) else 1)' "$ccid" <<< "$installed"; then
    peer lifecycle chaincode install "$pkg"
  fi
  export CHAINCODE_ID="$ccid" CHAINCODE_ADDRESS="127.0.0.1:$((base+(org-1)*2000))" CHAINCODE_TLS_DISABLED=false
  export CHAINCODE_TLS_KEY="$CORE_PEER_TLS_KEY_FILE" CHAINCODE_TLS_CERT="$CORE_PEER_TLS_CERT_FILE"
  start_process "$ch-chaincode$org-v$major" "$binary"
  python3 - "$ROOT/runtime/pids/$ch-chaincode$org-v$major.pid" "$binary" "$CHAINCODE_ADDRESS" "$ccid" <<'PY'
import os,pathlib,socket,sys,time
pidfile,binary,address,ccid=sys.argv[1:]
pid=pathlib.Path(pidfile).read_text().strip()
if not pid.isdigit(): raise SystemExit("Invalid scoped PID file")
proc=pathlib.Path('/proc')/pid
for attempt in range(30):
    if not proc.exists(): raise SystemExit("Scoped CCAAS process exited")
    if os.path.realpath(proc/'exe') != os.path.realpath(binary): raise SystemExit("Unexpected scoped executable")
    env=(proc/'environ').read_bytes().split(b'\0')
    if f'CHAINCODE_ID={ccid}'.encode() not in env: raise SystemExit("Running CCAAS package ID mismatch")
    if f'CHAINCODE_ADDRESS={address}'.encode() not in env: raise SystemExit("Running CCAAS port mismatch")
    try:
        with socket.create_connection(('127.0.0.1',int(address.rsplit(':',1)[1])),timeout=.2): break
    except OSError: time.sleep(.2)
else: raise SystemExit("Scoped CCAAS listener did not start")
PY
  peer lifecycle chaincode approveformyorg -o 127.0.0.1:27050 --tls --cafile "$ORDERER_TLS/ca.crt" \
    --channelID "$ch" --name evidence --version "$version" --package-id "$ccid" --sequence "$sequence" --signature-policy "$policy"
 done
peer_admin 1
peer lifecycle chaincode checkcommitreadiness --channelID "$ch" --name evidence --version "$version" --sequence "$sequence" \
  --signature-policy "$policy" --output json |
  python3 -c 'import json,sys; a=json.load(sys.stdin)["approvals"]; assert a.get("Org1MSP") and a.get("Org2MSP"), "Both organizations must approve"'
peer lifecycle chaincode commit -o 127.0.0.1:27050 --tls --cafile "$ORDERER_TLS/ca.crt" \
  --channelID "$ch" --name evidence --version "$version" --sequence "$sequence" --signature-policy "$policy" \
  --peerAddresses 127.0.0.1:27051 --tlsRootCertFiles "$CRYPTO/peerOrganizations/org1.trust/peers/peer0.org1.trust/tls/ca.crt" \
  --peerAddresses 127.0.0.1:29051 --tlsRootCertFiles "$CRYPTO/peerOrganizations/org2.trust/peers/peer0.org2.trust/tls/ca.crt"
status
