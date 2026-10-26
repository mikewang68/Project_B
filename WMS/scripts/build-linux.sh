#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "${script_dir}/.." && pwd)"
maven_command="${MAVEN_CMD:-mvn}"

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "缺少命令：$1" >&2
        exit 1
    fi
}

require_command java
require_command node
require_command pnpm
require_command "${maven_command}"

java_version="$(java -version 2>&1 | head -n 1)"
node_version="$(node --version)"
pnpm_version="$(pnpm --version)"
maven_version="$(${maven_command} --version | head -n 1)"

if [[ "${java_version}" != *'"17.'* && "${java_version}" != *'"17"'* ]]; then
    echo "需要 Java 17，当前为：${java_version}" >&2
    exit 1
fi
if [[ ! "${maven_version}" =~ Apache\ Maven\ 3\.9\. ]]; then
    echo "需要 Maven 3.9.x，当前为：${maven_version}" >&2
    exit 1
fi
if [[ ! "${node_version}" =~ ^v24\. ]]; then
    echo "需要 Node.js 24.x，当前为：${node_version}" >&2
    exit 1
fi
if [[ ! "${pnpm_version}" =~ ^(10|11)\. ]]; then
    echo "需要 pnpm 10.34.5 或 11.x，当前为：${pnpm_version}" >&2
    exit 1
fi

echo "Java：${java_version}"
echo "Maven：${maven_version}"
echo "Node.js：${node_version}"
echo "pnpm：${pnpm_version}"

cd "${project_root}"
"${maven_command}" clean package

echo "构建完成：${project_root}/target/mt-wms.jar"
