#!/usr/bin/env bash
# =============================================================================
# SCS D-3: openGauss b_project 库每日逻辑备份（bpoc-node4）
#
# 设计：
#   - gs_dump 在 bpoc-opengauss 容器内以 omm 执行（容器内 /tmp 中转，isula cp 导出）
#   - 输出 /var/backups/scs-opengauss/b_project-YYYYmmdd-HHMMSS.sql.gz + .sha256
#   - 保留 14 天，到期自动清理；全程不打印任何口令
# 安装（root cron，每日 02:30）：
#   printf '30 2 * * * root bash /home/jingchanglong/Project_B/SCS/deploy/scripts/backup-opengauss-scs.sh\n' \
#     > /etc/cron.d/scs-opengauss-backup
# =============================================================================
set -Eeuo pipefail

BACKUP_DIR="${SCS_BACKUP_DIR:-/var/backups/scs-opengauss}"
CONTAINER="${OPENGAUSS_CONTAINER_NAME:-bpoc-opengauss}"
DATABASE="${SCS_BACKUP_DATABASE:-b_project}"
KEEP_DAYS=14

mkdir -p "${BACKUP_DIR}"
STAMP=$(date +%Y%m%d-%H%M%S)
INNER="/tmp/scs_backup_${STAMP}.sql"
OUTER="${BACKUP_DIR}/b_project-${STAMP}.sql"

echo "[backup] gs_dump ${DATABASE} @ ${STAMP}"
isula exec "${CONTAINER}" su - omm -c "gs_dump -F p -f ${INNER} ${DATABASE}"
isula cp "${CONTAINER}:${INNER}" "${OUTER}"
isula exec "${CONTAINER}" su - omm -c "rm -f ${INNER}"

gzip -f "${OUTER}"
sha256sum "${OUTER}.gz" > "${OUTER}.gz.sha256"
chmod 600 "${OUTER}.gz" "${OUTER}.gz.sha256"

echo "[backup] written: ${OUTER}.gz ($(du -h "${OUTER}.gz" | cut -f1))"

# 轮转：仅清理本脚本命名规则的产物
find "${BACKUP_DIR}" -name 'b_project-*.sql.gz' -mtime +${KEEP_DAYS} -delete
find "${BACKUP_DIR}" -name 'b_project-*.sql.gz.sha256' -mtime +${KEEP_DAYS} -delete
echo "[backup] rotation done (keep ${KEEP_DAYS} days)"
