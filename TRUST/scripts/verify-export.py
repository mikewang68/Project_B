"""Offline file consistency check. Does not authenticate a blockchain receipt."""
import argparse,hashlib,json,zipfile
p=argparse.ArgumentParser(description=__doc__);p.add_argument('archive');p.add_argument('--manifest-sha256',help='Expected manifest digest obtained independently from Fabric');a=p.parse_args()
with zipfile.ZipFile(a.archive) as z:
    if len(z.namelist())!=len(set(z.namelist())):raise SystemExit('FAIL: duplicate ZIP entries')
    sums=json.loads(z.read('checksums.json'))
    if set(z.namelist())!=set(sums)|{'checksums.json','README.txt'}:raise SystemExit('FAIL: unlisted or missing entries')
    for name,expected in sums.items():
        if hashlib.sha256(z.read(name)).hexdigest()!=expected:raise SystemExit('FAIL: '+name)
    manifest=z.read('evidence-manifest.json');m=json.loads(manifest)
    if hashlib.sha256(z.read('event.json')).hexdigest()!=m['eventSha256']:raise SystemExit('FAIL: event digest')
    for evidence in m['evidence']:
        name=next((n for n in sums if n.startswith('evidence/'+evidence['id']+'.')),None)
        if not name or sums[name]!=evidence['sha256']:raise SystemExit('FAIL: evidence binding')
    if a.manifest_sha256 and hashlib.sha256(manifest).hexdigest()!=a.manifest_sha256:raise SystemExit('FAIL: independently supplied manifest digest')
print('PASS: archive file consistency'+(' and supplied manifest digest' if a.manifest_sha256 else '')+'. Ledger authenticity requires online verification.')
