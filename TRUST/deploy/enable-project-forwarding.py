#!/usr/bin/env python3
"""Root-only SSH forwarding prerequisite. Default validates a temporary candidate; --apply installs it."""
import argparse,datetime,ipaddress,pathlib,re,socket,subprocess,os
from deployment import load, PORTS
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',help='Override allowed source IPv4 address');p.add_argument('--role',required=True,choices=tuple(PORTS));p.add_argument('--apply',action='store_true');args=p.parse_args()
deployment=load();user=deployment['sshUser'];port=PORTS[args.role]
source=str(ipaddress.IPv4Address(args.source or deployment['operatorSource' if args.role=='application' else 'applicationSource']));host=socket.gethostname().split('.')[0]
if os.geteuid()!=0:raise SystemExit('Requires root to inspect and validate sshd configuration')
import json
interfaces=json.loads(subprocess.check_output(['ip','-j','-4','addr','show'],text=True))
addresses={a['local'] for interface in interfaces for a in interface.get('addr_info',[])}
if deployment['nodes'][args.role]['address'] not in addresses:raise SystemExit('Role address does not belong to this machine; configuration was not changed')
config=pathlib.Path('/etc/ssh/sshd_config');original=config.read_text()
begin='# BEGIN B-PROJECT-TRUST FORWARDING';end='# END B-PROJECT-TRUST FORWARDING'
clean=re.sub(re.escape(begin)+r'.*?'+re.escape(end)+r'\n?', '', original,flags=re.S)
block=f'{begin}\nMatch User {user} Address {source}\n    DisableForwarding no\n    AllowTcpForwarding local\n    PermitOpen 127.0.0.1:{port}\n{end}\n'
match=re.search(r'^\s*Match\s',clean,re.M);offset=match.start() if match else len(clean)
updated=clean[:offset].rstrip()+'\n\n'+block+clean[offset:]
target=pathlib.Path('/etc/ssh/sshd_config.b-project-trust.candidate')
try:
    target.write_text(updated);target.chmod(0o600)
    subprocess.run(['/usr/sbin/sshd','-t','-f',str(target)],check=True)
    output=subprocess.check_output(['/usr/sbin/sshd','-T','-f',str(target),'-C',f'user={user},addr={source},host={host}'],text=True)
    values=dict(line.split(' ',1) for line in output.splitlines())
    for key,value in [('disableforwarding','no'),('allowtcpforwarding','local'),('permitopen',f'127.0.0.1:{port}')]:
        if values.get(key)!=value:raise SystemExit(f'Existing policy still overrides {key}; configuration was not changed')
    print(f'{host}: {user} from {source}, local forwarding only to 127.0.0.1:{port}')
    if args.apply:
        stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        backup=config.with_name('sshd_config.before-b-project-trust-'+stamp)
        backup.write_text(original);backup.chmod(0o600)
        config.write_text(updated)
        try:subprocess.run(['systemctl','reload','sshd'],check=True)
        except Exception:
            config.write_text(original);subprocess.run(['systemctl','reload','sshd'],check=True);raise
        print('Applied; existing SSH sessions retained. Backup:',backup)
    else:print('Configuration validated; add --apply to install it.')
finally:
    target.unlink(missing_ok=True)
