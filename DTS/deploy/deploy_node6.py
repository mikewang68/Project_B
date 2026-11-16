#!/usr/bin/env python3
"""Idempotent Stage-2/3/4 deployment script for DTS V4 (S5) on bpoc-node6."""
import os
import re
import shutil
import subprocess
import sys
import time

STAGE = "/home/leiwen/dts_stage"
BACKUP_DIR = f"/home/leiwen/backups/dts-deploy-{time.strftime('%Y%m%d-%H%M%S')}"
WEB_VER_DIR = "/var/www/project-b-unified/components/dts-web-v4-20261116"
WEB_SITE_ROOT = "/var/www/project-b-unified/components/dts-site-root"
SVC_DIR = "/opt/project-b-unified/components/dts-service-20261116"
INBOX_DIR = "/var/lib/project-b-unified/dts-inbox"
ENV_DIR = "/etc/project-b-unified/scoped-9cce4f3686c7/dts"
UNIT_PATH = "/etc/systemd/system/project-b-unified-dts.service"
BACKHAUL_CONF = "/etc/nginx/conf.d/project-b-unified-backhaul.conf"
BDEMO_CONF = "/etc/nginx/conf.d/bdemo-44e32a9.conf"
BFF_PATH = "/opt/project-b-unified/components/project-workspace-220d8ea2a5931d72/e21_bff.py"


def run(cmd, check=True):
    print(f"+ {cmd}", flush=True)
    res = subprocess.run(cmd, shell=True, text=True, capture_output=True)
    if res.stdout:
        print(res.stdout.rstrip(), flush=True)
    if res.stderr:
        print(res.stderr.rstrip(), file=sys.stderr, flush=True)
    if check and res.returncode != 0:
        raise RuntimeError(f"Command failed ({res.returncode}): {cmd}")
    return res


def strip_marked_block(text: str, start_marker: str, end_marker: str) -> str:
    pattern = re.compile(
        r"\n?[ \t]*" + re.escape(start_marker) + r".*?" + re.escape(end_marker) + r"[ \t]*\n?",
        re.DOTALL,
    )
    return pattern.sub("\n", text)


def backup_files():
    os.makedirs(BACKUP_DIR, mode=0o700, exist_ok=True)
    for src in (BACKHAUL_CONF, BDEMO_CONF, BFF_PATH):
        shutil.copy2(src, os.path.join(BACKUP_DIR, os.path.basename(src)))
    if os.path.exists(UNIT_PATH):
        shutil.copy2(UNIT_PATH, os.path.join(BACKUP_DIR, os.path.basename(UNIT_PATH)))
    print(f"Backed up configs to {BACKUP_DIR}", flush=True)


def deploy_frontend():
    tgz = os.path.join(STAGE, "dts-web-v4-20261116.tgz")
    if not os.path.isfile(tgz):
        raise FileNotFoundError(f"Missing staged tarball: {tgz}")
    if os.path.exists(WEB_VER_DIR):
        shutil.rmtree(WEB_VER_DIR)
    os.makedirs(WEB_VER_DIR, mode=0o755, exist_ok=True)
    run(f"tar -xzf {tgz} -C {WEB_VER_DIR}")
    for root, dirs, files in os.walk(WEB_VER_DIR):
        os.chown(root, 0, 0)
        os.chmod(root, 0o755)
        for d in dirs:
            p = os.path.join(root, d)
            os.chown(p, 0, 0)
            os.chmod(p, 0o755)
        for f in files:
            p = os.path.join(root, f)
            os.chown(p, 0, 0)
            os.chmod(p, 0o644)
    os.makedirs(WEB_SITE_ROOT, mode=0o755, exist_ok=True)
    os.chown(WEB_SITE_ROOT, 0, 0)
    link_path = os.path.join(WEB_SITE_ROOT, "dts")
    if os.path.islink(link_path) or os.path.exists(link_path):
        if os.path.isdir(link_path) and not os.path.islink(link_path):
            shutil.rmtree(link_path)
        else:
            os.unlink(link_path)
    os.symlink(WEB_VER_DIR, link_path)
    assert os.path.isfile(os.path.join(link_path, "index.html")), "Extracted dist missing index.html"
    assert os.path.isfile(os.path.join(link_path, "models", "ash-core-v4-medium.glb")), "Extracted dist missing V4 GLB"
    run(f"restorecon -RF {WEB_VER_DIR} {WEB_SITE_ROOT}", check=False)
    run(f"chcon -R --reference=/var/www/project-b-unified/components {WEB_VER_DIR} {WEB_SITE_ROOT}", check=False)
    print("Frontend deployed OK", flush=True)


