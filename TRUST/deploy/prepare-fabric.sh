#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
python3 "$ROOT/deploy/download.py" fabric
mkdir -p "$ROOT/tools/fabric"
tar -xzf "$ROOT/artifacts/hyperledger-fabric-linux-amd64-3.1.5.tar.gz" -C "$ROOT/tools/fabric"
"$ROOT/tools/fabric/bin/peer" version
if [[ ! -x "$ROOT/tools/go/bin/go" ]]; then
  python3 - "$ROOT" <<'PY'
import sys,json,urllib.request,hashlib,pathlib
r=pathlib.Path(sys.argv[1]); data=json.load(urllib.request.urlopen('https://golang.google.cn/dl/?mode=json&include=all',timeout=30))
f=next(f for v in data if v['version']=='go1.26.4' for f in v['files'] if f['filename']=='go1.26.4.linux-amd64.tar.gz')
p=r/'artifacts'/f['filename']; urllib.request.urlretrieve('https://golang.google.cn/dl/'+f['filename'],p)
assert hashlib.sha256(p.read_bytes()).hexdigest()==f['sha256']
(r/'artifacts/go-release.json').write_text(json.dumps(f,indent=2))
PY
  tar -xzf "$ROOT/artifacts/go1.26.4.linux-amd64.tar.gz" -C "$ROOT/tools"
fi
