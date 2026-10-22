#!/usr/bin/env bash
set -euo pipefail
release=20261013-1240
stage=/home/lrz/psms-release-$release
saved=/opt/project-b/PSMS/.deploy/$release
backend=/opt/project-b/PSMS/backend
front=/data/app/project-b-20260921/PSMS/production-dispatch
simulator_unit="$stage/psms-s1-simulator.service"
node=/opt/node-24.18.0/bin/node
test -d "$backend/dist"
test -f "$front/index.html"
test -f "$simulator_unit"
test -s "$saved/database-before.json"
test ! -e "$backend/.dist-backup-$release"
test ! -e "$front.backup-$release"
umask 077
tar -czf "$saved/backend-before.tar.gz" -C "$backend" dist package.json
cp /etc/project-b/PSMS/service.env "$saved/service-before.env"
if test -f "$backend/.env.ai"; then cp "$backend/.env.ai" "$saved/ai-before.env"; fi
tar -czf "$saved/frontend-before.tar.gz" -C "$front" .
cp -a "$saved/backend/dist" "$backend/.dist-new-$release"
cp -a "$saved/frontend" "$front.new-$release"
chown -R psms:psms "$backend/.dist-new-$release"
chmod 755 "$front.new-$release"
chmod -R a+rX "$front.new-$release"
swapped=0
frontswapped=0
rollback() {
  code=$?
  trap - ERR
  if test "$swapped" = 1; then
    systemctl stop project-b-psms.service || true
    if test -d "$backend/dist"; then mv "$backend/dist" "$backend/.dist-failed-$release"; fi
    mv "$backend/.dist-backup-$release" "$backend/dist"
    if test -f "$saved/ai-before.env"; then cp "$saved/ai-before.env" "$backend/.env.ai"; chown psms:psms "$backend/.env.ai"; else mv "$backend/.env.ai" "$saved/ai-failed.env"; fi
  fi
  systemctl start project-b-psms.service || true
  if test "$frontswapped" = 1; then if test -d "$front"; then mv "$front" "$front.failed-$release"; fi; mv "$front.backup-$release" "$front"; fi
  echo "Deployment failed; previous application restored. Database backup retained at $saved/database-before.json" >&2
  exit "$code"
}
trap rollback ERR
systemctl stop project-b-psms.service
# Refresh the database snapshot under downtime, then repeat migration checks.
"$node" "$stage/deploy-db-check.mjs" "$saved/backend" "$saved/database-before.json"
# Inherit existing server connection settings without revealing any credentials.
"$node" --input-type=module - "$saved/backend" <<'NODE'
import fs from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire('/opt/project-b/PSMS/backend/package.json');
const dotenv = require('dotenv');
Object.assign(process.env, dotenv.parse(fs.readFileSync('/etc/project-b/PSMS/service.env')));
const { migrate } = await import(process.argv[2] + '/dist/db/migrate.js');
const { closePool } = await import(process.argv[2] + '/dist/db/openGaussClient.js');
try { await migrate(); } finally { await closePool(); }
NODE
mv "$backend/dist" "$backend/.dist-backup-$release"
swapped=1
mv "$backend/.dist-new-$release" "$backend/dist"
install -o psms -g psms -m 600 "$stage/ai.env" "$backend/.env.ai"
systemctl start project-b-psms.service
healthy=0
for attempt in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:3100/api/health > "$saved/health-after.json"; then healthy=1; break; fi
  sleep 1
done
test "$healthy" = 1
install -o root -g root -m 644 "$simulator_unit" /etc/systemd/system/project-b-psms-simulator.service
systemctl daemon-reload
systemctl enable --now project-b-psms-simulator.service
"$node" --input-type=module - "$saved/health-after.json" "$saved/database-before.json" <<'NODE'
import fs from 'node:fs';
const health = JSON.parse(fs.readFileSync(process.argv[2]));
const before = JSON.parse(fs.readFileSync(process.argv[3]));
if (!health.openGauss.connected) throw new Error('Database disconnected');
for (const [table, count] of Object.entries(health.openGauss.tables)) if (table !== 'audit_logs' && count !== before.tables[table].length) throw new Error('Business count mismatch: ' + table);
const ai = await fetch('http://127.0.0.1:3100/api/ai/status').then(r => r.json());
if (!ai.configured || !ai.demoSession || ai.model !== 'qwen3.8-27b') throw new Error('AI deployment configuration not ready');
console.log('PASS: database connection, preserved business counts, Qwen configuration and automatic AI session.');
NODE
mv "$front" "$front.backup-$release"
frontswapped=1
mv "$front.new-$release" "$front"
curl -kfsS https://127.0.0.1:18443/production-dispatch/ > "$saved/served-index.html"
cmp "$front/index.html" "$saved/served-index.html"
trap - ERR
echo "PSMS release deployed: $release"
