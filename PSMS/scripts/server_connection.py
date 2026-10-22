"""PSMS deployment connection; never print credentials."""
from pathlib import Path
import base64
import paramiko

ROOT = Path(__file__).resolve().parents[1]
def read_env(path):
    result = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            result[key.strip()] = value.strip().strip('"').strip("'")
    return result

def connect():
    cfg = read_env(ROOT / '.env.deploy')
    jump = paramiko.SSHClient()
    jump.load_host_keys(str(Path.home() / '.ssh' / 'known_hosts'))
    jump.set_missing_host_key_policy(paramiko.RejectPolicy())
    jump.connect('100.65.200.125', username='lrz', key_filename=str(Path.home() / '.ssh' / 'bpoc_ed25519'), timeout=15, allow_agent=False, look_for_keys=False)
    remote = paramiko.SSHClient()
    key = paramiko.Ed25519Key(data=base64.b64decode('AAAAC3NzaC1lZDI1NTE5AAAAIOsvbj6GysO7IqAG/yrajlRPU3GYryHM5YICyg3hehvy'))
    remote.get_host_keys().add('192.168.101.74', 'ssh-ed25519', key)
    remote.set_missing_host_key_policy(paramiko.RejectPolicy())
    channel = jump.get_transport().open_channel('direct-tcpip', ('192.168.101.74', 22), ('127.0.0.1', 0))
    options = {'username': cfg['NODE6_USERNAME'], 'sock': channel, 'timeout': 15, 'allow_agent': False, 'look_for_keys': False}
    if cfg.get('NODE6_PRIVATE_KEY'): options['key_filename'] = cfg['NODE6_PRIVATE_KEY']
    else: options['password'] = cfg['NODE6_PASSWORD']
    remote.connect('192.168.101.74', **options)
    return jump, remote, cfg

def run(remote, command, password=None):
    stdin, stdout, stderr = remote.exec_command(command, timeout=60)
    if password is not None: stdin.write(password + '\n'); stdin.flush()
    output = stdout.read().decode('utf-8', 'replace')
    errors = stderr.read().decode('utf-8', 'replace')
    status = stdout.channel.recv_exit_status()
    if status: raise RuntimeError(f'Command failed ({status}): {errors}')
    return output

if __name__ == '__main__':
    jump, remote, cfg = connect()
    try:
        print(run(remote, 'hostname; id; systemctl cat project-b-psms.service; ls -ld /opt/project-b/PSMS/backend /data/app/project-b-20260921/PSMS/production-dispatch; command -v gs_dump; command -v pg_dump; command -v python3'))
        password = cfg.get('NODE6_SUDO_PASSWORD') or cfg.get('NODE6_PASSWORD')
        print(run(remote, 'sudo -S -p "" id', password))
    finally: remote.close(); jump.close()
