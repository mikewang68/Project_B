"""Scan Git upload candidates without printing secret values. Does not scan Git history."""
import pathlib
import json
import re
import subprocess
import sys
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[2]
RULES = {
    'personal-path': re.compile(r'[A-Z]:[\\/]Users[\\/](?!developer[\\/])[^\\/\s]+', re.I),
    'private-key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'),
    'token': re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|AKIA[A-Z0-9]{16}|xox[baprs]-[A-Za-z0-9-]{20,})\b'),
}
def main():
    staged = set(subprocess.check_output(['git', 'diff', '--cached', '--name-only', '-z'], cwd=ROOT).decode().split('\0')) - {''}
    unstaged = set(subprocess.check_output(['git', 'diff', '--name-only', '-z'], cwd=ROOT).decode().split('\0')) - {''}
    stale = staged & unstaged
    if stale:
        print('Staged content differs from working files; review and stage the final versions first:')
        print('\n'.join(sorted(stale)))
        return True
    private_config = ROOT / 'TRUST/.local/deployment.json'
    identifiers = set()
    def collect(value):
        if not isinstance(value, dict):
            return
        for key, item in value.items():
            if key in {'sshUser', 'sshAlias', 'address', 'accessAddress', 'operatorSource', 'applicationSource'} and isinstance(item, str):
                identifiers.add(item)
            if key == 'publicUrl':
                identifiers.add(urllib.parse.urlsplit(item).hostname)
            if key == 'clientCidrs':
                identifiers.update(v.split('/')[0] for v in item)
            if isinstance(item, dict):
                collect(item)
            elif isinstance(item, list):
                for row in item:
                    collect(row)
    if private_config.is_file():
        collect(json.loads(private_config.read_text(encoding='utf-8-sig')))
    if identifiers:
        RULES['deployment-identity'] = re.compile('|'.join(re.escape(v) for v in identifiers if v), re.I)
    account_file = ROOT / 'TRUST/.local/development-accounts.json'
    known_passwords = []
    if account_file.is_file():
        known_passwords = [a['password'] for a in json.loads(account_file.read_text(encoding='utf-8-sig')) if len(a.get('password', '')) >= 6]
    names = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=ROOT).decode().split('\0')
    findings = []
    count = 0
    skipped = 0
    for name in sorted(set(names) - {''}):
        path = ROOT / name
        if not path.is_file():
            continue
        count += 1
        if any(part in {'.local', '.ssh', 'node_modules'} for part in pathlib.PurePosixPath(name).parts) or name.startswith('TRUST/runtime/') or path.suffix.lower() in {'.pem', '.key', '.p12', '.pfx'}:
            findings.append((name, 0, 'private-file-path'))
        if path.stat().st_size > 5_000_000:
            skipped += 1
            continue
        raw = path.read_bytes()
        if b'\0' in raw:
            skipped += 1
            continue
        for number, line in enumerate(raw.decode('utf-8', errors='replace').splitlines(), 1):
            if any(value in line for value in known_passwords):
                findings.append((name, number, 'known-private-password'))
            for label, rule in RULES.items():
                if rule.search(line):
                    findings.append((name, number, label))
    for name, number, label in findings:
        print(f'{name}:{number}: {label}')
    print(f'Checked {count} upload candidate paths; {len(findings)} findings; {skipped} binary/large files skipped. Values omitted. Git history not covered.')
    return bool(findings)

if __name__ == '__main__':
    sys.exit(main())
