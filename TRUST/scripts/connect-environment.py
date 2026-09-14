"""Provision restricted application tunnels using the already trusted local SSH aliases."""
import pathlib,subprocess,json,sys,argparse
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parent.parent/"deploy"))
from deployment import load, PORTS
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--dry-run",action="store_true")
args=parser.parse_args()
config=load()
if args.dry_run:
    print("Provisioning configuration valid; no keys, files or server settings changed")
    raise SystemExit()
nodes=config["nodes"]
app=nodes["application"]["sshAlias"]
database=nodes["database"]["sshAlias"]
fabric=nodes["fabric"]["sshAlias"]
root=pathlib.Path(__file__).resolve().parent.parent
local=root/'.local';local.mkdir(exist_ok=True)
remote=config['remoteRoot'].rstrip('/')
def ssh(node,script):
    result=subprocess.run(['ssh','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes',node,'bash','-s'],input=script.encode('utf-8'),capture_output=True)
    if result.returncode:raise RuntimeError(node+': '+result.stderr.decode('utf-8',errors='replace'))
    return result.stdout.decode('utf-8').strip()
def copy(src,dest):subprocess.run(['scp','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes',str(src),dest],check=True)
ssh(app,f"set -e\numask 077\nmkdir -p {remote}/runtime/secrets/fabric\nif [ ! -f {remote}/runtime/secrets/tunnel_ed25519 ]; then ssh-keygen -q -t ed25519 -N '' -f {remote}/runtime/secrets/tunnel_ed25519; fi\n")
public=ssh(app,f'cat {remote}/runtime/secrets/tunnel_ed25519.pub')
known=[]
for role in ('ipfs','database','fabric'):
    node=nodes[role]['sshAlias'];ip=nodes[role]['address'];port=PORTS[role]
    hostkey=ssh(node,'cat /etc/ssh/ssh_host_ed25519_key.pub').split()
    known.append(ip+' '+hostkey[0]+' '+hostkey[1])
    entry=f'restrict,port-forwarding,from="{config["applicationSource"]}",permitopen="127.0.0.1:{port}",command="/bin/false" {public}'
    script=r"""import pathlib,os
p=pathlib.Path.home()/'.ssh';p.mkdir(mode=0o700,exist_ok=True)
f=p/'authorized_keys';old=f.read_text() if f.exists() else ''
entry=ENTRY
if entry not in old.splitlines():f.write_text(old.rstrip()+'\n'+entry+'\n')
f.chmod(0o600)
""".replace('ENTRY',repr(entry))
    ssh(node,"python3 - <<'PY'\n"+script+"\nPY\n")
(local/'known_hosts').write_text('\n'.join(known)+'\n')
copy(local/'known_hosts',f'{app}:{remote}/runtime/secrets/known_hosts')
copy(f'{database}:{remote}/runtime/secrets/db.env',str(local/'db.env'))
copy(local/'db.env',f'{app}:{remote}/runtime/secrets/db.env')
crypto=f'{remote}/runtime/secrets/crypto/peerOrganizations/org1.trust'
for source,name in [(f'{crypto}/users/User1@org1.trust/msp/signcerts/User1@org1.trust-cert.pem','user.crt'),(f'{crypto}/peers/peer0.org1.trust/tls/ca.crt','tls-ca.crt')]:
    copy(fabric+':'+source,str(local/name));copy(local/name,f'{app}:{remote}/runtime/secrets/fabric/{name}');(local/name).unlink()
keypath=ssh(fabric,f'find {crypto}/users/User1@org1.trust/msp/keystore -maxdepth 1 -type f').splitlines()
assert len(keypath)==1
copy(fabric+':'+keypath[0],str(local/'user.key'));copy(local/'user.key',f'{app}:{remote}/runtime/secrets/fabric/user.key');(local/'user.key').unlink()
ssh(app,f"chmod 600 {remote}/runtime/secrets/db.env {remote}/runtime/secrets/fabric/user.key\npython3 - <<'PY'\n"+f"import pathlib,json,secrets\np=pathlib.Path('{remote}/runtime/secrets/users.json')\n"+"if not p.exists():\n users=[{'username':u,'password':'Dev_'+secrets.token_hex(8)+'!', 'role':role,'orgId':org} for u,role,org in [('admin','ADMIN','B-PROJECT'),('editor','EDITOR','B-PROJECT'),('viewer','VIEWER','B-PROJECT'),('external-viewer','VIEWER','EXTERNAL-TEST')]]\n p.write_text(json.dumps(users,indent=2));p.chmod(0o600)\nPY\n")
copy(f'{app}:{remote}/runtime/secrets/users.json',str(local/'development-accounts.json'))
(local/'db.env').unlink()
print('Restricted tunnels and application credentials provisioned. Accounts are in .local/development-accounts.json.')
