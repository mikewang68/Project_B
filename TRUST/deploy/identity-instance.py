"""Read-only launch guard. Explicit allowlist, protected-resource denial, no server mutations."""
import json
import os
from pathlib import Path
import re
import sys


def protected_root(root):
    return ('trust-wallet-remediation' in root or root.startswith('/data/app/')
            or bool(re.match(r'^/home/[^/]+/projects/b-project-trust', root)))


def check(config, name, actual_root, env):
    item = config.get('instances', {}).get(name)
    if config.get('schemaVersion') != 1 or not item:
        raise ValueError('Instance not in allowlist')
    root = Path(item['root'])
    if not root.is_absolute() or root.resolve() != Path(actual_root).resolve() or root.name != 'TRUST':
        raise ValueError('Instance root mismatch')
    if '..' in root.parts or protected_root(str(root)):
        raise ValueError('Protected root')
    if not re.fullmatch(r'jdbc:opengauss://127\.0\.0\.1:25432/trust_iam_[a-z0-9_]{1,32}', item['databaseUrl']):
        raise ValueError('Protected database')
    if not re.fullmatch(r'trust_iam_[a-z0-9_]{1,24}_app', item['appRole']):
        raise ValueError('Invalid runtime role')
    if not re.fullmatch(r'trust_iam_[a-z0-9_]{1,24}_migrate', item['migrationRole']):
        raise ValueError('Invalid migration role')
    if not re.fullmatch(r'trust-iam-[a-z0-9-]{1,32}', item['channel']):
        raise ValueError('Protected channel')
    if not 1024 <= item['port'] <= 65535 or item['port'] in (28182, 28183, 18080, 18091):
        raise ValueError('Protected port')
    expected = {'TRUST_DB_URL': item['databaseUrl'], 'TRUST_DB_USER': item['appRole'],
                'TRUST_FABRIC_CHANNEL': item['channel'], 'SERVER_PORT': str(item['port']),
                'TRUST_FABRIC_QUERY_KEY_REF': item['queryKeyRef']}
    if any(env.get(key) != value for key, value in expected.items()):
        raise ValueError('Environment differs from allowlist')
    return item


if __name__ == '__main__':
    try:
        filename = Path(os.environ['TRUST_INSTANCE_ALLOWLIST'])
        if not filename.is_absolute():
            raise ValueError('Absolute allowlist required')
        check(json.loads(filename.read_text()), os.environ['TRUST_INSTANCE'], os.environ['TRUST_ROOT'], os.environ)
        print('Explicit TRUST instance guard passed')
    except (KeyError, ValueError, OSError):
        sys.exit('Instance guard rejected configuration; no operation performed')
