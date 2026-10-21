#!/usr/bin/env bash
set -euo pipefail
umask 077
AUDIT_HOME="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${AUDIT_ENV_FILE:-${AUDIT_HOME}/config/audit.env}"
[[ -f "${ENV_FILE}" ]] || { echo 'Private AUDIT environment file is missing' >&2; exit 1; }
if [[ "$(stat -c '%a' "${ENV_FILE}")" != '600' && "$(stat -c '%a' "${ENV_FILE}")" != '400' ]]; then
  echo 'AUDIT env must have owner-only read permissions (600 or 400)' >&2; exit 1
fi
set -a
# Trusted operator-owned shell assignment file only. preflight-config.py does not execute its input.
source "${ENV_FILE}"
set +a
[[ "${AUDIT_AUTO_DDL:-false}" == 'false' ]] || { echo 'Use an authorized migration account, not runtime auto-DDL' >&2; exit 1; }
[[ -n "${AUDIT_DB_URL:-}" && -n "${AUDIT_DB_USER:-}" && -n "${AUDIT_DB_PASSWORD:-}" ]] || { echo 'Private database configuration is incomplete' >&2; exit 1; }
[[ -f "${AUDIT_CLIENTS_FILE:-}" ]] || { echo 'Private service identity file is missing' >&2; exit 1; }
cd -- "${AUDIT_HOME}"
exec "${JAVA_BIN:-java}" -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -jar service/target/audit-service-1.1.0.jar
