"""Read-only verification of a staged EHMS candidate; no config, secrets, database or services are touched."""
import argparse
import hashlib
import io
import json
import pathlib
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--package", type=pathlib.Path, required=True)
parser.add_argument("--expect-sha256", required=True)
parser.add_argument("--running-jar", type=pathlib.Path, required=True)
args = parser.parse_args()

def digest(data):
    return hashlib.sha256(data).hexdigest()

actual = digest(args.package.read_bytes())
if actual != args.expect_sha256:
    raise SystemExit("CANDIDATE_CHECK_FAILED: package digest mismatch; no changes made")

with zipfile.ZipFile(args.package) as package:
    manifest = json.loads(package.read("manifest.json"))
    for name, expected in manifest["files"].items():
        path = pathlib.PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts or set(path.parts) & {".local", "private-deployment"}:
            raise SystemExit("CANDIDATE_CHECK_FAILED: invalid inventory; no changes made")
        if digest(package.read(name)) != expected:
            raise SystemExit("CANDIDATE_CHECK_FAILED: payload digest mismatch; no changes made")
    candidate_bytes = package.read("app/ehm-service.jar")

running_bytes = args.running_jar.read_bytes()
with zipfile.ZipFile(io.BytesIO(candidate_bytes)) as candidate, zipfile.ZipFile(io.BytesIO(running_bytes)) as running:
    prefix = "BOOT-INF/classes/com/bproject/ehm/"
    classes = lambda jar: {n for n in jar.namelist() if n.startswith(prefix) and n.endswith(".class")}
    new_classes, old_classes = classes(candidate), classes(running)
    running_only = sorted(old_classes - new_classes)
    different = sorted(n for n in new_classes & old_classes if digest(candidate.read(n)) != digest(running.read(n)))
    deps = lambda jar: {n for n in jar.namelist() if n.startswith("BOOT-INF/lib/") and n.endswith(".jar")}
    new_deps, old_deps = deps(candidate), deps(running)
    changed_deps = sorted(n for n in new_deps & old_deps if digest(candidate.read(n)) != digest(running.read(n)))
    dependency_review = bool(new_deps != old_deps or changed_deps)
    print(json.dumps({
        "readOnly": True, "packageVerified": True, "payloadFiles": len(manifest["files"]),
        "packageSha256": actual, "runningJarSha256": digest(running_bytes),
        "candidateJarSha256": digest(candidate_bytes), "runningOnlyClasses": running_only,
        "candidateOnlyClasses": sorted(new_classes-old_classes), "differentCommonClassCount": len(different),
        "changedCommonClasses": different,
        "runningOnlyDependencies": sorted(old_deps-new_deps), "candidateOnlyDependencies": sorted(new_deps-old_deps),
        "sameNamedButChangedDependencies": changed_deps,
        "runtimeReconciliationRequired": bool(running_only or dependency_review or different),
        "published": False,
    }, ensure_ascii=True, indent=2))
