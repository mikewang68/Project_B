#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
release_root="${project_root}/deploy/generated/$(date +%Y%m%d-%H%M%S)"

require_version() {
  local name="$1"
  local actual="$2"
  local expected="$3"
  if [[ "$actual" != "$expected" ]]; then
    echo "ERROR: ${name} requires ${expected}, found ${actual}" >&2
    exit 1
  fi
}

java_version="$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
maven_version="$(mvn -version | awk '/Apache Maven/ {print $3; exit}')"
node_version="$(node --version | sed 's/^v//')"
npm_version="$(npm --version)"
pnpm_version="$(pnpm --version)"

[[ "$java_version" == 17.* ]] || { echo "ERROR: Java 17 required, found ${java_version}" >&2; exit 1; }
require_version "Maven" "$maven_version" "3.9.16"
require_version "Node.js" "$node_version" "24.18.0"
require_version "npm" "$npm_version" "11.16.0"
require_version "pnpm" "$pnpm_version" "10.34.5"

cd "$project_root"
pnpm install --frozen-lockfile
pnpm --filter b-project-safety-gate-web typecheck
pnpm --filter b-project-safety-gate-web test
pnpm --filter b-project-safety-gate-web build

mvn -f backend/pom.xml \
  -gs backend/.mvn/settings.xml \
  -s backend/.mvn/settings.xml \
  clean verify

mkdir -p "$release_root/backend" "$release_root/frontend"
jar_path="$(find backend/target -maxdepth 1 -type f -name '*.jar' ! -name '*.original' | head -n 1)"
[[ -n "$jar_path" ]] || { echo "ERROR: backend JAR was not produced" >&2; exit 1; }
cp "$jar_path" "$release_root/backend/safety-gate-service.jar"
cp -a frontend/dist/. "$release_root/frontend/"

echo "Release created: $release_root"
