#!/usr/bin/env bash
set -Eeuo pipefail

base_url="${1:-${WMS_BASE_URL:-http://127.0.0.1:18080}}"
base_url="${base_url%/}"

for command_name in curl jq; do
    if ! command -v "${command_name}" >/dev/null 2>&1; then
        echo "缺少命令：${command_name}" >&2
        exit 1
    fi
done

check_health() {
    local endpoint="$1"
    local response
    response="$(curl --fail --silent --show-error --max-time 10 "${base_url}${endpoint}")"
    if [[ "$(jq -r '.data.status // empty' <<<"${response}")" != "UP" ]]; then
        echo "健康检查失败：${endpoint}" >&2
        jq . <<<"${response}" >&2
        exit 1
    fi
    echo "通过：${endpoint}"
}

check_health /health/live
check_health /health/ready

if [[ -z "${WMS_SMOKE_USERNAME:-}" ]]; then
    echo "未提供 WMS_SMOKE_USERNAME，仅执行健康检查。"
    exit 0
fi

: "${WMS_SMOKE_COMPANY:?执行登录测试时必须设置 WMS_SMOKE_COMPANY}"
: "${WMS_SMOKE_PASSWORD:?执行登录测试时必须设置 WMS_SMOKE_PASSWORD}"

cookie_jar="$(mktemp)"
trap 'rm -f "${cookie_jar}"' EXIT

csrf_response="$(curl --fail --silent --show-error --max-time 10 \
    --cookie-jar "${cookie_jar}" \
    "${base_url}/api/v1/auth/csrf")"
csrf_header="$(jq -r '.data.headerName' <<<"${csrf_response}")"
csrf_token="$(jq -r '.data.token' <<<"${csrf_response}")"
login_body="$(jq -n \
    --arg company "${WMS_SMOKE_COMPANY}" \
    --arg username "${WMS_SMOKE_USERNAME}" \
    --arg password "${WMS_SMOKE_PASSWORD}" \
    '{company: $company, username: $username, password: $password}')"

login_response="$(curl --fail --silent --show-error --max-time 10 \
    --cookie "${cookie_jar}" \
    --cookie-jar "${cookie_jar}" \
    --header "${csrf_header}: ${csrf_token}" \
    --header 'Content-Type: application/json' \
    --data "${login_body}" \
    "${base_url}/api/v1/auth/login")"

if [[ "$(jq -r '.code // empty' <<<"${login_response}")" != "OK" ]]; then
    echo "登录冒烟测试失败。" >&2
    jq . <<<"${login_response}" >&2
    exit 1
fi

dashboard_response="$(curl --fail --silent --show-error --max-time 10 \
    --cookie "${cookie_jar}" \
    "${base_url}/api/v1/dashboard/summary")"
if [[ "$(jq -r '.code // empty' <<<"${dashboard_response}")" != "OK" ]]; then
    echo "工作台接口冒烟测试失败。" >&2
    jq . <<<"${dashboard_response}" >&2
    exit 1
fi

echo "通过：登录及工作台接口"
