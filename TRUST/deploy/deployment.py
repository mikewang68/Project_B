"""Shared, side-effect-free deployment configuration for operator and server tools."""
import argparse
import ipaddress
import json
import os
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
ROLES = ('ipfs', 'database', 'fabric', 'application')
PORTS = {'ipfs': 5001, 'database': 25432, 'fabric': 27051, 'application': 28182}
LOCAL_PORTS = {'ipfs': 25001, 'database': 25432, 'fabric': 27051}


def validate(config):
    if not isinstance(config, dict) or config.get('schemaVersion') != 1:
        raise ValueError('Deployment schemaVersion must be 1')
    if config.get('isExample', False) is not False:
        raise ValueError('Fill the example with your own environment and set isExample to false')
    required = ('sshUser', 'remoteRoot', 'databaseOwner', 'operatorSource', 'applicationSource', 'nodes')
    for key in required:
        if not config.get(key):
            raise ValueError('Missing deployment field: ' + key)
    if not isinstance(config['nodes'], dict):
        raise ValueError('nodes must be an object')
    for key, pattern in [('sshUser', r'[a-z_][a-z0-9_-]{0,31}'),
                         ('databaseOwner', r'[a-z_][a-z0-9_]{0,62}')]:
        if not isinstance(config[key], str) or not re.fullmatch(pattern, config[key]):
            raise ValueError('Invalid deployment field: ' + key)
    remote = config['remoteRoot']
    if not isinstance(remote, str) or not re.fullmatch(r'/[A-Za-z0-9_./-]+', remote):
        raise ValueError('remoteRoot must be an absolute POSIX path without spaces or shell metacharacters')
    if '..' in remote.split('/') or len(pathlib.PurePosixPath(remote).parts) < 3:
        raise ValueError('remoteRoot must name a project directory')
    addresses = []
    aliases = []
    for role in ROLES:
        node = config['nodes'].get(role)
        if not isinstance(node, dict):
            raise ValueError('Missing node role: ' + role)
        alias = node.get('sshAlias', '')
        if not isinstance(alias, str) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_.-]*', alias):
            raise ValueError('Invalid SSH alias for role: ' + role)
        try:
            if not isinstance(node.get('address'), str):
                raise ValueError()
            ipaddress.IPv4Address(node.get('address', ''))
        except (ValueError, TypeError):
            raise ValueError('Invalid IPv4 address for role: ' + role) from None
        aliases.append(alias)
        addresses.append(node['address'])
    if len(set(addresses)) != len(ROLES) or len(set(aliases)) != len(ROLES):
        raise ValueError('Each role needs its own machine address and SSH alias; IPFS must be independent')
    for key in ('operatorSource', 'applicationSource'):
        try:
            if not isinstance(config[key], str):
                raise ValueError()
            ipaddress.IPv4Address(config[key])
        except (ValueError, TypeError):
            raise ValueError('Invalid IPv4 address in ' + key) from None
    if 'httpAccess' in config:
        from http_access import validate as validate_http_access
        validate_http_access(config)
    return config


def load(path=None):
    explicit = path or os.environ.get('TRUST_DEPLOYMENT_FILE')
    if explicit:
        selected = pathlib.Path(explicit).expanduser()
    else:
        candidates = [ROOT / '.local/deployment.json', ROOT / 'runtime/secrets/deployment.json']
        selected = next((p for p in candidates if p.is_file()), candidates[0])
    if not selected.is_file():
        raise ValueError('Deployment configuration missing. Copy deploy/deployment.example.json to '
                         '.local/deployment.json, fill it, or set TRUST_DEPLOYMENT_FILE; '
                         'servers use runtime/secrets/deployment.json')
    try:
        return validate(json.loads(selected.read_text(encoding='utf-8-sig')))
    except (json.JSONDecodeError, UnicodeError):
        raise ValueError('Deployment configuration is not valid UTF-8 JSON') from None


def environment(config):
    values = {'TRUST_SSH_USER': config['sshUser'], 'TRUST_REMOTE_ROOT': config['remoteRoot'].rstrip('/'),
              'TRUST_DATABASE_OWNER': config['databaseOwner'], 'TRUST_OPERATOR_SOURCE': config['operatorSource'],
              'TRUST_APPLICATION_SOURCE': config['applicationSource']}
    for role in ROLES:
        values['TRUST_' + role.upper() + '_ALIAS'] = config['nodes'][role]['sshAlias']
        values['TRUST_' + role.upper() + '_ADDRESS'] = config['nodes'][role]['address']
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config')
    parser.add_argument('--format', choices=('check', 'json', 'env'), default='check')
    args = parser.parse_args()
    try:
        config = load(args.config)
    except (ValueError, OSError) as error:
        parser.exit(2, str(error) + '\n')
    if args.format == 'json':
        print(json.dumps(config))
    elif args.format == 'env':
        for key, value in environment(config).items():
            print(key + '=' + value)
    else:
        print('Deployment configuration valid: four independent roles; no credentials printed')


if __name__ == '__main__':
    main()
