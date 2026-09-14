#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
mkdir -p "$ROOT/tools/gateway-probe" "$ROOT/runtime/secrets/fabric"
cd "$ROOT/tools/gateway-probe"
(cd "$ROOT/backend"; mvn -B -ntp -Dmaven.repo.local="$ROOT/tools/m2" -DskipTests clean compile dependency:build-classpath -Dmdep.outputFile=target/probe-classpath.txt)
crypto="$ROOT/runtime/secrets/crypto/peerOrganizations/org1.trust"
cp "$crypto/users/User1@org1.trust/msp/signcerts/User1@org1.trust-cert.pem" "$ROOT/runtime/secrets/fabric/user.crt"
cp "$crypto/users/User1@org1.trust/msp/keystore/"* "$ROOT/runtime/secrets/fabric/user.key"
cp "$crypto/peers/peer0.org1.trust/tls/ca.crt" "$ROOT/runtime/secrets/fabric/tls-ca.crt"
chmod 600 "$ROOT/runtime/secrets/fabric/user.key"
classpath="$ROOT/backend/target/classes:$(cat "$ROOT/backend/target/probe-classpath.txt")"
mkdir -p "$ROOT/tools/gateway-probe/classes"
javac -cp "$classpath" -d "$ROOT/tools/gateway-probe/classes" "$ROOT/tests/FabricGatewayProbe.java"
java -cp "$ROOT/tools/gateway-probe/classes:$classpath" com.bproject.trust.FabricGatewayProbe "$ROOT" "${1:?Original event fixture ID required}"
