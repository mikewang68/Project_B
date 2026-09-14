"""Exercise the offline verifier with synthetic archive bytes; no ledger claim."""
import hashlib,json,pathlib,subprocess,sys,tempfile,zipfile
ROOT=pathlib.Path(__file__).resolve().parent.parent
sha=lambda data:hashlib.sha256(data).hexdigest()
encode=lambda value:json.dumps(value,sort_keys=True,separators=(',',':')).encode()
proof=b'%PDF-1.4 explicitly simulated evidence'
event=encode({'id':'offline-fixture','event':{'batchId':'STEEL-2026-001'}})
manifest=encode({'event':json.loads(event),'eventSha256':sha(event),'evidence':[{'id':'proof','sha256':sha(proof)}]})
files={'event.json':event,'evidence-manifest.json':manifest,'evidence/proof.pdf':proof,'ledger-query.json':b'{"note":"synthetic; not chain evidence"}'}
files['checksums.json']=encode({name:sha(body) for name,body in files.items()});files['README.txt']=b'Synthetic verifier test'
results=[]
with tempfile.TemporaryDirectory(prefix='offline-',dir=ROOT/'.local') as temp:
    path=pathlib.Path(temp)/'evidence.zip'
    cases=[('unaltered package',{},sha(manifest),0),('replaced evidence',{'evidence/proof.pdf':b'replaced'},sha(manifest),1),('missing evidence',{'evidence/proof.pdf':None},sha(manifest),1),('wrong trusted manifest digest',{},'0'*64,1)]
    for scenario,changes,expected,exit_code in cases:
        variant=dict(files);variant.update(changes)
        with zipfile.ZipFile(path,'w') as archive:
            for name,body in variant.items():
                if body is not None:archive.writestr(name,body)
        result=subprocess.run([sys.executable,str(ROOT/'scripts/verify-export.py'),str(path),'--manifest-sha256',expected],capture_output=True,text=True)
        assert result.returncode==exit_code,(scenario,result.stdout,result.stderr)
        results.append({'scenario':scenario,'status':'PASS'})
(ROOT/'.local/test-results/offline-verifier.json').write_text(json.dumps({'mode':'Synthetic exported-file fixtures; not real application acceptance','tests':results},indent=2))
print('PASS: four offline verifier checks')