def deploy_backend_service():
    srv_src = os.path.join(STAGE, "dts_readonly_server.py")
    if not os.path.isfile(srv_src):
        raise FileNotFoundError(f"Missing {srv_src}")
    os.makedirs(SVC_DIR, mode=0o755, exist_ok=True)
    srv_dst = os.path.join(SVC_DIR, "dts_readonly_server.py")
    with open(srv_src, "rb") as rf, open(srv_dst, "wb") as wf:
        wf.write(rf.read())
    os.chown(srv_dst, 0, 0)
    os.chmod(srv_dst, 0o755)
    run(f"restorecon -RF {SVC_DIR}", check=False)
    run(f"chcon --reference=/opt/project-b-unified/components/project-workspace-220d8ea2a5931d72/e21_bff.py {srv_dst}", check=False)
    run(f"python3 -m py_compile {srv_dst}")

    pw_info = run("id -u leiwen && id -g leiwen")
    uid, gid = [int(x) for x in pw_info.stdout.strip().splitlines()[:2]]

    os.makedirs(INBOX_DIR, mode=0o750, exist_ok=True)
    os.chown(INBOX_DIR, uid, gid)
    os.chmod(INBOX_DIR, 0o750)
    snap_gz = os.path.join(STAGE, "dts-inbox-snapshot.sqlite.gz")
    snap_src = os.path.join(STAGE, "dts-inbox-snapshot.sqlite")
    snap_dst = os.path.join(INBOX_DIR, "dts-public-inbox.sqlite")
    if os.path.isfile(snap_gz):
        import gzip
        with gzip.open(snap_gz, "rb") as rf, open(snap_dst, "wb") as wf:
            shutil.copyfileobj(rf, wf)
        os.chown(snap_dst, uid, gid)
        os.chmod(snap_dst, 0o640)
    elif os.path.isfile(snap_src):
        with open(snap_src, "rb") as rf, open(snap_dst, "wb") as wf:
            shutil.copyfileobj(rf, wf)
        os.chown(snap_dst, uid, gid)
        os.chmod(snap_dst, 0o640)
    run(f"restorecon -RF {INBOX_DIR}", check=False)
    run(f"chcon -R --reference=/var/lib/project-b-unified/gateway {INBOX_DIR}", check=False)

    os.makedirs(ENV_DIR, mode=0o700, exist_ok=True)
    os.chown(ENV_DIR, uid, gid)
    os.chmod(ENV_DIR, 0o700)
    env_src = os.path.join(STAGE, "dts_runtime.env")
    env_dst = os.path.join(ENV_DIR, "runtime.env")
    if os.path.isfile(env_src):
        with open(env_src, "rb") as rf, open(env_dst, "wb") as wf:
            wf.write(rf.read())
        os.remove(env_src)
        os.chown(env_dst, uid, gid)
        os.chmod(env_dst, 0o600)
    elif not os.path.isfile(env_dst):
        raise FileNotFoundError("Missing staged dts_runtime.env and existing runtime.env")
    run(f"restorecon -RF {ENV_DIR}", check=False)
    run(f"chcon -R --reference=/etc/project-b-unified/scoped-9cce4f3686c7/portal {ENV_DIR}", check=False)

    unit_content = f"""[Unit]
Description=Project B S5 Digital Twin Visualization Readonly Service (DTS V4)
After=network-online.target

[Service]
Type=simple
User=leiwen
Group=leiwen
WorkingDirectory={INBOX_DIR}
EnvironmentFile={env_dst}
ExecStart=/usr/bin/python3 {srv_dst}
Restart=on-failure
RestartSec=3
UMask=0077

[Install]
WantedBy=multi-user.target
"""
    with open(UNIT_PATH, "w", encoding="utf-8", newline="\n") as f:
        f.write(unit_content)
    os.chmod(UNIT_PATH, 0o644)
    run(f"restorecon -F {UNIT_PATH}", check=False)
    run("systemctl daemon-reload")
    run("systemctl enable project-b-unified-dts.service")
    run("systemctl restart project-b-unified-dts.service")
    print("Systemd service deployed OK", flush=True)


