"""Root-operated, scoped EHMS publication. Keeps unchanged runtime classes/dependencies and all database/account rows.

No passwords or keys are accepted in arguments. --check is read-only; --apply requires sudo in the operator's terminal.
"""
import argparse
import hashlib
import io
import json
import os
import pathlib
import re
import shlex
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

VERSION = "EHMS-restoration-ai-20261106-r3"
SERVICE = "project-b-unified-ehms.service"
EXPECTED_RUNNING = "4a5f29946fdb75e3c8020865dfa250209215cb3f3053830adc5cde5a275a00ed"
EXPECTED_PACKAGE = "e49a0625f1edfaa2d9c11fa4aae430bb0c94185f2e6894ff1c230ddefaf77c6a"
STAGE = pathlib.Path("/home/bpoc-data/projects/Project_B/releases/EHMS-restoration-ai-20261106")
OLD_WEB = pathlib.Path("/var/www/project-b-unified/releases/EHMS-running-v1.9.0-r4-20261102/ehms")
NEW_WEB = pathlib.Path("/var/www/project-b-unified/releases") / VERSION / "ehms"
COMPONENT_ROOT = pathlib.Path("/opt/project-b-unified/components")
RUNTIME = COMPONENT_ROOT / VERSION
WORK = pathlib.Path("/var/lib/project-b-unified/ehms")
KEY_SOURCE_ROOT = pathlib.Path("/home/yanxinyu/.ehms-ai-private-20261106")
UNIT_DIR = pathlib.Path("/etc/systemd/system") / (SERVICE + ".d")
PRIVATE_NAME = "ai-private-20261106-r3"
RECEIPT_NAME = "private-deployment-r3"
BACKUP_NAME = "ehms.before-ai-20261106-r3"

def require(condition, code):
    if not condition:
        raise RuntimeError(code)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout.strip()

def service_property(name):
    return run("systemctl", "show", SERVICE, "--value", "--property=" + name)

def class_source(data):
    """Resolve a class's package/SourceFile (also handles nested and package-private classes)."""
    require(data[:4] == b"\xca\xfe\xba\xbe", "INVALID_CLASS")
    offset = 8
    def u2():
        nonlocal offset
        result = struct.unpack_from(">H", data, offset)[0]
        offset += 2
        return result
    count = u2()
    cp = [None] * count
    i = 1
    while i < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = u2()
            cp[i] = data[offset:offset + length].decode("utf-8", errors="replace")
            offset += length
        elif tag == 7:
            cp[i] = u2()
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            i += 1
        elif tag in (8, 16, 19, 20):
            offset += 2
        elif tag == 15:
            offset += 3
        else:
            raise RuntimeError("UNKNOWN_CLASS_FORMAT")
        i += 1
    u2()
    name = cp[cp[u2()]]
    u2()
    interfaces = u2()
    offset += 2 * interfaces
    def attributes():
        nonlocal offset
        result = {}
        for _ in range(u2()):
            attribute_name = cp[u2()]
            length = struct.unpack_from(">I", data, offset)[0]
            offset += 4
            result[attribute_name] = data[offset:offset + length]
            offset += length
        return result
    for _ in range(2):
        for _ in range(u2()):
            offset += 6
            attributes()
    source = attributes().get("SourceFile")
    require(source is not None, "CLASS_SOURCE_UNAVAILABLE")
    filename = cp[struct.unpack(">H", source)[0]]
    package = name.rsplit("/", 1)[0]
    return package + "/" + filename

