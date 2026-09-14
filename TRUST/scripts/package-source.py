"""Package this module's source, excluding private state and the parent repository."""
import argparse
import os
import pathlib
import tarfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOP_LEVEL = {'backend', 'frontend', 'contracts', 'deploy', 'scripts', 'tests', 'samples', 'docs',
             'AGENTS.md', 'README.md', '.gitignore', '.gitattributes'}
EXCLUDED = {'.git', '.local', 'runtime', 'tools', 'artifacts', 'node_modules', 'target', 'dist', '__pycache__'}


def source_files(root=ROOT):
    for top in sorted(TOP_LEVEL):
        entry = root / top
        if not entry.exists():
            continue
        if entry.is_symlink():
            raise ValueError('Source symlinks are not supported: ' + top)
        if entry.is_file():
            yield entry
            continue
        for folder, directories, files in os.walk(entry, followlinks=False):
            for name in list(directories):
                path = pathlib.Path(folder) / name
                if name in EXCLUDED or path.relative_to(root).as_posix() == 'backend/src/main/resources/static':
                    directories.remove(name)
                elif path.is_symlink():
                    raise ValueError('Source symlinks are not supported')
            for name in sorted(files):
                path = pathlib.Path(folder) / name
                if path.is_symlink():
                    raise ValueError('Source symlinks are not supported')
                if name.endswith(('.key', '.pem', '.env', '.log', '.pid', '.pyc')) or name.startswith('.env') and name != '.env.example':
                    continue
                yield path


def package(output, root=ROOT):
    paths = list(source_files(root))
    output = pathlib.Path(output).resolve()
    if output in [p.resolve() for p in paths]:
        raise ValueError('Archive output must be outside packaged source (use .local/)')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(output, 'w:gz') as archive:
        for path in paths:
            info = archive.gettarinfo(str(path), arcname=path.relative_to(root).as_posix())
            info.uid = info.gid = 0
            info.uname = info.gname = ''
            info.mode = 0o755 if path.suffix == '.sh' else 0o644
            with path.open('rb') as stream:
                archive.addfile(info, stream)
    return len(paths)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path, default=ROOT / '.local/source.tar.gz')
    parser.add_argument('--list', action='store_true')
    args = parser.parse_args()
    if args.list:
        print('\n'.join(p.relative_to(ROOT).as_posix() for p in source_files()))
    else:
        print('Packaged module source files:', package(args.output))
