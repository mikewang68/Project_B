#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
cd "$ROOT/backend"
mvn -B -ntp -Dmaven.repo.local="$ROOT/tools/m2" dependency:copy \
  -Dartifact=com.google.googlejavaformat:google-java-format:1.22.0:jar:all-deps \
  -DoutputDirectory="$ROOT/tools/formatters"
python3 - "$ROOT" <<'PY'
import pathlib,subprocess,sys
root=pathlib.Path(sys.argv[1])
files=sorted((root/'backend/src').rglob('*.java'))+[root/'tests/FabricGatewayProbe.java']
subprocess.run(['java','-jar',str(root/'tools/formatters/google-java-format-1.22.0-all-deps.jar'),'--replace',*[str(p) for p in files]],check=True)
PY