def build_scoped_jar(running_bytes, candidate_bytes, sources):
    """Patch only SourceFile families included in the reviewed source update; retain all other live bytecode."""
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(running_bytes)) as current, zipfile.ZipFile(io.BytesIO(candidate_bytes)) as candidate:
        prefix = "BOOT-INF/classes/"
        replacements = {}
        covered = set()
        current_names = set(current.namelist())
        candidate_names = set(candidate.namelist())
        for name in candidate_names:
            if name.startswith(prefix + "com/bproject/ehm/") and name.endswith(".class"):
                source = class_source(candidate.read(name))
                if source in sources:
                    replacements[name] = candidate.read(name)
                    covered.add(source)
                elif name not in current_names:
                    raise RuntimeError("UNREVIEWED_NEW_CLASS")
        require(covered == sources, "SOURCE_CLASS_INVENTORY_INCOMPLETE")
        # Do not silently drop an old nested/package-private class belonging to an updated source family.
        for name in current_names:
            if name.startswith(prefix + "com/bproject/ehm/") and name.endswith(".class"):
                if class_source(current.read(name)) in sources:
                    require(name in candidate_names, "UPDATED_CLASS_REMOVAL_REQUIRES_REVIEW")
        with zipfile.ZipFile(output, "w") as result:
            for info in current.infolist():
                require(not re.match(r"META-INF/.*\.(SF|RSA|DSA)$", info.filename), "SIGNED_JAR_REQUIRES_REBUILD")
                result.writestr(info, replacements.get(info.filename, current.read(info.filename)))
            for name in sorted(set(replacements) - current_names):
                result.writestr(candidate.getinfo(name), replacements[name])
    return output.getvalue(), len(replacements)

def read_package(path):
    data = path.read_bytes()
    require(sha(data) == EXPECTED_PACKAGE, "PACKAGE_DIGEST_MISMATCH")
    with zipfile.ZipFile(io.BytesIO(data)) as package:
        manifest = json.loads(package.read("manifest.json"))
        require(set(package.namelist()) == set(manifest["files"]) | {"manifest.json"}, "UNLISTED_PAYLOAD")
        for name, expected in manifest["files"].items():
            p = pathlib.PurePosixPath(name)
            require(not p.is_absolute() and ".." not in p.parts and "\\" not in name, "INVALID_PACKAGE_PATH")
            require(sha(package.read(name)) == expected, "PAYLOAD_DIGEST_MISMATCH")
        source_prefix = "source-update/EHMS/source/backend/src/main/java/"
        sources = {n[len(source_prefix):] for n in package.namelist() if n.startswith(source_prefix) and n.endswith(".java")}
        web = {n[4:]: package.read(n) for n in package.namelist() if n.startswith("web/")}
        require(b"assistantModel" in web["index.html"] and b"2.1.2-multi-model" in web["index.html"], "WRONG_FRONTEND")
        return package.read("app/ehm-service.jar"), sources, web

def probe(url):
    try:
        with urllib.request.urlopen(url, timeout=5) as response:
            return response.status, response.read(2_000_000)
    except urllib.error.HTTPError as error:
        return error.code, b""
    except (OSError, TimeoutError):
        return 0, b""

def systemd_argument(value):
    require("\n" not in value and "\r" not in value and "%" not in value, "UNSAFE_EXEC_ARGUMENT")
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'

def write_private_text(path, content):
    # pathlib.Path.open has no opener argument. builtin open preserves O_EXCL and creation mode 600.
    with open(path,"x",encoding="utf-8",opener=lambda target,flags: os.open(target,flags,0o600)) as output:
        output.write(content)

def verify_lab_connectivity(base_url, key, allow_network_unavailable=False):
    """Optional network availability is not an authentication or key-validity bypass."""
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, response, code, message, headers, url):
            return None
    payload = {"model":"Qwen3.8-27B","messages":[{"role":"user","content":"Connectivity test only. Reply OK."}],
               "max_tokens":128,"stream":False,"chat_template_kwargs":{"enable_thinking":False}}
    try:
        request = urllib.request.Request(base_url.rstrip("/")+"/chat/completions",
            data=json.dumps(payload).encode(),headers={"Authorization":"Bearer "+key,"Content-Type":"application/json"})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
        with opener.open(request,timeout=45) as response:
            reply = json.loads(response.read(1_000_000))
        require(bool(reply.get("choices",[{}])[0].get("message",{}).get("content")),"LAB_MODEL_EMPTY_RESPONSE")
        return True
    except urllib.error.HTTPError as error:
        # Never suppress a bad key, denied access, model error or provider redirect.
        raise RuntimeError("LAB_MODEL_HTTP_"+str(error.code)) from None
    except (urllib.error.URLError, TimeoutError, OSError):
        if allow_network_unavailable:
            return False
        raise RuntimeError("LAB_MODEL_NETWORK_UNREACHABLE") from None
    except RuntimeError:
        raise
    except Exception:
        raise RuntimeError("LAB_MODEL_INVALID_RESPONSE") from None

