"""Actual independent IPFS and Fabric checks via operator SSH, independent of application tunnels."""
import pathlib,subprocess,json,hashlib,uuid,base64,time,sys,shlex
ROOT=pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0,str(ROOT/'deploy'))
from deployment import load
DEPLOYMENT=load();REMOTE=DEPLOYMENT['remoteRoot'].rstrip('/');NODES=DEPLOYMENT['nodes']
OUT=ROOT/'.local/test-results';OUT.mkdir(parents=True,exist_ok=True)
results=[]
def ssh(node,script,ok=True):
    r=subprocess.run(['ssh','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes',node,'bash','-s'],input=script.encode(),capture_output=True,timeout=150)
    if ok and r.returncode:raise AssertionError(node+': '+r.stderr.decode(errors='replace')[-2000:])
    return r
def measure(name,fn):
    start=time.monotonic();value=fn();results.append({'scenario':name,'status':'PASS','seconds':round(time.monotonic()-start,3)});print(name,'PASS',flush=True);return value
def ipfs_add(bytes):
    script=r"""import urllib.request,hashlib,json,base64
b=base64.b64decode(DATA);boundary='trustcomponent'
payload=b'--trustcomponent\r\nContent-Disposition: form-data; name="file"; filename="proof"\r\n\r\n'+b+b'\r\n--trustcomponent--\r\n'
r=urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:5001/api/v0/add?pin=true&cid-version=1',data=payload,headers={'Content-Type':'multipart/form-data; boundary='+boundary}),timeout=30)
cid=json.load(r)['Hash']
back=urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:5001/api/v0/cat?arg='+cid,data=b''),timeout=30).read()
assert hashlib.sha256(back).digest()==hashlib.sha256(b).digest()
print(cid)
""".replace('DATA',repr(base64.b64encode(bytes).decode()))
    return ssh(NODES['ipfs']['sshAlias'],"python3 - <<'PY'\n"+script+"\nPY").stdout.decode().strip()
def contract(fn,args,admin=False,expect_success=True):
    params=json.dumps({'Args':[fn,*args]},separators=(',',':'))
    setup=f'source {REMOTE}/deploy/fabric-env.sh\npeer_admin 1\n'
    if not admin:setup+='export CORE_PEER_MSPCONFIGPATH="$CRYPTO/peerOrganizations/org1.trust/users/User1@org1.trust/msp"\n'
    if fn.startswith('Get'):
        command=f'peer chaincode query -C trust -n evidence -c {shlex.quote(params)}'
    else:
        command='peer chaincode invoke -o 127.0.0.1:27050 --tls --cafile "$ORDERER_TLS/ca.crt" -C trust -n evidence --peerAddresses 127.0.0.1:27051 --tlsRootCertFiles "$CRYPTO/peerOrganizations/org1.trust/peers/peer0.org1.trust/tls/ca.crt" --peerAddresses 127.0.0.1:29051 --tlsRootCertFiles "$CRYPTO/peerOrganizations/org2.trust/peers/peer0.org2.trust/tls/ca.crt" --waitForEvent --waitForEventTimeout 30s -c '+shlex.quote(params)
    result=ssh(NODES['fabric']['sshAlias'],setup+command,ok=expect_success)
    if not expect_success:assert result.returncode!=0,'Unexpected contract acceptance';return None
    if fn.startswith('Get'):return json.loads(result.stdout)
def run():
    content=json.dumps({'fixture':'REAL_COMPONENT_TEST','note':'Simulated evidence, not actual cargo documentation','run':uuid.uuid4().hex},sort_keys=True).encode()
    cid=measure('independent IPFS add, pin, retrieve and SHA-256',lambda:ipfs_add(content))
    id=str(uuid.uuid4());r={'id':id,'orgId':'B-PROJECT','rootId':id,'version':1,'supersedesId':'','eventSha256':hashlib.sha256(content).hexdigest(),'manifestCid':cid,'manifestSha256':hashlib.sha256(content).hexdigest(),'submittedBy':'component-test'}
    measure('Real Fabric dual-organization registration',lambda:contract('RegisterEvent',[json.dumps(r)]))
    initial=contract('GetEvent',['B-PROJECT',id]);assert initial['manifestCid']==cid and initial['txId']
    measure('Idempotent contract replay retains original registration',lambda:contract('RegisterEvent',[json.dumps(r)]))
    assert contract('GetEvent',['B-PROJECT',id])==initial
    bad=dict(r,eventSha256='0'*64)
    measure('Different contents cannot overwrite an event',lambda:contract('RegisterEvent',[json.dumps(bad)],expect_success=False))
    measure('Administrator certificate cannot impersonate registrar',lambda:contract('RegisterEvent',[json.dumps(r)],admin=True,expect_success=False))
    corrected=dict(r,id=str(uuid.uuid4()),version=2,supersedesId=id)
    measure('Append correction keeps prior version',lambda:contract('AppendCorrection',[json.dumps(corrected)]))
    history=contract('GetEventHistory',['B-PROJECT',id]);assert len(history)==2 and history[0]==initial
    fork=dict(corrected,id=str(uuid.uuid4()))
    measure('Correction fork from stale version rejected',lambda:contract('AppendCorrection',[json.dumps(fork)],expect_success=False))
    assert contract('GetEvent',['B-PROJECT',id])==initial
    (OUT/'real-components.json').write_text(json.dumps({'tests':results,'manifestCid':cid,'original':initial,'correction':contract('GetEvent',['B-PROJECT',corrected['id']]),'limitation':'Operator SSH component tests; application end-to-end transport remains a separate acceptance item.'},ensure_ascii=False,indent=2),encoding='utf-8')
if __name__=='__main__':run()
