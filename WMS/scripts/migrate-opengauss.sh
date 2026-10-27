#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "${script_dir}/.." && pwd)"
migration_dir="${project_root}/database/migrations"
environment_file="${WMS_MIGRATION_ENV_FILE:-/etc/mt-wms/mt-wms-migration.env}"

if [[ ! -r "${environment_file}" ]]; then
    echo "无法读取数据库迁移配置：${environment_file}" >&2
    echo "请参考 deploy/openeuler/mt-wms-migration.env.example 创建该文件。" >&2
    exit 1
fi

# The migration environment file is administrator-managed and must contain
# shell-compatible KEY=VALUE assignments.
set -a
# shellcheck disable=SC1090
source "${environment_file}"
set +a

container_runtime="${CONTAINER_RUNTIME:-isula}"
container_name="${OPENGAUSS_CONTAINER_NAME:-}"
gsql_bin="${OPENGAUSS_GSQL_BIN:-/usr/local/opengauss/bin/gsql}"
library_path="${OPENGAUSS_LIBRARY_PATH:-/usr/local/opengauss/lib:/scws/lib}"
database_name="${WMS_DB_NAME:-mt_wms}"
admin_username="${WMS_DB_ADMIN_USERNAME:-omm}"
admin_password="${WMS_DB_ADMIN_PASSWORD:-}"

if [[ -z "${container_name}" || -z "${admin_password}" ]]; then
    echo "迁移配置必须提供 OPENGAUSS_CONTAINER_NAME 和 WMS_DB_ADMIN_PASSWORD。" >&2
    exit 1
fi
for command_name in "${container_runtime}" sha256sum; do
    if ! command -v "${command_name}" >/dev/null 2>&1; then
        echo "缺少命令：${command_name}" >&2
        exit 1
    fi
done
if ! "${container_runtime}" inspect "${container_name}" >/dev/null 2>&1; then
    echo "找不到 openGauss 容器：${container_name}" >&2
    exit 1
fi

run_gsql() {
    "${container_runtime}" exec \
        -e "LD_LIBRARY_PATH=${library_path}" \
        "${container_name}" \
        "${gsql_bin}" \
        -d "${database_name}" \
        -U "${admin_username}" \
        -W "${admin_password}" \
        -v ON_ERROR_STOP=1 \
        "$@"
}

last_nonempty_line() {
    awk 'NF { value=$0 } END { gsub(/^[[:space:]]+|[[:space:]]+$/, "", value); print value }'
}

table_count="$(run_gsql -t -A -c \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='schema_version';" \
    | last_nonempty_line)"

shopt -s nullglob
migration_files=("${migration_dir}"/V*.sql)
if (( ${#migration_files[@]} == 0 )); then
    echo "没有找到数据库迁移脚本：${migration_dir}/V*.sql" >&2
    exit 1
fi
IFS=$'\n' migration_files=($(printf '%s\n' "${migration_files[@]}" | sort -V))
unset IFS

for migration_file in "${migration_files[@]}"; do
    file_name="$(basename -- "${migration_file}")"
    if [[ ! "${file_name}" =~ ^V([0-9]+)__(.+)\.sql$ ]]; then
        echo "迁移脚本命名不符合 V数字__说明.sql：${file_name}" >&2
        exit 1
    fi

    version="${BASH_REMATCH[1]}"
    description="${BASH_REMATCH[2]//_/ }"
    checksum="$(sha256sum "${migration_file}" | awk '{print $1}')"
    installed_checksum=""

    if [[ "${table_count}" == "1" ]]; then
        installed_checksum="$(run_gsql -t -A -c \
            "SELECT checksum FROM schema_version WHERE version='${version}' AND success=true;" \
            | last_nonempty_line)"
    fi

    if [[ -n "${installed_checksum}" ]]; then
        if [[ "${installed_checksum}" != "${checksum}" ]]; then
            echo "迁移脚本 V${version} 的校验值已变化，拒绝继续。" >&2
            exit 1
        fi
        echo "V${version} 已执行，跳过。"
        continue
    fi

    container_file="/tmp/mt-wms-${file_name}"
    "${container_runtime}" cp "${migration_file}" "${container_name}:${container_file}"
    if ! run_gsql -f "${container_file}"; then
        "${container_runtime}" exec "${container_name}" rm -f "${container_file}" >/dev/null 2>&1 || true
        echo "V${version} 执行失败。" >&2
        exit 1
    fi
    "${container_runtime}" exec "${container_name}" rm -f "${container_file}" >/dev/null

    escaped_description="${description//\'/\'\'}"
    run_gsql -c \
        "INSERT INTO schema_version(version, description, checksum, success) VALUES ('${version}', '${escaped_description}', '${checksum}', true);" \
        >/dev/null
    table_count=1
    echo "V${version} 执行成功。"
done

echo "openGauss 数据库迁移完成：${database_name}"
