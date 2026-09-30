#!/usr/bin/env bash
source "$(dirname "$0")/fabric-env.sh"
[[ $# == 1 && -f "$1" ]] || { echo 'Usage: signer-policy.sh APPROVED_POLICY.json'; exit 2; }
proposal=$(python3 - "$1" <<'PY'
import json,re,sys
p=json.load(open(sys.argv[1],encoding='utf-8'))
assert set(p)=={'orgId','sourceSystem','mspId','fingerprint','enabled','revision','changeId'}
for k in ['orgId','sourceSystem','mspId']: assert re.fullmatch(r'[A-Za-z0-9._-]{1,80}',p[k])
assert re.fullmatch(r'[a-f0-9]{64}',p['fingerprint'])
assert re.fullmatch(r'[a-f0-9-]{36}',p['changeId'])
assert type(p['enabled']) is bool and type(p['revision']) is int and p['revision']>0
print(json.dumps({'Args':['SetSigner',json.dumps(p,separators=(',',':'))]}))
PY
)
peer_admin 1
peer chaincode invoke -o 127.0.0.1:27050 --tls --cafile "$ORDERER_TLS/ca.crt" -C trust -n evidence -c "$proposal" --waitForEvent --peerAddresses 127.0.0.1:27051 --tlsRootCertFiles "$CRYPTO/peerOrganizations/org1.trust/peers/peer0.org1.trust/tls/ca.crt" --peerAddresses 127.0.0.1:29051 --tlsRootCertFiles "$CRYPTO/peerOrganizations/org2.trust/peers/peer0.org2.trust/tls/ca.crt"
