#!/usr/bin/env bash
# =============================================================================
# SCS Phase 1 / P0-5: openGauss 真实实机集成测试一键执行脚本（bpoc-node4）
#
# 用途：将日常 mvn test 完全不覆盖的 JDBC 代码路径（OpenGaussStep* 系列集成测试）
#       固化为发版前强制门禁。直连本机 openGauss（默认取 runtime/backend.env 的
#       连接配置），激活全部 Step 门控环境变量后执行。
#
# 用法（在 bpoc-node4 上）：
#   bash deploy/scripts/run-opengauss-tests.sh              # 全部 Step 1~6
#   SCS_TEST_DATABASE=scs_test bash deploy/scripts/run-opengauss-tests.sh
#   ONLY_STEPS="Step1,Step6" bash deploy/scripts/run-opengauss-tests.sh
#
# 安全原则（与 verify-node4-step0.sh 一致）：
# 1. 严格禁止任何 DROP DATABASE / DROP SCHEMA / DROP TABLE / TRUNCATE 操作
# 2. 严格禁止把任何明文 Secret 输出到 stdout / 日志 / 终端
# 3. 测试自带 scs_stepN_it_ 前缀数据与每轮唯一 runPrefix，不触碰正式数据
# =============================================================================
set -Eeuo pipefail

fail_exit() {
    local reason="${1:-Unknown error}"
    echo ""
    echo "========================================"
    echo "SCS OPENGAUSS INTEGRATION TESTS"
    echo "========================================"
    echo "RESULT: FAIL"
    echo "Reason: ${reason}"
    echo "========================================"
    exit 1
}

trap 'fail_exit "Command failed unexpectedly at line ${LINENO}"' ERR

# -----------------------------------------------------------------------------
# 1. 路径与凭据加载（不打印任何 Secret）
# -----------------------------------------------------------------------------
SCS_ROOT="${SCS_ROOT:-/home/jingchanglong/Project_B/SCS}"
BACKEND_DIR="${SCS_ROOT}/backend"
RUNTIME_ENV="${SCS_ROOT}/runtime/backend.env"
MVN_BIN="${MVN_BIN:-mvn}"

if [[ ! -d "${BACKEND_DIR}" ]]; then
    fail_exit "Backend directory not found: ${BACKEND_DIR}"
fi
if [[ -f "${RUNTIME_ENV}" ]]; then
    # shellcheck disable=SC1090
    source "${RUNTIME_ENV}"
else
    echo "[WARN] runtime/backend.env not found, relying on pre-exported OPENGAUSS_* variables"
fi

for var in OPENGAUSS_HOST OPENGAUSS_PORT OPENGAUSS_DATABASE OPENGAUSS_USERNAME OPENGAUSS_PASSWORD; do
    if [[ -z "${!var:-}" ]]; then
        fail_exit "Missing required variable: ${var} (set it or provide runtime/backend.env)"
    fi
done

# 门禁目标库：默认独立可弃库 scs_test（与生产同构、含防删触发器）；
# 生产库 b_project 不再承接任何测试写入。重建流程：
#   loadtest-drop.sql → loadtest-setup.sql → 权威 DDL → safety_admin 授权
TEST_DATABASE="${SCS_TEST_DATABASE:-scs_test}"
TEST_HOST="${SCS_TEST_HOST:-${OPENGAUSS_HOST}}"
TEST_PORT="${SCS_TEST_PORT:-${OPENGAUSS_PORT}}"

command -v "${MVN_BIN}" >/dev/null 2>&1 || fail_exit "Maven not found (set MVN_BIN=...)"
command -v java >/dev/null 2>&1 || fail_exit "Java not found in PATH"

# 关键：source 得到的变量必须 export，surefire 子 JVM 才能读到（否则密码为空导致认证失败）
export OPENGAUSS_HOST="${TEST_HOST}"
export OPENGAUSS_PORT="${TEST_PORT}"
export OPENGAUSS_DATABASE="${TEST_DATABASE}"
export OPENGAUSS_USERNAME="${OPENGAUSS_USERNAME}"
export OPENGAUSS_PASSWORD="${OPENGAUSS_PASSWORD}"

echo "[OK] Target: ${TEST_HOST}:${TEST_PORT}/${TEST_DATABASE} (user: ${OPENGAUSS_USERNAME})"

# -----------------------------------------------------------------------------
# 2. 激活 Spike 主门控与各 Step 门控
# -----------------------------------------------------------------------------
export RUN_OPENGAUSS_SPIKE=true
export RUN_OPENGAUSS_STEP1=true
export RUN_OPENGAUSS_STEP2=true
export RUN_OPENGAUSS_STEP25=true
export RUN_OPENGAUSS_STEP3=true
export RUN_OPENGAUSS_STEP4=true
export RUN_OPENGAUSS_STEP5=true
export RUN_OPENGAUSS_STEP6=true
export RUN_OPENGAUSS_STEP7=true

# 可选：仅执行部分 Step（逗号分隔，如 ONLY_STEPS="Step1,Step6" → OpenGaussStep1*,OpenGaussStep6*）
TEST_PATTERN='OpenGaussStep*'
if [[ -n "${ONLY_STEPS:-}" ]]; then
    TEST_PATTERN=$(echo "${ONLY_STEPS}" | tr ',' '\n' | sed 's/^/OpenGauss/;s/$/*/' | paste -sd, -)
fi
echo "[OK] Test pattern: ${TEST_PATTERN} (spike gates all enabled)"

# -----------------------------------------------------------------------------
# 3. 执行集成测试
# -----------------------------------------------------------------------------
cd "${BACKEND_DIR}"
set +e
"${MVN_BIN}" -gs .mvn/settings.xml -s .mvn/settings.xml test \
    -Dtest="${TEST_PATTERN}" \
    -Dsurefire.failIfNoSpecifiedTests=true
MVN_RC=$?
set -e

echo ""
echo "========================================"
echo "SCS OPENGAUSS INTEGRATION TESTS"
echo "========================================"
if [[ ${MVN_RC} -eq 0 ]]; then
    echo "RESULT: PASS"
else
    echo "RESULT: FAIL (maven exit ${MVN_RC})"
    echo "Refer to ${BACKEND_DIR}/target/surefire-reports"
    echo "========================================"
    exit ${MVN_RC}
fi

# 注：scs_test 与生产同构含防删触发器，测试前缀行不做即时清理（测试自带每轮唯一前缀，
# 固定前缀用例为 upsert 语义，跨轮自收敛）；库可弃，积累过多时按脚本头注释重建。
echo "========================================"
exit 0
