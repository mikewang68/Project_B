"""Read-only SSH identity, configured sources, project directory and listener checks."""
import concurrent.futures
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'deploy'))
from deployment import load, PORTS, LOCAL_PORTS


def check(role, config):
    node = config['nodes'][role]
    command = ['ssh', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=10']
    effective = subprocess.run(command + ['-G', node['sshAlias']], capture_output=True, text=True, check=True)
    fields = dict(line.split(' ', 1) for line in effective.stdout.splitlines() if ' ' in line)
    if fields.get('hostname') != node['address'] or fields.get('user') != config['sshUser']:
        raise ValueError('SSH alias address or user differs from deployment configuration')
    script = '''import getpass,json,os,pathlib,subprocess,urllib.request
listeners=subprocess.check_output(['ss','-H','-lnt'],text=True).splitlines()
ports={int(line.split()[3].rsplit(':',1)[1]) for line in listeners}
result={'user':getpass.getuser(),'connection':os.environ.get('SSH_CONNECTION','').split(),
        'projectExists':pathlib.Path(PROJECT).is_dir(),'ports':list(ports)}
if APPLICATION:
    result['health']=json.load(urllib.request.urlopen('http://127.0.0.1:28182/actuator/health',timeout=5))['status']
print(json.dumps(result))
'''.replace('PROJECT', repr(config['remoteRoot'])).replace('APPLICATION', repr(role == 'application'))
    result = subprocess.run(command + [node['sshAlias'], 'python3', '-'], input=script.encode(),
                            capture_output=True, timeout=30, check=True)
    value = json.loads(result.stdout)
    if value['user'] != config['sshUser'] or not value['projectExists'] or PORTS[role] not in value['ports']:
        raise ValueError('Server user, project directory or service listener check failed')
    if len(value['connection']) != 4 or value['connection'][2] != node['address']:
        raise ValueError('Connected server address does not match configured role')
    if role == 'application':
        if value['connection'][0] != config['operatorSource']:
            raise ValueError('Actual operator source differs from configured allowed source')
        if config['applicationSource'] != value['connection'][2]:
            raise ValueError('Application source requires explicit review for this network topology')
        if value['health'] != 'UP' or not set(LOCAL_PORTS.values()).issubset(value['ports']):
            raise ValueError('Application health or existing tunnel listeners are not ready')
    return {'role': role, 'status': 'PASS', 'sshIdentityAndAddress': True,
            'projectDirectory': True, 'serviceListener': True,
            'applicationHealthAndForwarding': True if role == 'application' else None}


def main():
    config = load()
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        futures = {role: pool.submit(check, role, config) for role in PORTS}
        for role, future in futures.items():
            try:
                results.append(future.result())
            except Exception as error:
                results.append({'role': role, 'status': 'FAIL', 'errorType': type(error).__name__})
    output = ROOT / '.local/test-results/environment-check.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(results, indent=2), encoding='utf-8')
    for row in results:
        print(row['role'], row['status'])
    if not all(row['status'] == 'PASS' for row in results):
        raise SystemExit('Environment check failed. Review private deployment and trusted SSH configuration.')


if __name__ == '__main__':
    main()
