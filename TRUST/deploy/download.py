"""Download upstream release assets with SHA-256 verification. No system install."""
import hashlib, json, pathlib, sys, urllib.request
root = pathlib.Path(__file__).resolve().parent.parent
kind = sys.argv[1]
if kind == 'fabric':
    repo, tag, asset = 'hyperledger/fabric', 'v3.1.5', 'hyperledger-fabric-linux-amd64-3.1.5.tar.gz'
elif kind == 'ipfs':
    repo, tag, asset = 'ipfs/kubo', 'v0.43.0', 'kubo_v0.43.0_linux-amd64.tar.gz'
else:
    raise SystemExit('Unknown release')
req = urllib.request.Request(f'https://api.github.com/repos/{repo}/releases/tags/{tag}', headers={'User-Agent': 'b-project-trust'})
release = json.load(urllib.request.urlopen(req, timeout=30))
entry = next(a for a in release['assets'] if a['name'] == asset)
digest = entry.get('digest', '').removeprefix('sha256:')
if len(digest) != 64:
    raise SystemExit('Official asset digest missing')
dest = root / 'artifacts' / asset
dest.parent.mkdir(parents=True, exist_ok=True)
if not dest.exists() or hashlib.sha256(dest.read_bytes()).hexdigest() != digest:
    urllib.request.urlretrieve(entry['browser_download_url'], dest.with_suffix('.part'))
    if hashlib.sha256(dest.with_suffix('.part').read_bytes()).hexdigest() != digest:
        raise SystemExit('Checksum mismatch')
    dest.with_suffix('.part').replace(dest)
(dest.parent / f'{kind}-release.json').write_text(json.dumps({'version': tag, 'url': entry['browser_download_url'], 'sha256': digest}, indent=2))
print(dest)
