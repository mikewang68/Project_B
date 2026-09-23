#!/usr/bin/env bash
# =============================================================================
# B 项目数字孪生前端 —— 在 bpoc-node6（openEuler 24.03 / Node 24 / pnpm 10）上
# 一键构建并发布到 Nginx 静态目录。
#
# 用法：
#   chmod +x deploy/build-and-deploy.sh
#   ./deploy/build-and-deploy.sh                 # 根路径部署（默认）
#   BASE=/dt/ ./deploy/build-and-deploy.sh       # 子路径 /dt/ 部署
#   WEB_ROOT=/opt/b-dt ./deploy/build-and-deploy.sh
#
# 依赖：node>=20.19、pnpm>=9（服务器已装 pnpm 10.34.5）、rsync、nginx。
# =============================================================================
set -euo pipefail

# ---- 可配置变量 ----
WEB_ROOT="${WEB_ROOT:-/usr/share/nginx/b-dt}"   # Nginx root 指向的目录
BASE="${BASE:-/}"                                # 部署 base，根路径用 /
PNPM_VERSION_REQ="10.34.5"

# 切到仓库根目录（脚本位于 deploy/ 下）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

echo "==> [1/6] 运行时检查"
command -v node >/dev/null || { echo "未找到 node，请先安装 Node 24"; exit 1; }
echo "    node $(node -v)"
if ! command -v pnpm >/dev/null; then
  echo "    未找到 pnpm，尝试通过 corepack 启用 pnpm@${PNPM_VERSION_REQ}"
  command -v corepack >/dev/null || { echo "未找到 corepack/pnpm，请先安装 pnpm"; exit 1; }
  corepack enable
  corepack prepare "pnpm@${PNPM_VERSION_REQ}" --activate
fi
echo "    pnpm $(pnpm -v)"

echo "==> [2/6] 安装依赖（frozen-lockfile，保证可重复构建）"
pnpm install --frozen-lockfile

echo "==> [3/6] 类型检查 + 生产构建（base=${BASE}）"
if [ "${BASE}" = "/" ]; then
  pnpm build
else
  pnpm --filter admin build -- --base="${BASE}"
fi

DIST_DIR="${REPO_ROOT}/apps/admin/dist"
[ -f "${DIST_DIR}/index.html" ] || { echo "构建产物缺失：${DIST_DIR}/index.html"; exit 1; }

echo "==> [4/6] 同步产物到 ${WEB_ROOT}"
sudo mkdir -p "${WEB_ROOT}"
sudo rsync -a --delete "${DIST_DIR}/" "${WEB_ROOT}/"

echo "==> [5/6] 校验并重载 Nginx"
sudo nginx -t
sudo systemctl reload nginx

echo "==> [6/6] 本机自检"
sleep 1
HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/ || true)"
echo "    首页 HTTP 状态：${HTTP_CODE}（200 即成功）"
echo "完成。浏览器访问 http://<node6-ip>/ （子路径则为 /dt/）"
