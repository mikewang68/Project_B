"""Read-only static configuration gate. Does NOT connect, deploy, migrate or enable TRUST."""
import argparse
import json
import os
from pathlib import Path
import re
import stat
import sys
import zipfile
import hashlib


def read_env(path):
    values = {}
    for raw in path.read_text(encoding='utf-8-sig').splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('export '):
            line = line[7:]
        if '=' not in line:
            raise ValueError('Environment file has a non-assignment line; it is never executed')
        key, value = line.split('=', 1)
        if not re.fullmatch(r'[A-Z][A-Z0-9_]*', key):
            raise ValueError('Invalid configuration key')
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        if '$(' in value or '`' in value or '\x00' in value:
            raise ValueError('Executable interpolation is not allowed in checked configuration')
        if key in values:
            raise ValueError('Duplicate configuration key: ' + key)
        values[key] = value
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit-env', type=Path, required=True)
    parser.add_argument('--ehm-env', type=Path, required=True)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    checks = []
    def check(name, ok, reason):
        checks.append(dict(name=name, passed=bool(ok), reason=reason))
    try:
        audit, ehm = read_env(args.audit_env), read_env(args.ehm_env)
        for kind, values, required in (
            ('AUDIT', audit, ('AUDIT_DB_URL', 'AUDIT_DB_USER', 'AUDIT_DB_PASSWORD', 'AUDIT_CLIENTS_FILE')),
            ('EHM', ehm, ('EHM_OPENGAUSS_HOST', 'EHM_OPENGAUSS_USERNAME', 'EHM_OPENGAUSS_PASSWORD', 'EHM_AUDIT_DOMAIN', 'EHM_AUDIT_ZONE', 'EHM_AUDIT_ENDPOINT', 'EHM_AUDIT_TOKEN_FILE', 'EHM_AUDIT_IAM_ME_URL')),
        ):
            for key in required:
                value = values.get(key, '')
                check(kind + ':' + key, bool(value) and not any(x in value.lower() for x in ('__fill_me__', '.example.', 'unassigned')), 'Required non-placeholder value; its contents are not exported')
        check('AUDIT:auto-ddl-disabled', audit.get('AUDIT_AUTO_DDL', 'false').lower() == 'false', 'DDL requires a separately authorized migration account')
        check('AUDIT:relational-database-url', audit.get('AUDIT_DB_URL', '').startswith('jdbc:postgresql:'), 'Server profile must not silently use a local H2/Mongo database')
        check('EHM:auto-ddl-disabled', ehm.get('EHM_OPENGAUSS_AUTO_DDL', 'false').lower() == 'false', 'Apply reviewed 002/003 migrations before startup')
        check('AUDIT:chain-stays-disabled', audit.get('AUDIT_CHAIN_ENABLED', 'false').lower() == 'false', 'This waiting-stage check never grants archival or chain authorization')
        check('EHM:verified-identity-required', ehm.get('EHM_AUDIT_IAM_REQUIRED', '').lower() == 'true', 'Without this, absent sessions may remain anonymous')
        for path in (args.audit_env, args.ehm_env):
            check('private-environment-file-permissions', os.name == 'nt' or not (path.stat().st_mode & (stat.S_IRWXG | stat.S_IRWXO)), 'On Windows verify the file ACL separately; on Linux require owner-only permissions')
        if ehm.get('EHM_AUDIT_EVIDENCE_EXPORT_ENABLED', 'false').lower() == 'true':
            check('EHM:export-has-explicit-actors', bool(ehm.get('EHM_AUDIT_EVIDENCE_EXPORT_ACTORS', '').strip()), 'Only approved stable IAM user IDs, never front-end role labels')
        check('AUDIT:no-unrestricted-bind', audit.get('AUDIT_BIND', '127.0.0.1') not in ('0.0.0.0', '::'), 'Use an approved private interface/proxy, not an unrestricted listener')
        token = Path(ehm.get('EHM_AUDIT_TOKEN_FILE', ''))
        clients = Path(audit.get('AUDIT_CLIENTS_FILE', ''))
        check('EHM:writer-file-exists', token.is_file(), 'Token is read locally, never printed')
        check('AUDIT:clients-file-exists', clients.is_file(), 'Service identities must already be provisioned privately')
        if token.is_file() and clients.is_file():
            writer = token.read_text(encoding='utf-8').strip()
            check('EHM:writer-credential-format', len(writer) >= 32 and not any(c.isspace() for c in writer), 'Synthetic/real service credential must meet backend validation')
            for path in (token, clients):
                check('private-credential-file-permissions', os.name == 'nt' or not (path.stat().st_mode & (stat.S_IRWXG | stat.S_IRWXO)), 'Owner-only on Linux; verify Windows ACL separately')
            identities = json.loads(clients.read_text(encoding='utf-8-sig'))
            found = [x for x in identities if x.get('token') == writer]
            check('EHM:writer-exact-scope', len(found) == 1 and found[0].get('appCode') == 'ehealth' and found[0].get('domainId') == ehm.get('EHM_AUDIT_DOMAIN') and found[0].get('zoneId') == ehm.get('EHM_AUDIT_ZONE') and 'WRITE' in found[0].get('permissions', []), 'Compare backend token and scope without exporting either credential')
        repo = Path(__file__).resolve().parents[2]
        sdk = repo / 'AUDIT/client/target/audit-client-1.0.1.jar'
        for name in ('AUDIT/service/target/audit-service-1.1.0.jar', 'EHMS/source/backend/target/ehm-demo-0.1.0.jar'):
            jar = repo / name
            if jar.is_file() and sdk.is_file():
                with zipfile.ZipFile(jar) as z:
                    same = hashlib.sha256(z.read('BOOT-INF/lib/audit-client-1.0.1.jar')).digest() == hashlib.sha256(sdk.read_bytes()).digest()
                check('artifact:' + name, same, 'Embedded SDK matches the current built SDK exactly')
            else:
                check('artifact:' + name, False, 'Build both services and SDK before deployment')
    except Exception as error:
        # Never echo exception text: parsers can contain private field values or raw config paths.
        check('configuration-readable-and-valid', False, 'Parsing/permission/artifact error; inspect privately. Type: ' + type(error).__name__)
    result = dict(scope='STATIC_ONLY_NO_NETWORK_NO_DDL_NO_DEPLOY', passed=all(c['passed'] for c in checks), checks=checks,
                  notVerified=['database privileges and connectivity', 'actual IAM identity/authorization', 'TRUST/IPFS/Fabric', 'content archival approval', 'Windows ACL'])
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    return 0 if result['passed'] else 2


if __name__ == '__main__':
    sys.exit(main())
