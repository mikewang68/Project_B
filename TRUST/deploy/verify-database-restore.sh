#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
load_deployment
export GAUSSHOME="$ROOT/tools/opengauss" LD_LIBRARY_PATH="$ROOT/tools/opengauss/lib:${LD_LIBRARY_PATH:-}"
export PATH="$GAUSSHOME/bin:$PATH"
source "$ROOT/runtime/secrets/db.env"
database=${1:-trust}
[[ "$database" == trust || "$database" == trust_test ]] || exit 2
stamp=$(date -u +%Y%m%dT%H%M%SZ)
restored="trust_restore_${stamp,,}"
backup="$ROOT/runtime/backups/$database-$stamp.dump"
gs_dump -h 127.0.0.1 -p 25432 -U trust_app -W "$TRUST_DB_PASSWORD" -F c -f "$backup" "$database"
gsql -h "$ROOT/runtime" -p 25432 -U "$TRUST_DATABASE_OWNER" -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $restored OWNER trust_app DBCOMPATIBILITY='PG';"
gs_restore -h "$ROOT/runtime" -p 25432 -U "$TRUST_DATABASE_OWNER" -d "$restored" "$backup"
query="SELECT id,event_sha256,COALESCE(manifest_cid,''),COALESCE(manifest_sha256,''),COALESCE(tx_id,''),version FROM trust_data.events ORDER BY id;"
gsql -h 127.0.0.1 -p 25432 -U trust_app -W "$TRUST_DB_PASSWORD" -d "$database" -t -A -c "$query" >"$ROOT/runtime/backups/original-$stamp.txt"
gsql -h 127.0.0.1 -p 25432 -U trust_app -W "$TRUST_DB_PASSWORD" -d "$restored" -t -A -c "$query" >"$ROOT/runtime/backups/restored-$stamp.txt"
cmp "$ROOT/runtime/backups/original-$stamp.txt" "$ROOT/runtime/backups/restored-$stamp.txt"
echo "PASS: database rows, event digests, manifest CIDs, manifest digests, transaction IDs and versions restored unchanged"
echo "BACKUP: $backup"
echo "RESTORED_DATABASE: $restored"
sha256sum "$ROOT/runtime/backups/original-$stamp.txt"