def dts_locations_block(indent: str, forwarded_proto: str) -> str:
    return (
        f"{indent}# --- BEGIN DTS V4 (S5) ---\n"
        f"{indent}location = /dts {{ return 308 /dts/; }}\n"
        f"{indent}location ^~ /dts/assets/ {{\n"
        f"{indent} root {WEB_SITE_ROOT};\n"
        f"{indent} charset utf-8;\n"
        f'{indent} add_header Cache-Control "public, max-age=31536000, immutable";\n'
        f"{indent} try_files $uri =404;\n"
        f"{indent}}}\n"
        f"{indent}location ^~ /dts/cesium/ {{\n"
        f"{indent} root {WEB_SITE_ROOT};\n"
        f"{indent} charset utf-8;\n"
        f"{indent} try_files $uri =404;\n"
        f"{indent}}}\n"
        f"{indent}location ^~ /dts/models/ {{\n"
        f"{indent} root {WEB_SITE_ROOT};\n"
        f"{indent} types {{ model/gltf-binary glb; model/gltf+json gltf; application/octet-stream bin; }}\n"
        f"{indent} try_files $uri =404;\n"
        f"{indent}}}\n"
        f"{indent}location ^~ /dts/data/ {{\n"
        f"{indent} root {WEB_SITE_ROOT};\n"
        f"{indent} charset utf-8;\n"
        f"{indent} try_files $uri =404;\n"
        f"{indent}}}\n"
        f"{indent}location ^~ /dts/ {{\n"
        f"{indent} root {WEB_SITE_ROOT};\n"
        f"{indent} charset utf-8;\n"
        f"{indent} try_files $uri $uri/ /dts/index.html;\n"
        f"{indent}}}\n"
        f"{indent}location ^~ /api/v1/dts/ {{\n"
        f"{indent} proxy_pass http://127.0.0.1:20989;\n"
        f"{indent} proxy_http_version 1.1;\n"
        f"{indent} proxy_set_header Host $http_host;\n"
        f"{indent} proxy_set_header X-Forwarded-Host $http_host;\n"
        f"{indent} proxy_set_header X-Forwarded-Proto {forwarded_proto};\n"
        f"{indent} proxy_set_header X-Forwarded-For $remote_addr;\n"
        f'{indent} proxy_set_header Connection "";\n'
        f"{indent} proxy_read_timeout 30s;\n"
        f"{indent}}}\n"
        f"{indent}# --- END DTS V4 (S5) ---\n"
    )


def update_nginx():
    # 1. project-b-unified-backhaul.conf
    with open(BACKHAUL_CONF, "r", encoding="utf-8") as f:
        bh = f.read()
    bh = strip_marked_block(bh, "# --- BEGIN DTS V4 (S5) ---", "# --- END DTS V4 (S5) ---")
    anchor = " location / {\n  proxy_pass http://127.0.0.1:20995;"
    if anchor not in bh:
        raise ValueError(f"Anchor not found in {BACKHAUL_CONF}")
    bh_new = bh.replace(anchor, dts_locations_block(" ", "https") + anchor, 1)
    with open(BACKHAUL_CONF, "w", encoding="utf-8", newline="\n") as f:
        f.write(bh_new)

    # 2. bdemo-44e32a9.conf (add /dts inside 18092 SYS block + append 18096 server block)
    with open(BDEMO_CONF, "r", encoding="utf-8") as f:
        bd = f.read()
    bd = strip_marked_block(bd, "# --- BEGIN DTS V4 (S5) ---", "# --- END DTS V4 (S5) ---")
    bd = strip_marked_block(bd, "# --- BEGIN DTS V4 18096 ---", "# --- END DTS V4 18096 ---")
    sys_anchor = "root /var/www/project-b-unified/components/sys-web-"
    if sys_anchor not in bd:
        raise ValueError(f"SYS root anchor not found in {BDEMO_CONF}")
    bd = bd.replace(sys_anchor, dts_locations_block("", "$scheme") + sys_anchor, 1)
    server_18096 = (
        "\n# --- BEGIN DTS V4 18096 ---\n"
        "server {\n"
        " listen 18096;\n"
        " server_name 192.168.101.74 bpoc-node6 _;\n"
        " charset utf-8;\n"
        " add_header X-Content-Type-Options nosniff always;\n"
        " absolute_redirect off; port_in_redirect off; client_max_body_size 20m;\n"
        " add_header X-Project-B-DTS-Version V4-20261116 always;\n"
        " location = / { return 302 /dts/; }\n"
        " location = /health/frontend { return 200 \"dts-v4-20261116\\n\"; }\n"
        + dts_locations_block(" ", "$scheme")
        + " location / { return 404; }\n"
        "}\n"
        "# --- END DTS V4 18096 ---\n"
    )
    bd = bd.rstrip() + "\n" + server_18096
    with open(BDEMO_CONF, "w", encoding="utf-8", newline="\n") as f:
        f.write(bd)

    res = run("nginx -t", check=False)
    if res.returncode != 0:
        shutil.copy2(os.path.join(BACKUP_DIR, os.path.basename(BACKHAUL_CONF)), BACKHAUL_CONF)
        shutil.copy2(os.path.join(BACKUP_DIR, os.path.basename(BDEMO_CONF)), BDEMO_CONF)
        raise RuntimeError("nginx -t failed; rolled back nginx confs")
    if run("semanage port -l | grep -qE '^http_port_t\\b.*\\b18096\\b'", check=False).returncode != 0:
        r_sem = run("semanage port -a -t http_port_t -p tcp 18096", check=False)
        if r_sem.returncode != 0:
            run("semanage port -m -t http_port_t -p tcp 18096")
    run("systemctl reload nginx")
    run("sleep 1 && ss -tln | grep -E ':18096\\b|:20989\\b'")
    print("Nginx updated & reloaded OK", flush=True)


