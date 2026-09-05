#!/usr/bin/env bash
# =============================================================================
# 可选：在任意构建机构建后，用 rsync 把静态包推送到 bpoc-node6。
# 用法：
#   NODE6_USER=root NODE6_HOST=10.0.0.6 ./deploy/deploy-rsync.sh
#   BASE=/dt/ NODE6_HOST=10.0.0.6 ./deploy/deploy-rsync.sh
# =============================================================================
set -euo pipefail

NODE6_USER="${NODE6_USER:-root}"
NODE6_HOST="${NODE6_HOST:-bpoc-node6}"
WEB_ROOT="${WEB_ROOT:-/usr/share/nginx/b-dt}"
BASE="${BASE:-/}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

echo "==> 本地构建（base=${BASE}）"
pnpm install --frozen-lockfile
if [ "${BASE}" = "/" ]; then pnpm build; else pnpm --filter admin build -- --base="${BASE}"; fi

echo "==> 推送到 ${NODE6_USER}@${NODE6_HOST}:${WEB_ROOT}"
ssh "${NODE6_USER}@${NODE6_HOST}" "mkdir -p '${WEB_ROOT}'"
rsync -az --delete apps/admin/dist/ "${NODE6_USER}@${NODE6_HOST}:${WEB_ROOT}/"
ssh "${NODE6_USER}@${NODE6_HOST}" "nginx -t && systemctl reload nginx"
echo "完成：http://${NODE6_HOST}/"
