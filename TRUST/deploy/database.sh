#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
load_deployment
export GAUSSHOME="$ROOT/tools/opengauss"
export PATH="$GAUSSHOME/bin:$PATH" LD_LIBRARY_PATH="$GAUSSHOME/lib:${LD_LIBRARY_PATH:-}"
DATA="$ROOT/runtime/opengauss"
ulimit -n 8192
case "${1:-status}" in
 install)
  python3 "$ROOT/deploy/extract-opengauss.py"
  gaussdb --version
  ;;
 init)
  if [[ ! -f "$ROOT/runtime/secrets/db.env" ]]; then
   umask 077
   python3 - "$ROOT" <<'PY'
import pathlib,secrets,sys
r=pathlib.Path(sys.argv[1]);pw='Trust_'+secrets.token_hex(10)+'9!'
(r/'runtime/secrets/db.env').write_text('TRUST_DB_PASSWORD='+pw+'\n')
PY
  fi
  source "$ROOT/runtime/secrets/db.env"
  [[ -f "$DATA/PG_VERSION" ]] || gs_initdb -D "$DATA" --nodename=trust_dev -U "$TRUST_DATABASE_OWNER" -w "$TRUST_DB_PASSWORD" --encoding=UTF8 --locale=C
  gs_guc set -D "$DATA" -c "port=25432" -c "listen_addresses='127.0.0.1'" -c "max_connections=80" -c "shared_buffers=256MB" -c "max_process_memory=2GB" -c "password_encryption_type=1" -c "unix_socket_directory='$ROOT/runtime'"
  gs_guc set -D "$DATA" -h 'host all all 127.0.0.1/32 sha256'
  ;;
 start) cd "$ROOT"; gs_ctl start -D "$DATA" -Z single_node -l "$ROOT/runtime/logs/opengauss.log" ;;
 create)
  source "$ROOT/runtime/secrets/db.env"
  export PGPASSWORD="$TRUST_DB_PASSWORD"
  if [[ ! -f "$ROOT/runtime/secrets/db-owner.env" ]]; then
    umask 077
    python3 - "$ROOT" <<'PY'
import pathlib,secrets,sys
p=pathlib.Path(sys.argv[1])/'runtime/secrets/db-owner.env'
p.write_text('TRUST_OWNER_PASSWORD=Owner_'+secrets.token_hex(10)+'8!\n')
PY
  fi
  source "$ROOT/runtime/secrets/db-owner.env"
  if [[ ! -f "$ROOT/runtime/secrets/db-owner-initialized" ]]; then
    gsql -h "$ROOT/runtime" -p 25432 -U "$TRUST_DATABASE_OWNER" -d postgres -v ON_ERROR_STOP=1 <<SQL
ALTER ROLE ${TRUST_DATABASE_OWNER} WITH PASSWORD '$TRUST_OWNER_PASSWORD';
SQL
    touch "$ROOT/runtime/secrets/db-owner-initialized"
  fi
  gsql -h "$ROOT/runtime" -p 25432 -U "$TRUST_DATABASE_OWNER" -d postgres -v ON_ERROR_STOP=1 <<SQL
CREATE USER trust_app WITH PASSWORD '$TRUST_DB_PASSWORD';
CREATE DATABASE trust OWNER trust_app DBCOMPATIBILITY='PG';
SQL
  gsql -h "$ROOT/runtime" -p 25432 -U "$TRUST_DATABASE_OWNER" -d trust -v ON_ERROR_STOP=1 -c 'CREATE SCHEMA trust_data AUTHORIZATION trust_app;'
  ;;
 stop) gs_ctl stop -D "$DATA" -m fast ;;
 status) gs_ctl status -D "$DATA" ;;
 backup)
  source "$ROOT/runtime/secrets/db.env"; export PGPASSWORD="$TRUST_DB_PASSWORD"
  gs_dump -h 127.0.0.1 -p 25432 -U trust_app -W "$TRUST_DB_PASSWORD" -F c -f "$ROOT/runtime/backups/trust-$(date -u +%Y%m%dT%H%M%SZ).dump" trust
  ;;
 *) echo 'Usage: database.sh install|init|start|create|stop|status|backup'; exit 2 ;;
esac