def patch_e21_bff():
    with open(BFF_PATH, "r", encoding="utf-8") as f:
        src = f.read()

    # Remove previous patch if re-running
    src = re.sub(
        r"\n?[ \t]*# --- DTS_NAV_INJECTION_V4_HELPER_START ---.*?# --- DTS_NAV_INJECTION_V4_HELPER_END ---[ \t]*\n?",
        "\n",
        src,
        flags=re.DOTALL,
    )
    src = re.sub(
        r"\n?[ \t]*# --- DTS_NAV_INJECTION_V4_CALL_START ---.*?# --- DTS_NAV_INJECTION_V4_CALL_END ---[ \t]*\n?",
        "\n",
        src,
        flags=re.DOTALL,
    )
    src = re.sub(
        r"\n?[ \t]*# --- DTS_NAV_INJECTION_V4_HDR_START ---.*?# --- DTS_NAV_INJECTION_V4_HDR_END ---[ \t]*\n?",
        "\n",
        src,
        flags=re.DOTALL,
    )

    helper_code = '''
    # --- DTS_NAV_INJECTION_V4_HELPER_START ---
    def _maybe_inject_dts_navigation(self, resp_data):
        """为已授权门户主体动态注入 S5 数字孪生可视化系统 (DTS) 导航节点（保持 8 系统隔离）"""
        try:
            payload = json.loads(resp_data.decode("utf-8"))
            if payload.get("code") != 0 or not isinstance(payload.get("data"), dict):
                return resp_data, False
            data = payload["data"]
            uc = data.get("userContext")
            systems = data.get("systems")
            if not isinstance(uc, dict) or not isinstance(systems, list):
                return resp_data, False
            allowed_codes = uc.get("allowedAppCodes") or []
            access_mode = str(uc.get("accessMode") or "")
            dts_allowed = (
                access_mode in ("FULL_ADMIN", "READONLY_AUDITOR")
                or any(c in ("IAM", "SYS", "DTS") for c in allowed_codes)
            )
            if not dts_allowed:
                return resp_data, False
            if "DTS" not in allowed_codes:
                allowed_codes.append("DTS")
                uc["allowedAppCodes"] = allowed_codes
            if not any(isinstance(s, dict) and s.get("appCode") == "DTS" for s in systems):
                raw_host = (self.headers.get("X-Forwarded-Host") or self.headers.get("Host") or "bproject.de5.net").split(",")[0].strip()
                raw_proto = (self.headers.get("X-Forwarded-Proto") or ("https" if "bproject.de5.net" in raw_host else "http")).split(",")[0].strip()
                if not re.fullmatch(r"[A-Za-z0-9._:-]+", raw_host) or raw_proto not in ("http", "https"):
                    raw_proto, raw_host = "https", "bproject.de5.net"
                base_url = f"{raw_proto}://{raw_host}/dts/"
                systems.append({
                    "appCode": "DTS",
                    "displayName": "数字孪生可视化系统 (S5)",
                    "sortOrder": 60,
                    "entryBasePath": "/dts/",
                    "entryBaseUrl": base_url,
                    "ssoEnabled": False,
                    "children": [
                        {
                            "stableMenuKey": "DTS:workbench",
                            "hierarchyCode": "DTS-01",
                            "menuName": "三维作业台",
                            "nodeType": "PAGE",
                            "entryMode": "SPA_ROUTE",
                            "entryPath": "/dts/",
                            "featureStatus": "READY",
                            "canLaunch": True,
                            "children": [],
                        },
                        {
                            "stableMenuKey": "DTS:bigscreen",
                            "hierarchyCode": "DTS-02",
                            "menuName": "综合态势大屏",
                            "nodeType": "PAGE",
                            "entryMode": "SPA_ROUTE",
                            "entryPath": "/dts/bigscreen",
                            "featureStatus": "READY",
                            "canLaunch": True,
                            "children": [],
                        },
                        {
                            "stableMenuKey": "DTS:calibration",
                            "hierarchyCode": "DTS-03",
                            "menuName": "CAD 落位校核",
                            "nodeType": "PAGE",
                            "entryMode": "SPA_ROUTE",
                            "entryPath": "/dts/calibration",
                            "featureStatus": "READY",
                            "canLaunch": True,
                            "children": [],
                        },
                    ],
                })
            op_perms = data.setdefault("operationPermissions", {})
            if isinstance(op_perms, dict) and "DTS" not in op_perms:
                op_perms["DTS"] = ["dts:workbench:view", "dts:bigscreen:view", "dts:calibration:view"]
            return json.dumps(payload, ensure_ascii=False).encode("utf-8"), True
        except Exception:
            return resp_data, False
    # --- DTS_NAV_INJECTION_V4_HELPER_END ---
'''

    target_def = "    def forward_request(self, method, upstream_base, path):"
    if target_def not in src:
        raise ValueError("Could not locate def forward_request in e21_bff.py")
    src = src.replace(target_def, helper_code + target_def, 1)

    hdr_anchor = '            if header_name.lower() in ["host", "content-length", "origin", "referer"]:'
    hdr_repl = (
        '            # --- DTS_NAV_INJECTION_V4_HDR_START ---\n'
        '            is_nav_tree_req = (method == "GET" and upstream_base == f"http://127.0.0.1:{IAM_PORT}" and urllib.parse.urlsplit(path).path == "/api/v1/iam/navigation/tree")\n'
        '            skip_hdrs = ["host", "content-length", "origin", "referer"] + (["accept-encoding"] if is_nav_tree_req else [])\n'
        '            # --- DTS_NAV_INJECTION_V4_HDR_END ---\n'
        '            if header_name.lower() in skip_hdrs:'
    )
    if hdr_anchor not in src:
        raise ValueError("Could not locate header skip anchor in forward_request")
    src = src.replace(hdr_anchor, hdr_repl, 1)

    resp_anchor = "                resp_data = resp.read()"
    resp_repl = (
        "                resp_data = resp.read()\n"
        "                # --- DTS_NAV_INJECTION_V4_CALL_START ---\n"
        '                if method == "GET" and upstream_base == f"http://127.0.0.1:{IAM_PORT}" and urllib.parse.urlsplit(path).path == "/api/v1/iam/navigation/tree" and resp.status == 200:\n'
        "                    resp_data, _ = self._maybe_inject_dts_navigation(resp_data)\n"
        "                # --- DTS_NAV_INJECTION_V4_CALL_END ---"
    )
    # Limit replacement to the first occurrence inside forward_request after target_def
    idx_def = src.index(target_def)
    idx_resp = src.find(resp_anchor, idx_def)
    if idx_resp == -1:
        raise ValueError("Could not locate resp.read() anchor inside forward_request")
    src = src[:idx_resp] + resp_repl + src[idx_resp + len(resp_anchor):]

    with open(BFF_PATH, "w", encoding="utf-8", newline="\n") as f:
        f.write(src)

    res = run(f"python3 -m py_compile {BFF_PATH}", check=False)
    if res.returncode != 0:
        shutil.copy2(os.path.join(BACKUP_DIR, os.path.basename(BFF_PATH)), BFF_PATH)
        raise RuntimeError("py_compile failed for e21_bff.py; rolled back")
    run("systemctl restart project-b-unified-portal.service")
    print("e21_bff.py patched & portal service restarted OK", flush=True)


def update_firewalld():
    rules = [
        "rule family=\"ipv4\" source address=\"192.168.101.0/24\" port port=\"18096\" protocol=\"tcp\" accept",
        "rule family=\"ipv4\" source address=\"192.168.101.11/32\" port port=\"18096\" protocol=\"tcp\" accept",
    ]
    existing = run("firewall-cmd --list-rich-rules", check=False).stdout
    changed = False
    for r in rules:
        if 'port="18096"' not in existing:
            run(f"firewall-cmd --permanent --add-rich-rule='{r}'")
            changed = True
    if changed:
        run("firewall-cmd --reload")
    print("Firewalld 18096 checked/updated OK", flush=True)


if __name__ == "__main__":
    backup_files()
    deploy_frontend()
    deploy_backend_service()
    update_nginx()
    update_firewalld()
    patch_e21_bff()
    print("ALL NODE6 STEPS SUCCEEDED", flush=True)
