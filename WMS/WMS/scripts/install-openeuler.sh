#!/usr/bin/env bash
set -Eeuo pipefail

if (( EUID != 0 )); then
    echo "请使用 root 或 sudo 运行此脚本。" >&2
    exit 1
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "${script_dir}/.." && pwd)"
source_jar="${1:-${project_root}/target/mt-wms.jar}"
service_user="mtwms"
install_dir="/opt/mt-wms"
config_dir="/etc/mt-wms"
service_file="/etc/systemd/system/mt-wms.service"

if [[ ! -f "${source_jar}" ]]; then
    echo "找不到待部署 JAR：${source_jar}" >&2
    echo "请先运行 scripts/build-linux.sh，或把 JAR 路径作为第一个参数传入。" >&2
    exit 1
fi
if ! command -v systemctl >/dev/null 2>&1; then
    echo "当前系统没有 systemctl，无法安装 systemd 服务。" >&2
    exit 1
fi

if ! id "${service_user}" >/dev/null 2>&1; then
    useradd --system --home-dir "${install_dir}" --shell /sbin/nologin "${service_user}"
fi

install -d -m 0750 -o "${service_user}" -g "${service_user}" "${install_dir}"
install -d -m 0750 -o root -g "${service_user}" "${config_dir}"
temporary_jar="${install_dir}/.mt-wms.jar.new"
install -m 0640 -o root -g "${service_user}" "${source_jar}" "${temporary_jar}"
mv -f "${temporary_jar}" "${install_dir}/mt-wms.jar"
install -m 0644 "${project_root}/deploy/openeuler/mt-wms.service" "${service_file}"

if [[ ! -e "${config_dir}/mt-wms.env" ]]; then
    install -m 0600 -o root -g root \
        "${project_root}/deploy/openeuler/mt-wms.env.example" \
        "${config_dir}/mt-wms.env"
    echo "已创建配置模板：${config_dir}/mt-wms.env"
    echo "请先填写真实数据库配置，再启动服务。"
else
    echo "保留已有配置：${config_dir}/mt-wms.env"
fi

systemctl daemon-reload
echo "JAR 和 systemd 服务已安装。"
echo "数据库迁移完成后执行：systemctl enable --now mt-wms"
