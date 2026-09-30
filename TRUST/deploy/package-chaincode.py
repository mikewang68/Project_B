"""Build deterministic legacy or explicitly isolated CCAAS packages."""
import argparse
import gzip
import io
import json
import pathlib
import re
import tarfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("org", type=int, choices=(1, 2))
parser.add_argument("version", type=int, nargs="?", default=1, choices=(1, 2, 21))
parser.add_argument("base", type=int, nargs="?", default=27159)
parser.add_argument("channel", nargs="?")
args = parser.parse_args()
if args.channel and not re.fullmatch(r"trust-[a-z0-9][a-z0-9-]{0,59}", args.channel):
    parser.error("Only an explicit isolated trust-* channel is permitted")
base = args.base if args.channel or args.version != 1 else 27059
if not 1024 <= base <= 63535:
    parser.error("Base port and base+2000 must both be in 1024..65535")
root = pathlib.Path(__file__).resolve().parent.parent
ca = (root / f"runtime/secrets/crypto/peerOrganizations/org{args.org}.trust/peers/"
      f"peer0.org{args.org}.trust/tls/ca.crt").read_text()
connection = {"address": f"127.0.0.1:{base + (args.org - 1) * 2000}",
              "dial_timeout": "10s", "tls_required": True,
              "client_auth_required": False, "root_cert": ca}


def tar_bytes(files):
    stream = io.BytesIO()
    with gzip.GzipFile(fileobj=stream, mode="wb", mtime=0) as zipped:
        with tarfile.open(fileobj=zipped, mode="w") as archive:
            for name, value in sorted(files.items()):
                info = tarfile.TarInfo(name)
                info.size, info.mode, info.mtime = len(value), 0o644, 0
                archive.addfile(info, io.BytesIO(value))
    return stream.getvalue()


suffix = f"-v{args.version}" if args.version != 1 else ""
label = (f"{args.channel}-evidence-org{args.org}-v{args.version}" if args.channel
         else f"evidence-org{args.org}{suffix}")
filename = f"{label}.tar.gz" if args.channel else f"evidence{args.org}{suffix}.tar.gz"
code = tar_bytes({"connection.json": json.dumps(connection, sort_keys=True).encode()})
package = tar_bytes({"metadata.json": json.dumps({"type": "ccaas", "label": label}, sort_keys=True).encode(),
                     "code.tar.gz": code})
target = root / "artifacts" / filename
if args.channel and target.exists():
    if target.read_bytes() != package:
        raise SystemExit("Refusing to overwrite a different isolated package; use a new channel/version")
else:
    # Preserve legacy CLI paths; isolated callers always pass an explicit channel.
    with target.open("xb" if args.channel else "wb") as output:
        output.write(package)
print(target)
