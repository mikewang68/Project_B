#!/usr/bin/env bash
source "$(dirname "$0")/fabric-env.sh"
case "${1:-status}" in
 init)
  [[ -d "$CRYPTO" ]] || cryptogen generate --config="$ROOT/deploy/crypto-config.yaml" --output="$CRYPTO"
  python3 "$ROOT/deploy/fabric-config.py"
  [[ -f "$ROOT/runtime/trust.block" ]] || configtxgen -profile Trust -channelID trust -outputBlock "$ROOT/runtime/trust.block"
  ;;
 start)
  export ORDERER_GENERAL_LISTENADDRESS=127.0.0.1 ORDERER_GENERAL_LISTENPORT=27050
  export ORDERER_GENERAL_LOCALMSPID=OrdererMSP ORDERER_GENERAL_LOCALMSPDIR="${ORDERER_TLS%/tls}/msp"
  export ORDERER_GENERAL_BOOTSTRAPMETHOD=none ORDERER_CHANNELPARTICIPATION_ENABLED=true
  export ORDERER_GENERAL_TLS_ENABLED=true ORDERER_GENERAL_TLS_PRIVATEKEY="$ORDERER_TLS/server.key" ORDERER_GENERAL_TLS_CERTIFICATE="$ORDERER_TLS/server.crt"
  export ORDERER_GENERAL_TLS_ROOTCAS="[$ORDERER_TLS/ca.crt]"
  export ORDERER_GENERAL_CLUSTER_CLIENTCERTIFICATE="$ORDERER_TLS/server.crt" ORDERER_GENERAL_CLUSTER_CLIENTPRIVATEKEY="$ORDERER_TLS/server.key"
  export ORDERER_GENERAL_CLUSTER_ROOTCAS="[$ORDERER_TLS/ca.crt]"
  export ORDERER_FILELEDGER_LOCATION="$ROOT/runtime/fabric/orderer"
  export ORDERER_CONSENSUS_WALDIR="$ROOT/runtime/fabric/etcdraft/wal" ORDERER_CONSENSUS_SNAPDIR="$ROOT/runtime/fabric/etcdraft/snapshot"
  export ORDERER_ADMIN_LISTENADDRESS=127.0.0.1:27053 ORDERER_ADMIN_TLS_ENABLED=true ORDERER_ADMIN_TLS_CLIENTAUTHREQUIRED=true
  export ORDERER_ADMIN_TLS_PRIVATEKEY="$ORDERER_TLS/server.key" ORDERER_ADMIN_TLS_CERTIFICATE="$ORDERER_TLS/server.crt" ORDERER_ADMIN_TLS_CLIENTROOTCAS="[$ORDERER_TLS/ca.crt]"
  export ORDERER_OPERATIONS_LISTENADDRESS=127.0.0.1:27055
  start_process orderer "$ROOT/tools/fabric/bin/orderer"
  for org in 1 2; do peer_env "$org"; start_process "peer$org" "$ROOT/tools/fabric/bin/peer" node start; done
  ;;
 join)
  if ! osnadmin channel list -o 127.0.0.1:27053 --ca-file "$ORDERER_TLS/ca.crt" --client-cert "$ORDERER_TLS/server.crt" --client-key "$ORDERER_TLS/server.key" | grep -q '"trust"'; then
    osnadmin channel join --channelID trust --config-block "$ROOT/runtime/trust.block" -o 127.0.0.1:27053 --ca-file "$ORDERER_TLS/ca.crt" --client-cert "$ORDERER_TLS/server.crt" --client-key "$ORDERER_TLS/server.key"
  fi
  for org in 1 2; do
   peer_admin "$org"
   ready=false
   for i in {1..30}; do if peer channel list >/dev/null 2>&1; then ready=true; break; fi; sleep 1; done
   [[ "$ready" == true ]] || { echo "Peer $org did not become ready"; exit 1; }
   peer channel list 2>/dev/null | grep -qx trust || peer channel join -b "$ROOT/runtime/trust.block"
  done
  ;;
 deploy-contract)
  cd "$ROOT/contracts"
  export GOPROXY=https://goproxy.cn,direct GOTOOLCHAIN=local
  go mod tidy
  go test ./...
  go build -trimpath -o "$ROOT/artifacts/evidence-chaincode" .
  for org in 1 2; do
   python3 "$ROOT/deploy/package-chaincode.py" "$org"
   peer_admin "$org"
   pkg="$ROOT/artifacts/evidence$org.tar.gz"
   ccid=$(peer lifecycle chaincode calculatepackageid "$pkg")
   echo "$ccid" >"$ROOT/runtime/ccid$org"
   if ! peer lifecycle chaincode queryinstalled | grep -q "$ccid"; then peer lifecycle chaincode install "$pkg"; fi
   export CHAINCODE_ID="$ccid" CHAINCODE_ADDRESS="127.0.0.1:$((27059+(org-1)*2000))" CHAINCODE_TLS_DISABLED=false
   export CHAINCODE_TLS_KEY="$CORE_PEER_TLS_KEY_FILE" CHAINCODE_TLS_CERT="$CORE_PEER_TLS_CERT_FILE"
   start_process "chaincode$org" "$ROOT/artifacts/evidence-chaincode"
   peer lifecycle chaincode approveformyorg -o 127.0.0.1:27050 --tls --cafile "$ORDERER_TLS/ca.crt" --channelID trust --name evidence --version 1.0 --package-id "$ccid" --sequence 1 --signature-policy "AND('Org1MSP.peer','Org2MSP.peer')"
  done
  peer_admin 1
  peer lifecycle chaincode commit -o 127.0.0.1:27050 --tls --cafile "$ORDERER_TLS/ca.crt" --channelID trust --name evidence --version 1.0 --sequence 1 --signature-policy "AND('Org1MSP.peer','Org2MSP.peer')" --peerAddresses 127.0.0.1:27051 --tlsRootCertFiles "$CRYPTO/peerOrganizations/org1.trust/peers/peer0.org1.trust/tls/ca.crt" --peerAddresses 127.0.0.1:29051 --tlsRootCertFiles "$CRYPTO/peerOrganizations/org2.trust/peers/peer0.org2.trust/tls/ca.crt"
  ;;
 start-contract)
  for org in 1 2; do
   peer_env "$org"
   export CHAINCODE_ID="$(cat "$ROOT/runtime/ccid$org")" CHAINCODE_ADDRESS="127.0.0.1:$((27059+(org-1)*2000))" CHAINCODE_TLS_DISABLED=false
   export CHAINCODE_TLS_KEY="$CORE_PEER_TLS_KEY_FILE" CHAINCODE_TLS_CERT="$CORE_PEER_TLS_CERT_FILE"
   start_process "chaincode$org" "$ROOT/artifacts/evidence-chaincode"
  done
  ;;
 stop) for n in chaincode1 chaincode2 peer1 peer2 orderer; do stop_process "$n"; done ;;
 status) for org in 1 2; do peer_admin "$org"; peer channel getinfo -c trust; done ;;
 *) echo 'Usage: fabric.sh init|start|join|deploy-contract|start-contract|stop|status'; exit 2 ;;
esac
