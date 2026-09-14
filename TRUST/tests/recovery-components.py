"""Run real component backup/restore and independent stop/start checks via operator SSH."""
import pathlib,subprocess,json,time
import sys
ROOT=pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0,str(ROOT/'deploy'))
from deployment import load
DEPLOYMENT=load();REMOTE=DEPLOYMENT['remoteRoot'].rstrip('/');NODES=DEPLOYMENT['nodes']
OUT=ROOT/'.local/test-results';OUT.mkdir(parents=True,exist_ok=True);result=[]
def ssh(node,script):
    r=subprocess.run(['ssh','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes',node,'bash','-s'],input=script.encode(),capture_output=True,timeout=150)
    if r.returncode:raise AssertionError(node+': '+(r.stdout+r.stderr).decode(errors='replace')[-2000:])
    return r.stdout.decode()
def check(name,node,script):
    start=time.monotonic();output=ssh(node,script);result.append({'scenario':name,'status':'PASS','seconds':round(time.monotonic()-start,3),'output':output.strip()});print(name,'PASS',flush=True);return output
def run():
    cid=json.loads((OUT/'real-components.json').read_text())['manifestCid']
    check('Real IPFS cold backup and isolated restore',NODES['ipfs']['sshAlias'],f'bash {REMOTE}/deploy/verify-ipfs-restore.sh {cid}')
    check('Real openGauss test-database backup and restore',NODES['database']['sshAlias'],f'bash {REMOTE}/deploy/verify-database-restore.sh trust_test')
    try:
        ssh(NODES['ipfs']['sshAlias'],f'bash {REMOTE}/deploy/ipfs.sh stop')
        check('Fabric remains available while IPFS is stopped',NODES['fabric']['sshAlias'],f'bash {REMOTE}/deploy/fabric.sh status')
    finally:ssh(NODES['ipfs']['sshAlias'],f'bash {REMOTE}/deploy/ipfs.sh start')
    try:
        ssh(NODES['fabric']['sshAlias'],f'bash {REMOTE}/deploy/fabric.sh stop')
        check('IPFS remains available while Fabric is stopped',NODES['ipfs']['sshAlias'],f'bash {REMOTE}/deploy/ipfs.sh status')
    finally:
        ssh(NODES['fabric']['sshAlias'],f'bash {REMOTE}/deploy/fabric.sh start\nbash {REMOTE}/deploy/fabric.sh start-contract')
    # Wait for recovery rather than assuming process launch means readiness.
    check('Fabric persisted ledger returns after restart',NODES['fabric']['sshAlias'],f'for i in {{1..30}}; do bash {REMOTE}/deploy/fabric.sh status && exit 0; sleep 1; done; exit 1')
    (OUT/'recovery-components.json').write_text(json.dumps({'tests':result,'limitation':'Database uses test fixtures with injected external services. Coordinated application backup recovery is a separate acceptance item.'},ensure_ascii=False,indent=2),encoding='utf-8')
    # Keep verified recovery artifacts on the application node as planned, using existing operator aliases.
    ssh(NODES['application']['sshAlias'],f'mkdir -p {REMOTE}/runtime/backups\nchmod 700 {REMOTE}/runtime/backups')
    for node,suffix in [(NODES['ipfs']['sshAlias'],'tar.gz'),(NODES['database']['sshAlias'],'dump')]:
        remote_file=ssh(node,f'ls -1t {REMOTE}/runtime/backups/*.{suffix} | head -1').strip()
        assert remote_file.startswith(REMOTE+'/runtime/backups/')
        subprocess.run(['scp','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-3',node+':'+remote_file,NODES['application']['sshAlias']+':'+REMOTE+'/runtime/backups/'],check=True,timeout=180)
if __name__=='__main__':run()
