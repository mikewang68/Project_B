"""Retire only known old source paths after sync; preserve a copy and reject unexpected edits."""
import hashlib
import json
import pathlib
import shutil

root = pathlib.Path(__file__).resolve().parent.parent
entries = json.loads((root / 'deploy/retired-source-paths.json').read_text())
pending = []
for name, expected in entries.items():
    relative = pathlib.PurePosixPath(name)
    if relative.is_absolute() or '..' in relative.parts or not name.startswith(('backend/src/', 'frontend/src/')):
        raise SystemExit('Refusing invalid retired source path: ' + name)
    source = root / relative
    if not source.exists():
        continue
    if not source.resolve().is_relative_to(root.resolve()) or not source.is_file():
        raise SystemExit('Retired source escapes project: ' + name)
    actual = hashlib.sha256(source.read_bytes().replace(b'\r\n', b'\n')).hexdigest()
    if actual != expected:
        raise SystemExit('Remote source changed; review before retiring: ' + name)
    pending.append((source, relative))

for source, relative in pending:
    backup = root / 'runtime/source-history/feature-layout' / relative
    backup.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, backup)
    source.unlink()
print('Retired source paths:', len(pending))
