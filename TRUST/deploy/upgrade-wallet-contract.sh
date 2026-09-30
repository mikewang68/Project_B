#!/usr/bin/env bash
# Side-by-side CCAAS upgrade: existing peers, orderer, ledger and v1 processes are retained.
source "$(dirname "$0")/fabric-env.sh"
peer_admin 1
committed=$(peer lifecycle chaincode querycommitted --channelID trust --name evidence --output json)
sequence=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["sequence"])' <<< "$committed")
if [[ "${1:---check}" == --check ]]; then
  echo "Current evidence sequence: $sequence; wallet contract target: version 2.0, sequence 2"
  [[ "$sequence" == 1 || "$sequence" == 2 ]]
  exit
fi
[[ "${1:-}" == --apply ]] || { echo 'Usage: upgrade-wallet-contract.sh --check|--apply'; exit 2; }
if [[ "$sequence" == 2 ]]; then echo 'Sequence 2 already committed; verify before any further upgrade.'; exit 0; fi
[[ "$sequence" == 1 ]] || { echo 'Unexpected committed sequence; refusing to guess an upgrade'; exit 1; }
cd "$ROOT/contracts"
export GOTOOLCHAIN=local GOPROXY=https://goproxy.cn,direct
go test ./...
go build -trimpath -o "$ROOT/artifacts/evidence-chaincode-v2" .
for org in 1 2; do
  python3 "$ROOT/deploy/package-chaincode.py" "$org" 2
  peer_admin "$org"
  pkg="$ROOT/artifacts/evidence$org-v2.tar.gz"
  ccid=$(peer lifecycle chaincode calculatepackageid "$pkg")
  peer lifecycle chaincode queryinstalled | grep -Fq "$ccid" || peer lifecycle chaincode install "$pkg"
  echo "$ccid" > "$ROOT/runtime/ccid$org-v2"
  export CHAINCODE_ID="$ccid" CHAINCODE_ADDRESS="127.0.0.1:$((27159+(org-1)*2000))" CHAINCODE_TLS_DISABLED=false
  export CHAINCODE_TLS_KEY="$CORE_PEER_TLS_KEY_FILE" CHAINCODE_TLS_CERT="$CORE_PEER_TLS_CERT_FILE"
  start_process "chaincode$org-v2" "$ROOT/artifacts/evidence-chaincode-v2"
  peer lifecycle chaincode approveformyorg -o 127.0.0.1:27050 --tls --cafile "$ORDERER_TLS/ca.crt" --channelID trust --name evidence --version 2.0 --package-id "$ccid" --sequence 2 --signature-policy "AND('Org1MSP.peer','Org2MSP.peer')"
done
peer_admin 1
peer lifecycle chaincode checkcommitreadiness --channelID trust --name evidence --version 2.0 --sequence 2 --signature-policy "AND('Org1MSP.peer','Org2MSP.peer')" --output json |
  python3 -c 'import json,sys; a=json.load(sys.stdin)["approvals"]; assert a.get("Org1MSP") and a.get("Org2MSP"), "Both organizations must approve"'
peer lifecycle chaincode commit -o 127.0.0.1:27050 --tls --cafile "$ORDERER_TLS/ca.crt" --channelID trust --name evidence --version 2.0 --sequence 2 --signature-policy "AND('Org1MSP.peer','Org2MSP.peer')" --peerAddresses 127.0.0.1:27051 --tlsRootCertFiles "$CRYPTO/peerOrganizations/org1.trust/peers/peer0.org1.trust/tls/ca.crt" --peerAddresses 127.0.0.1:29051 --tlsRootCertFiles "$CRYPTO/peerOrganizations/org2.trust/peers/peer0.org2.trust/tls/ca.crt"
peer lifecycle chaincode querycommitted --channelID trust --name evidence
echo 'Wallet contract committed. Old ledger and v1 processes retained; authorize signer scopes separately.'
