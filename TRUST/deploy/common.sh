#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export ROOT
load_deployment() {
  local values key value
  values=$(python3 "$ROOT/deploy/deployment.py" --format env) || return
  while IFS='=' read -r key value; do
    export "$key=$value"
  done <<< "$values"
}
mkdir -p "$ROOT"/{tools,artifacts,runtime/{logs,pids,secrets,backups}}
chmod 700 "$ROOT/runtime" "$ROOT/runtime/secrets" "$ROOT/runtime/backups"
start_process() {
  local name=$1; shift
  local pf="$ROOT/runtime/pids/$name.pid"
  if [[ -f "$pf" ]] && kill -0 "$(cat "$pf")" 2>/dev/null; then
    local existing; existing=$(cat "$pf")
    local existing_cwd; existing_cwd=$(readlink -f "/proc/$existing/cwd" || true)
    if [[ "$existing_cwd" == "$ROOT" || "$existing_cwd" == "$ROOT/"* ]] && [[ "$(awk '{print $3}' "/proc/$existing/stat")" != Z ]]; then
      echo "$name already running"; return
    fi
  fi
  (cd "$ROOT"; nohup "$@" >"$ROOT/runtime/logs/$name.log" 2>&1 </dev/null & echo $! >"$pf")
}
stop_process() {
  local pf="$ROOT/runtime/pids/$1.pid"
  [[ -f "$pf" ]] || return 0
  local proc; proc=$(cat "$pf")
  [[ "$proc" =~ ^[0-9]+$ ]] || return 1
  if kill -0 "$proc" 2>/dev/null; then
    if [[ "$(awk '{print $3}' "/proc/$proc/stat")" == Z ]]; then rm -f -- "$pf"; return; fi
    local process_cwd; process_cwd=$(readlink -f "/proc/$proc/cwd" || true)
    [[ "$process_cwd" == "$ROOT" || "$process_cwd" == "$ROOT/"* ]] || { echo "Refusing unrelated PID"; return 1; }
    kill "$proc"
    for i in {1..120}; do
      kill -0 "$proc" 2>/dev/null || break
      [[ "$(awk '{print $3}' "/proc/$proc/stat" 2>/dev/null)" == Z ]] && break
      sleep .25
    done
    if kill -0 "$proc" 2>/dev/null && [[ "$(awk '{print $3}' "/proc/$proc/stat" 2>/dev/null)" != Z ]]; then
      echo "$1 has not stopped; PID retained"; return 1
    fi
  fi
  rm -f -- "$pf"
}