def main(args):
    candidate, sources, web = read_package(STAGE / "EHMS-AI-update-verified.zip")
    require(service_property("User") == "leiwen" and service_property("WorkingDirectory") == str(WORK), "SERVICE_BASELINE_CHANGED")
    require(service_property("ActiveState") == "active", "EXISTING_SERVICE_NOT_ACTIVE")
    exec_value = service_property("ExecStart")
    match = re.search(r"argv\[\]=(.*?) ; ignore_errors=", exec_value)
    require(match is not None, "UNRECOGNIZED_EXECSTART")
    command = shlex.split(match.group(1))
    require(command.count("-jar") == 1, "UNRECOGNIZED_JAVA_COMMAND")
    jar_index = command.index("-jar") + 1
    current_jar = pathlib.Path(command[jar_index])
    require(current_jar.is_relative_to(COMPONENT_ROOT) and current_jar.suffix == ".jar", "UNEXPECTED_RUNNING_PATH")
    current_bytes = current_jar.read_bytes()
    require(sha(current_bytes) == EXPECTED_RUNNING, "RUNNING_BASELINE_CHANGED")
    merged, class_count = build_scoped_jar(current_bytes, candidate, sources)
    require(OLD_WEB.is_dir() and not OLD_WEB.is_symlink(), "STATIC_BASELINE_CHANGED")
    require(b"ai-assistant.js?v=1.6.1" in (OLD_WEB / "index.html").read_bytes(), "STATIC_VERSION_CHANGED")
    if not args.apply:
        print(json.dumps({"readOnly": True, "packageVerified": True, "updatedClassFiles": class_count,
            "mergedJarSha256": sha(merged), "runtimeDependenciesPreserved": True, "unchangedRuntimeClassesPreserved": True,
            "accountsOrDatabaseChanged": False, "published": False}))
        return
    require(os.geteuid() == 0, "SUDO_REQUIRED")
    require(args.lab_key_file is not None and args.lab_base_url is not None, "LAB_PRIVATE_CONFIGURATION_REQUIRED")
    from urllib.parse import urlsplit
    base = urlsplit(args.lab_base_url)
    require(base.scheme in {"http", "https"} and base.hostname and not base.username and not base.password
            and not base.query and not base.fragment and base.path.rstrip("/") == "/v1", "INVALID_LAB_ORIGIN")
    require("\n" not in args.lab_base_url and "\r" not in args.lab_base_url, "INVALID_LAB_ORIGIN")
    private_source = args.lab_key_file.resolve()
    require(private_source.is_relative_to(KEY_SOURCE_ROOT)
            and private_source.is_file() and private_source.stat().st_mode & 0o077 == 0, "KEY_SOURCE_NOT_PRIVATE")
    key = private_source.read_text().strip()
    require(bool(key) and not any(c.isspace() for c in key), "INVALID_KEY_FILE")
    model_verified = verify_lab_connectivity(args.lab_base_url, key, args.allow_model_unavailable)
    print("LAB_MODEL_LIVE_TEST_OK; NO_BUSINESS_DATA_SENT" if model_verified else
          "LAB_MODEL_NETWORK_PENDING; PUBLISH_CORE_FEATURES_WITH_EXPLICIT_LOCAL_FALLBACK")
    require(not RUNTIME.exists() and not NEW_WEB.parent.exists(), "RELEASE_ALREADY_PREPARED")
    private = WORK / PRIVATE_NAME
    require(not private.exists(), "PRIVATE_CONFIGURATION_ALREADY_PREPARED")
    unit_dir = UNIT_DIR
    last_name = max(p.name for p in unit_dir.glob("*.conf"))
    override = unit_dir / (last_name + ".ai-20261106-r3.conf")
    require(len(override.name.encode()) <= 250 and not override.exists(), "OVERRIDE_PATH_UNAVAILABLE")
    backup_web = OLD_WEB.with_name(BACKUP_NAME)
    require(not backup_web.exists(), "STATIC_BACKUP_ALREADY_EXISTS")
    receipt = STAGE / RECEIPT_NAME
    require(not receipt.exists(), "PREVIOUS_DEPLOYMENT_REQUIRES_REVIEW")
    receipt.mkdir(mode=0o700)
    old_web_hash = sha((OLD_WEB / "index.html").read_bytes())
    import pwd
    account = pwd.getpwnam("leiwen")
    runtime_changed = web_moved = False
    phase = "PREPARE_ARTIFACTS"
    try:
        RUNTIME.mkdir(mode=0o755)
        new_jar = RUNTIME / "ehm-service.jar"
        new_jar.write_bytes(merged)
        new_jar.chmod(0o644)
        NEW_WEB.mkdir(mode=0o755, parents=True)
        NEW_WEB.parent.chmod(0o755)
        for name, data in web.items():
            target = NEW_WEB / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            target.chmod(0o644)
        run("restorecon", "-R", str(NEW_WEB.parent), str(RUNTIME))
        require(sha((OLD_WEB / "index.html").read_bytes()) == old_web_hash, "STATIC_CHANGED_DURING_PREPARATION")
        phase = "WRITE_PRIVATE_CONFIGURATION"
        private.mkdir(mode=0o700)
        os.chown(private, account.pw_uid, account.pw_gid)
        key_target = private / "lab-api.key"
        write_private_text(key_target,key + "\n")
        os.chown(key_target, account.pw_uid, account.pw_gid)
        key = ""
        env_file = private / "ai.env"
        settings = {"EHM_AI_DEFAULT_MODEL":"lab-qwen", "EHM_AI_LAB_ENABLED":"true",
            "EHM_AI_LAB_BASE_URL":args.lab_base_url.rstrip("/"), "EHM_AI_LAB_MODEL":"Qwen3.8-27B",
            "EHM_AI_LAB_API_KEY_FILE":str(key_target), "EHM_AI_LAB_DATA_POLICY":"anonymized",
            "EHM_AI_LAB_TRUSTED_HTTP_ORIGIN":base.scheme+"://"+base.netloc,
            "EHM_AI_LAB_TOOLS_ENABLED":"false", "EHM_AI_LAB_TIMEOUT_SECONDS":"45"}
        write_private_text(env_file,"".join(k+"="+v+"\n" for k,v in settings.items()))
        os.chown(env_file, account.pw_uid, account.pw_gid)
        run("restorecon", "-R", str(private))
        command[jar_index] = str(new_jar)
        phase = "START_NEW_BACKEND"
        override.write_text("[Service]\nEnvironmentFile="+str(env_file)+"\nExecStart=\nExecStart="
                            +" ".join(systemd_argument(value) for value in command)+"\n")
        override.chmod(0o600)
        runtime_changed = True
        run("systemctl", "daemon-reload")
        run("systemctl", "restart", SERVICE)
        ready = False
        for _ in range(45):
            status, body = probe("http://127.0.0.1:20983/actuator/health")
            if status == 200:
                try:
                    ready = json.loads(body).get("status") == "UP"
                except (ValueError, AttributeError):
                    pass
            if ready:
                break
            time.sleep(2)
        require(ready, "NEW_BACKEND_NOT_READY")
        require(str(new_jar) in service_property("ExecStart"), "NEW_JAR_NOT_SELECTED")
        # A new endpoint must stay protected; never disable IAM to test publication.
        unauthenticated, _ = probe("http://127.0.0.1:20983/api/ehm/v1/assistant/models")
        require(unauthenticated in (401, 403), "ASSISTANT_AUTH_GUARD_NOT_VERIFIED")
        phase = "PUBLISH_STATIC_WEB"
        OLD_WEB.rename(backup_web)
        web_moved = True
        OLD_WEB.symlink_to(NEW_WEB, target_is_directory=True)
        public_status, public_html = probe("http://127.0.0.1:18090/ehm/")
        require(public_status == 200 and b"2.1.2-multi-model" in public_html, "UNIFIED_FRONTEND_NOT_VERIFIED")
        script_status, script_body = probe("http://127.0.0.1:18090/ehm/ai-assistant.js?v=2.1.2-multi-model")
        require(script_status == 200 and sha(script_body) == sha(web["ai-assistant.js"]), "UNIFIED_SCRIPT_NOT_VERIFIED")
        result = {"published":True, "service":SERVICE,"version":VERSION,"runtimeJarSha256":sha(merged),
            "updatedClassFiles":class_count,"originalJar":str(current_jar),"originalWebBackup":str(backup_web),
            "newWeb":str(NEW_WEB),"override":str(override),"databaseOrAccountsChanged":False,
            "runtimeDependenciesPreserved":True,"unchangedRuntimeClassesPreserved":True,
            "iamAuthenticatedUserAcceptancePending":True,"deepseekLiveConnectivityNotVerified":True,
            "labModelConnectivityVerified":model_verified,"labModelNetworkPending":not model_verified}
        (receipt / "receipt.json").write_text(json.dumps(result,indent=2)+"\n")
        print("DEPLOYMENT_COMPLETED; IAM_AND_DATABASE_UNCHANGED; EXISTING_BACKGROUND_CLASSES_PRESERVED")
        if not model_verified:
            print("LAB_MODEL_NETWORK_PENDING; MODEL_SELECTION_INSTALLED; REMOTE_INFERENCE_NOT_VERIFIED")
        print("ACCEPTANCE_URL=http://127.0.0.1:18090/ehm/; LOGIN_AND_MODEL_TEST_REQUIRED")
    except BaseException:
        print("DEPLOYMENT_STAGE_FAILED="+phase,file=sys.stderr)
        if web_moved:
            if OLD_WEB.is_symlink():
                OLD_WEB.unlink()
            if not OLD_WEB.exists():
                backup_web.rename(OLD_WEB)
        if runtime_changed:
            override.unlink(missing_ok=True)
            run("systemctl", "daemon-reload")
            run("systemctl", "restart", SERVICE)
        print(("APPLICATION_ROLLED_BACK" if runtime_changed or web_moved else "APPLICATION_WAS_NOT_SWITCHED")
              +"; DATABASE_AND_ACCOUNT_ROWS_RETAINED",file=sys.stderr)
        raise

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--check", action="store_true")
    action.add_argument("--apply", action="store_true")
    parser.add_argument("--lab-key-file",type=pathlib.Path)
    parser.add_argument("--lab-base-url")
    parser.add_argument("--allow-model-unavailable",action="store_true",
        help="Publish core features despite a network timeout/unreachable model; never ignores HTTP auth/model failures")
    args = parser.parse_args()
    try:
        main(args)
    except Exception as error:
        # Only our predefined error codes are printed, never provider responses, environment or secret values.
        code = str(error) if isinstance(error,RuntimeError) and re.fullmatch(r"[A-Z0-9_]+",str(error)) else type(error).__name__
        print("DEPLOYMENT_NOT_COMPLETED: "+code,file=sys.stderr)
        sys.exit(1)
