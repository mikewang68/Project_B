source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
export PATH="$ROOT/tools/fabric/bin:$ROOT/tools/go/bin:$PATH"
export FABRIC_CFG_PATH="$ROOT/runtime/fabric-config"
export FABRIC_LOGGING_SPEC=warn
CRYPTO="$ROOT/runtime/secrets/crypto"
ORDERER_TLS="$CRYPTO/ordererOrganizations/orderer.trust/orderers/orderer.orderer.trust/tls"
peer_env() {
 local org=$1; local port=27051; [[ "$org" == 2 ]] && port=29051
 export CORE_PEER_ID="peer0.org$org.trust" CORE_PEER_LOCALMSPID="Org${org}MSP"
 export CORE_PEER_ADDRESS="127.0.0.1:$port" CORE_PEER_LISTENADDRESS="127.0.0.1:$port"
 export CORE_PEER_CHAINCODELISTENADDRESS="127.0.0.1:$((port+1))"
 export CORE_PEER_GOSSIP_EXTERNALENDPOINT="127.0.0.1:$port" CORE_PEER_GOSSIP_BOOTSTRAP="127.0.0.1:$port"
 export CORE_PEER_FILESYSTEMPATH="$ROOT/runtime/fabric/peer$org"
 export CORE_LEDGER_SNAPSHOTS_ROOTDIR="$ROOT/runtime/fabric/snapshots$org"
 export CORE_OPERATIONS_LISTENADDRESS="127.0.0.1:$((port+3))"
 export CORE_PEER_TLS_ENABLED=true
 local p="$CRYPTO/peerOrganizations/org$org.trust/peers/peer0.org$org.trust"
 export CORE_PEER_MSPCONFIGPATH="$p/msp" CORE_PEER_TLS_CERT_FILE="$p/tls/server.crt" CORE_PEER_TLS_KEY_FILE="$p/tls/server.key" CORE_PEER_TLS_ROOTCERT_FILE="$p/tls/ca.crt"
 export CORE_VM_ENDPOINT=""
}
peer_admin() { peer_env "$1"; export CORE_PEER_MSPCONFIGPATH="$CRYPTO/peerOrganizations/org$1.trust/users/Admin@org$1.trust/msp"; }
