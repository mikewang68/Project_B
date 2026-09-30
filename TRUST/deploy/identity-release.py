"""Review/apply an isolated JAR release. Never creates services, migrates data or switches ingress."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import sys
import tempfile


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def artifact(manifest_path, module):
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if manifest.get('configVersion') != 'identity-v1' or manifest.get('deploymentState') != 'NOT_DEPLOYED':
        raise ValueError('Expected independent reviewed release manifest')
    item = next(a for a in manifest['artifacts'] if a['module'] == module and a['kind'] == 'jar')
    source = (manifest_path.parent / item['file']).resolve()
    if not source.is_relative_to(manifest_path.parent.resolve()) or digest(source) != item['sha256']:
        raise ValueError('Artifact path/digest mismatch')
    return source, item['sha256'], manifest['commit']


def stopped(root):
    # Linux operator command. Refuse every live owned PID, including ambiguous PID records.
    for record in (root / 'runtime/pids').glob('*.pid'):
        pid = record.read_text().strip()
        if not pid.isdigit():
            raise ValueError('Invalid PID record; inspect explicitly')
        if Path('/proc', pid).exists():
            raise ValueError('Stop the isolated instance and verify shutdown first')


def protected_root(root):
    return ('trust-wallet-remediation' in root or root.startswith('/data/app/')
            or bool(re.match(r'^/home/[^/]+/projects/b-project-trust', root)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('action', choices=['install', 'rollback'])
    ap.add_argument('--root', type=Path, required=True)
    ap.add_argument('--allowlist', type=Path, required=True)
    ap.add_argument('--instance', required=True)
    ap.add_argument('--manifest', type=Path)
    ap.add_argument('--apply', action='store_true')
    args = ap.parse_args()
    root = args.root.resolve(strict=True)
    module = root.name
    if module not in ('IAM', 'TRUST') or protected_root(str(root)):
        raise ValueError('Protected or invalid module root')
    config = json.loads(args.allowlist.read_text(encoding='utf-8'))
    item = config['instances'][args.instance]
    if config.get('schemaVersion') != 1 or root != Path(item['root']).resolve(strict=True):
        raise ValueError('Root not in selected instance allowlist')
    target = root / 'artifacts' / ('iam-backend.jar' if module == 'IAM' else 'trust-identity.jar')
    if target.parent.is_symlink() or target.is_symlink():
        raise ValueError('Symlink artifact target refused')
    history = root / 'runtime/releases'
    previous = history / 'previous.json'
    if args.action == 'install':
        if not args.manifest:
            raise ValueError('Release manifest required')
        source, sha, commit = artifact(args.manifest.resolve(strict=True), module)
    else:
        receipt = json.loads(previous.read_text(encoding='utf-8'))
        source = history / receipt['file']
        if not source.resolve().is_relative_to(history.resolve()) or digest(source) != receipt['sha256']:
            raise ValueError('Rollback artifact digest mismatch')
        sha, commit = receipt['sha256'], receipt['commit']
    print(json.dumps({'action': args.action, 'root': str(root), 'target': str(target), 'sha256': sha, 'commit': commit, 'apply': args.apply}))
    if not args.apply:
        return
    if os.name != 'posix' or not Path('/proc').is_dir():
        raise ValueError('Apply requires Linux instance ownership checks')
    stopped(root)
    history.mkdir(parents=True, exist_ok=True)
    target.parent.mkdir(parents=True, exist_ok=True)
    if args.action == 'install' and target.exists():
        old_sha = digest(target)
        old_file = old_sha + '.jar'
        shutil.copy2(target, history / old_file)
        old_commit = 'UNRECORDED_PREVIOUS_ISOLATED_ARTIFACT'
        current = history / 'current.json'
        if current.exists():
            old_commit = json.loads(current.read_text())['commit']
        previous.write_text(json.dumps({'file': old_file, 'sha256': old_sha, 'commit': old_commit}), encoding='utf-8')
    fd, temporary = tempfile.mkstemp(dir=target.parent, prefix='identity-release-')
    os.close(fd)
    try:
        shutil.copyfile(source, temporary)
        if digest(Path(temporary)) != sha:
            raise ValueError('Staged artifact digest mismatch')
        os.chmod(temporary, 0o640)
        os.replace(temporary, target)
        (history / 'current.json').write_text(json.dumps({'sha256': sha, 'commit': commit, 'action': args.action}), encoding='utf-8')
    finally:
        if Path(temporary).exists():
            Path(temporary).unlink()


if __name__ == '__main__':
    main()
