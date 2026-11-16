#!/usr/bin/env python3
"""Idempotently expose DTS port 18096 on bpoc-node1 Tailscale 100.65.200.125."""
import os
import re
import shutil
import subprocess
import sys
import time

CONF = "/etc/nginx/conf.d/project-b-shared.conf"
BACKUP_DIR = f"/home/leiwen/backups/dts-deploy-node1-{time.strftime('%Y%m%d-%H%M%S')}"
START_M = "# --- BEGIN DTS V4 18096 ---"
END_M = "# --- END DTS V4 18096 ---"

BLOCK = f"""{START_M}
server {{
 listen 100.65.200.125:18096;
 server_name bpoc-node6 _;
 client_max_body_size 50m;
 location / {{
  proxy_pass http://192.168.101.74:18096;
  proxy_http_version 1.1;
  proxy_set_header Host $http_host;
  proxy_set_header X-Forwarded-Host $http_host;
  proxy_set_header X-Forwarded-Proto $scheme;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header Upgrade $http_upgrade;
  proxy_set_header Connection $project_b_upgrade;
  proxy_read_timeout 180s;
 }}
}}
{END_M}
"""

def run(cmd, check=True):
    print(f"+ {cmd}", flush=True)
    res = subprocess.run(cmd, shell=True, text=True, capture_output=True)
    if res.stdout:
        print(res.stdout.rstrip(), flush=True)
    if res.stderr:
        print(res.stderr.rstrip(), file=sys.stderr, flush=True)
    if check and res.returncode != 0:
        raise RuntimeError(f"Command failed: {cmd}")
    return res

os.makedirs(BACKUP_DIR, mode=0o700, exist_ok=True)
shutil.copy2(CONF, os.path.join(BACKUP_DIR, os.path.basename(CONF)))

with open(CONF, "r", encoding="utf-8") as f:
    src = f.read()

pattern = re.compile(r"\n?" + re.escape(START_M) + r".*?" + re.escape(END_M) + r"\n?", re.DOTALL)
src = pattern.sub("\n", src).rstrip() + "\n\n" + BLOCK
with open(CONF, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)

res = run("nginx -t", check=False)
if res.returncode != 0:
    shutil.copy2(os.path.join(BACKUP_DIR, os.path.basename(CONF)), CONF)
    raise RuntimeError("nginx -t failed on bpoc-node1; rolled back")
if run("semanage port -l | grep -qE '^http_port_t\\b.*\\b18096\\b'", check=False).returncode != 0:
    r_sem = run("semanage port -a -t http_port_t -p tcp 18096", check=False)
    if r_sem.returncode != 0:
        run("semanage port -m -t http_port_t -p tcp 18096")
run("systemctl reload nginx")
run("sleep 1 && ss -tln | grep ':18096\\b'")
print("bpoc-node1 zones:", run("firewall-cmd --get-active-zones", check=False).stdout.strip())
print("NODE1 DEPLOY SUCCEEDED", flush=True)
