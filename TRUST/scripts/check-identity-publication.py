"""Audit IAM/TRUST Git blobs in the final tree and every outgoing commit; print rules, never values."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
RULES = {
    'private-key-material': re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----[\r\n]+[A-Za-z0-9+/=\r\n]{32,}'),
    'access-token': re.compile(rb'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|AKIA[A-Z0-9]{16}|xox[baprs]-[A-Za-z0-9-]{20,})\b'),
    'credential-url': re.compile(rb'(?:https?|postgres(?:ql)?|opengauss)://[^\s/:]+:[^\s/@]+@'),
}
PRIVATE_PARTS = {'.local', '.ssh', 'runtime', 'node_modules', 'target', 'dist', 'artifacts', '__pycache__'}
PRIVATE_SUFFIXES = {'.key', '.p12', '.pfx', '.dump', '.jar', '.class', '.zip', '.tar', '.gz'}
PRIVATE_DOC_DIRS = {'test-results', 'reports', 'evidence', 'handoffs', 'conversation-prompts'}
PRIVATE_DOC_NAMES = {'implementation-status.md', 'requirements-coverage.md',
                     'application-directory-migration.md', 'gateway-port-plan.md'}
SITE_SCRIPTS = {'identity-forwarding.py', 'install-identity-forwarding-admin.sh',
                'identity-database.py', 'isolated-database.py'}


def local_only_path(name):
    """Operational reports stay private even when forcibly added or later deleted."""
    path = pathlib.PurePosixPath(name)
    parts = path.parts
    if len(parts) < 3 or parts[0] not in ('IAM', 'TRUST'):
        return False
    if parts[1] == 'docs':
        return bool(PRIVATE_DOC_DIRS.intersection(parts[2:]) or path.name in PRIVATE_DOC_NAMES
                    or re.search(r'(?:report|review|acceptance|deployment|worklog)[-_]?20\d{6}', path.stem, re.I))
    return parts[1] == 'deploy' and path.name in SITE_SCRIPTS


def known_identifiers():
    """Use private deployment configuration as a local denylist; never print values."""
    found = set()
    keys = {'sshUser', 'sshAlias', 'address', 'accessAddress', 'operatorSource', 'applicationSource'}
    def collect(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key in keys and isinstance(item, str) and len(item) >= 3:
                    found.add(item.encode())
                collect(item)
        elif isinstance(value, list):
            for item in value: collect(item)
    for module in ('IAM', 'TRUST'):
        path = ROOT / module / '.local/deployment.json'
        if path.is_file():
            collect(json.loads(path.read_text(encoding='utf-8-sig')))
        denylist = ROOT / module / '.local/publication-identifiers.json'
        if denylist.is_file():
            values = json.loads(denylist.read_text(encoding='utf-8-sig'))
            if not isinstance(values, list) or any(not isinstance(v, str) or len(v) < 3 for v in values):
                raise ValueError('Invalid private publication identifier list')
            found.update(v.encode() for v in values)
    return {v for v in found if not v.startswith((b'127.', b'192.0.2.', b'198.51.100.', b'203.0.113.'))}

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

def known_secrets():
    # Private files are read locally, never added to findings or copied into reports.
    found = set()
    def collect(value, sensitive=False):
        if isinstance(value, dict):
            for key, item in value.items():
                collect(item, sensitive or bool(re.search(r'password|secret|token$|jwt', key, re.I)))
        elif isinstance(value, list):
            for item in value: collect(item, sensitive)
        elif sensitive and isinstance(value, str) and len(value) >= 8 and not value.startswith(('/', 'http')):
            found.add(value.encode())
    for module in ('IAM', 'TRUST'):
        base = ROOT / module / '.local'
        if not base.exists(): continue
        for p in base.rglob('*.json'):
            if p.stat().st_size > 2_000_000 or any(x in p.parts for x in ('m2', 'tools', 'go-mod', 'github-objects', 'pnpm-store')): continue
            if not re.search(r'credential|account|secret|deployment|config', p.name, re.I): continue
            try: collect(json.loads(p.read_text(encoding='utf-8-sig')), p.name == 'db-credentials.json')
            except (ValueError, UnicodeError): pass
    return found

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--base', required=True, help='Freshly fetched remote main SHA')
    ap.add_argument('--head', default='HEAD')
    ap.add_argument('--output', type=pathlib.Path)
    args = ap.parse_args()
    base = git('rev-parse', args.base).decode().strip()
    head = git('rev-parse', args.head).decode().strip()
    commits = git('rev-list', head, '^' + base).decode().splitlines()
    paths = git('diff', '--name-only', base, head).decode().splitlines()
    findings = [{'path': p, 'rule': 'outside-module-scope'} for p in paths if not p.startswith(('IAM/', 'TRUST/'))]
    blobs = {}
    for commit in [head, *commits]:
        for line in git('ls-tree', '-rz', commit, '--', 'IAM', 'TRUST').split(b'\0'):
            if not line: continue
            metadata, rawpath = line.split(b'\t', 1)
            mode, kind, oid = metadata.decode().split()
            path = rawpath.decode()
            if kind != 'blob':
                findings.append({'path': path, 'rule': 'uninspected-object', 'commit': commit}); continue
            blobs.setdefault(oid, set()).add(path)
    secrets = known_secrets()
    identifiers = known_identifiers()
    identifier_pattern = (re.compile(rb'(?<![A-Za-z0-9_-])(?:' + b'|'.join(re.escape(v) for v in sorted(identifiers)) + rb')(?![A-Za-z0-9_-])')
                          if identifiers else None)
    binary = 0
    for oid, names in blobs.items():
        raw = git('cat-file', 'blob', oid)
        if b'\0' in raw: binary += 1
        rules = [rule for rule, pattern in RULES.items() if pattern.search(raw)]
        if any(value in raw for value in secrets): rules.append('known-private-credential')
        if identifier_pattern and identifier_pattern.search(raw): rules.append('known-deployment-identifier')
        for name in sorted(names):
            path = pathlib.PurePosixPath(name)
            if local_only_path(name):
                findings.append({'path': name, 'blob': oid, 'rule': 'local-only-operational-material'})
            if PRIVATE_PARTS.intersection(path.parts) or path.suffix.lower() in PRIVATE_SUFFIXES or (path.name.startswith('.env') and not path.name.endswith('.example')):
                findings.append({'path': name, 'blob': oid, 'rule': 'private-or-generated-path'})
            if path.suffix.lower() == '.pem' and not raw.startswith(b'-----BEGIN CERTIFICATE-----'):
                findings.append({'path': name, 'blob': oid, 'rule': 'unclassified-pem'})
            for rule in rules: findings.append({'path': name, 'blob': oid, 'rule': rule})
    report = {'base': base, 'head': head, 'outgoingCommits': len(commits), 'uniqueBlobs': len(blobs), 'binaryBlobsScanned': binary,
              'skippedBlobs': 0, 'knownPrivateValuesChecked': len(secrets),
              'knownDeploymentIdentifiersChecked': len(identifiers), 'findings': findings,
              'limits': 'Final tree and outgoing commits only, not inherited main history or GitHub PR references. Known-value checks depend on local private configuration. Public certificates and synthetic test passwords are not real credentials. Manual content review remains required.'}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 1 if findings else 0

if __name__ == '__main__': sys.exit(main())
