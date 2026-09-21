#!/usr/bin/env bash
set -euo pipefail

base_url="${1:?Usage: verify-deployment.sh http://host-or-domain}"
base_url="${base_url%/}"

curl --fail --silent --show-error "${base_url}/health/frontend"
echo
curl --fail --silent --show-error "${base_url}/health/ready"
echo
curl --fail --silent --show-error "${base_url}/api/v1/auth/me" >/dev/null
curl --fail --silent --show-error "${base_url}/api/v1/meta/dictionaries" >/dev/null
echo "HTTP checks passed: ${base_url}"
echo "Open ${base_url} in a browser to verify the WebSocket status changes to online."
