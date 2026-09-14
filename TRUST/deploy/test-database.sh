#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
set -a
source "$ROOT/runtime/secrets/db.env"
set +a
export TRUST_ROOT="$ROOT" TRUST_DB_INTEGRATION=1 TRUST_DB_USER=trust_app
cd "$ROOT/backend"
mvn -B -ntp -Dmaven.repo.local="$ROOT/tools/m2" clean test >"$ROOT/runtime/logs/database-tests.log" 2>&1
python3 - "$ROOT" <<'PY'
import json,pathlib,sys,xml.etree.ElementTree as E
root=pathlib.Path(sys.argv[1]);tests=[]
for p in sorted((root/'backend/target/surefire-reports').glob('TEST-*.xml')):
    r=E.parse(p).getroot()
    tests.append({k:r.get(k) for k in ('name','tests','failures','errors','skipped','time')})
result={'mode':'Real openGauss; IPFS and Fabric fault injection','suites':tests}
(root/'.local/test-results').mkdir(parents=True,exist_ok=True)
(root/'.local/test-results/database-integration.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result,indent=2))
PY
