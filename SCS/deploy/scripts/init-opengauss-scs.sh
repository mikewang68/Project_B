#!/usr/bin/env bash
# =============================================================================
# SCS Phase 1 / Step 0: openGauss 数据库与 Schema 初始化脚本
# 仅供在 bpoc-node4 (192.168.101.57) 上执行
#
# 安全原则：
# 1. 严格禁止任何 DROP DATABASE / DROP SCHEMA / DROP TABLE / TRUNCATE 操作
# 2. 严格限制仅操作 bpoc-opengauss (5432) 与 b_project 库，严禁接触 25432/25433
# 3. 严格禁止把任何明文密码写入日志或回显
# 4. 若目标 safety schema 包含业务对象，必须立即停止并输出 EXISTING_SAFETY_SCHEMA_REQUIRES_REVIEW
# =============================================================================
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
DDL_FILE="${PROJECT_ROOT}/backend/src/main/resources/db/design/openGauss-schema-final-draft.sql"

CONTAINER_NAME="${OPENGAUSS_CONTAINER_NAME:-bpoc-opengauss}"
KVROCKS_CONTAINER="${KVROCKS_CONTAINER_NAME:-bpoc-kvrocks}"
DB_NAME="${OPENGAUSS_DATABASE:-b_project}"
APP_USER="${OPENGAUSS_APP_USER:-safety_admin}"
APP_PASSWORD="${OPENGAUSS_APP_PASSWORD:-${OPENGAUSS_PASSWORD:-}}"
ISULA_BIN="${ISULA_BIN:-isula}"

OG_GAUSSHOME="/usr/local/opengauss"
OG_GSQL_PATH="/usr/local/opengauss/bin/gsql"
OG_LD_LIBRARY_PATH="/usr/local/opengauss/lib:/scws/lib"
OG_PATH="/usr/local/opengauss/bin:/scws/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

echo "============================================================================="
echo "SCS openGauss Step 0 初始化与实机预检"
echo "============================================================================="
echo "容器名称: ${CONTAINER_NAME}"
echo "目标数据库: ${DB_NAME}"
echo "目标应用用户: ${APP_USER}"
echo "DDL 文件: ${DDL_FILE}"

if [[ ! -f "${DDL_FILE}" ]]; then
    echo "ERROR: 找不到权威 DDL 文件: ${DDL_FILE}" >&2
    exit 1
fi

# 1. 校验当前节点是否为 bpoc-node4 (IP: 192.168.101.57)
CURRENT_HOST="$(hostname 2>/dev/null || true)"
ALL_IPS="$(hostname -I 2>/dev/null || ip addr show 2>/dev/null || true)"
echo "当前主机名: ${CURRENT_HOST}"
echo "当前主机 IP: ${ALL_IPS}"

if [[ "${CURRENT_HOST}" != "bpoc-node4"* && "${ALL_IPS}" != *"192.168.101.57"* ]]; then
    echo "ERROR: 本脚本必须且仅能在 bpoc-node4 (IP: 192.168.101.57) 执行！" >&2
    echo "当前节点主机名或 IP 不符合预期，安全终止。" >&2
    exit 1
fi

# 2. 运行时预检 (Runtime Preflight)
# 2.1 检查 openGauss 容器是否存在且正在运行
if ! sudo "${ISULA_BIN}" inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
    echo "ERROR: Container ${CONTAINER_NAME} not found: OPEN_GAUSS_CONTAINER_NOT_RUNNING" >&2
    exit 1
fi

ISULA_RUNNING="$(sudo "${ISULA_BIN}" inspect -f '{{.State.Running}}' "${CONTAINER_NAME}" 2>/dev/null || true)"
if [[ "${ISULA_RUNNING}" != "true" ]]; then
    echo "ERROR: Container ${CONTAINER_NAME} is not in running state: OPEN_GAUSS_CONTAINER_NOT_RUNNING" >&2
    exit 1
fi
echo "[OK] openGauss 容器 ${CONTAINER_NAME} 正在运行。"

# 2.2 检查 Kvrocks 容器状态
if sudo "${ISULA_BIN}" inspect "${KVROCKS_CONTAINER}" >/dev/null 2>&1; then
    echo "[OK] Kvrocks 容器 ${KVROCKS_CONTAINER} 状态正常。"
else
    echo "[WARN] 找不到 Kvrocks 容器 ${KVROCKS_CONTAINER}，请核实容器名称与状态。" >&2
fi

# 2.3 检查容器内 gsql 可执行文件
if ! sudo "${ISULA_BIN}" exec "${CONTAINER_NAME}" test -x "${OG_GSQL_PATH}"; then
    echo "ERROR: ${OG_GSQL_PATH} not found or not executable in container: OPEN_GAUSS_GSQL_NOT_FOUND" >&2
    exit 1
fi
echo "[OK] gsql 可执行文件存在: ${OG_GSQL_PATH}"

# 2.4 检查容器内 /usr/local/opengauss/lib 目录
if ! sudo "${ISULA_BIN}" exec "${CONTAINER_NAME}" test -d "${OG_GAUSSHOME}/lib"; then
    echo "ERROR: ${OG_GAUSSHOME}/lib directory not found in container: OPEN_GAUSS_LIB_NOT_FOUND" >&2
    exit 1
fi
echo "[OK] openGauss 共享库目录存在: ${OG_GAUSSHOME}/lib"

# 2.5 检查容器内是否存在 OS 用户 omm
if ! sudo "${ISULA_BIN}" exec "${CONTAINER_NAME}" id omm >/dev/null 2>&1; then
    echo "ERROR: OS user omm not found inside container: OPEN_GAUSS_OS_USER_OMM_NOT_FOUND" >&2
    exit 1
fi
echo "[OK] 容器内 OS 用户 omm 确认存在。"

# 2.6 探测以 omm 用户身份执行命令的方式 (su vs runuser)
ADMIN_EXEC_MODE=""
if sudo "${ISULA_BIN}" exec "${CONTAINER_NAME}" su -s /bin/sh - omm -c 'id -u' >/dev/null 2>&1; then
    ADMIN_EXEC_MODE="su"
elif sudo "${ISULA_BIN}" exec "${CONTAINER_NAME}" runuser -u omm -- id -u >/dev/null 2>&1; then
    ADMIN_EXEC_MODE="runuser"
else
    echo "ERROR: Unable to switch to OS user omm via su or runuser: OPEN_GAUSS_OS_USER_OMM_NOT_FOUND" >&2
    exit 1
fi
echo "[OK] openGauss admin execution mode: ${ADMIN_EXEC_MODE}"

# 2.7 验证 gsql --version (加载完整运行时环境)
GSQL_VER_OUT="$(sudo "${ISULA_BIN}" exec "${CONTAINER_NAME}" \
    sh -c "export GAUSSHOME='${OG_GAUSSHOME}'; export PATH='${OG_PATH}'; export LD_LIBRARY_PATH='${OG_LD_LIBRARY_PATH}'; exec '${OG_GSQL_PATH}' --version" 2>/dev/null || true)"
if ! echo "${GSQL_VER_OUT}" | grep -qi "openGauss"; then
    echo "ERROR: gsql runtime execution failed: OPEN_GAUSS_GSQL_RUNTIME_INVALID" >&2
    echo "Output: ${GSQL_VER_OUT}" >&2
    exit 1
fi
echo "[OK] openGauss gsql version: ${GSQL_VER_OUT}"

# =============================================================================
# 3. openGauss 统一运行时 Helper 函数 (通过 stdin 传递 SQL，杜绝 quoting bug)
# =============================================================================
run_og_admin_sql() {
    local db="${1:-postgres}"
    local sql="${2:-}"
    local cmd
    if [[ "${ADMIN_EXEC_MODE}" == "su" ]]; then
        cmd=(su -s /bin/sh - omm -c 'export GAUSSHOME="/usr/local/opengauss"; export PATH="/usr/local/opengauss/bin:/scws/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"; export LD_LIBRARY_PATH="/usr/local/opengauss/lib:/scws/lib"; exec /usr/local/opengauss/bin/gsql -d "$1" -v ON_ERROR_STOP=1 -q -t -A -c "$2"' _ "${db}" "${sql}")
    else
        cmd=(runuser -u omm -- sh -c 'export GAUSSHOME="/usr/local/opengauss"; export PATH="/usr/local/opengauss/bin:/scws/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"; export LD_LIBRARY_PATH="/usr/local/opengauss/lib:/scws/lib"; exec /usr/local/opengauss/bin/gsql -d "$1" -v ON_ERROR_STOP=1 -q -t -A -c "$2"' _ "${db}" "${sql}")
    fi
    sudo "${ISULA_BIN}" exec "${CONTAINER_NAME}" "${cmd[@]}"
}

run_og_admin_file() {
    local db="${1:-postgres}"
    local file="${2}"
    sudo "${ISULA_BIN}" cp "${file}" "${CONTAINER_NAME}:/tmp/scs-schema.sql"
    local cmd
    if [[ "${ADMIN_EXEC_MODE}" == "su" ]]; then
        cmd=(su -s /bin/sh - omm -c 'export GAUSSHOME="/usr/local/opengauss"; export PATH="/usr/local/opengauss/bin:/scws/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"; export LD_LIBRARY_PATH="/usr/local/opengauss/lib:/scws/lib"; exec /usr/local/opengauss/bin/gsql -d "$1" -v ON_ERROR_STOP=1 -f /tmp/scs-schema.sql' _ "${db}")
    else
        cmd=(runuser -u omm -- sh -c 'export GAUSSHOME="/usr/local/opengauss"; export PATH="/usr/local/opengauss/bin:/scws/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"; export LD_LIBRARY_PATH="/usr/local/opengauss/lib:/scws/lib"; exec /usr/local/opengauss/bin/gsql -d "$1" -v ON_ERROR_STOP=1 -f /tmp/scs-schema.sql' _ "${db}")
    fi
    sudo "${ISULA_BIN}" exec "${CONTAINER_NAME}" "${cmd[@]}"
    sudo "${ISULA_BIN}" exec "${CONTAINER_NAME}" rm -f /tmp/scs-schema.sql
}

# 2.8 管理员只读预检: SELECT 1;
ADMIN_PRECHECK_OUT="$(run_og_admin_sql postgres "SELECT 1;" 2>/dev/null || true)"
ADMIN_PRECHECK_VAL="$(echo "${ADMIN_PRECHECK_OUT}" | tr -dc '0-9')"
if [[ "${ADMIN_PRECHECK_VAL}" != "1" ]]; then
    echo "ERROR: Local omm admin SELECT 1 check failed: OPEN_GAUSS_LOCAL_ADMIN_LOGIN_FAILED" >&2
    echo "Output: ${ADMIN_PRECHECK_OUT}" >&2
    exit 1
fi
echo "[OK] openGauss local omm admin precheck passed: SELECT 1 -> 1"

# =============================================================================
# 4. 读取 safety_admin 应用密码
# =============================================================================
if [[ -z "${APP_PASSWORD}" ]]; then
    if [[ -t 0 ]]; then
        read -s -p "请输入新的 safety_admin 应用密码: " APP_PASSWORD
        echo
        if [[ -z "${APP_PASSWORD}" ]]; then
            echo "ERROR: safety_admin 密码不能为空" >&2
            exit 1
        fi
    else
        echo "ERROR: OPENGAUSS_APP_PASSWORD 环境变量未设置，且非交互终端环境。" >&2
        exit 1
    fi
fi

# 对密码中的单引号进行 SQL 安全转义 (避免注入与语法解析错误)
ESCAPED_APP_PASSWORD="${APP_PASSWORD//\'/\'\'}"

# =============================================================================
# 5. 检查并创建应用用户 safety_admin 与数据库 b_project
# =============================================================================
echo ">>> 检查应用角色 ${APP_USER} 是否存在..."
USER_EXISTS=$(run_og_admin_sql postgres "SELECT count(*) FROM pg_roles WHERE rolname='${APP_USER}';")
USER_EXISTS=$(echo "${USER_EXISTS}" | tr -dc '0-9')

if [[ "${USER_EXISTS}" -eq 0 ]]; then
    echo "应用用户 ${APP_USER} 不存在，正在创建..."
    run_og_admin_sql postgres "CREATE USER ${APP_USER} IDENTIFIED BY '${ESCAPED_APP_PASSWORD}';"
    echo "[OK] 用户 ${APP_USER} 创建成功。"
else
    echo "应用用户 ${APP_USER} 已存在，更新密码以同步本次部署输入..."
    run_og_admin_sql postgres "ALTER USER ${APP_USER} IDENTIFIED BY '${ESCAPED_APP_PASSWORD}';"
    echo "[OK] 用户 ${APP_USER} 密码已同步。"
fi

echo ">>> 检查数据库 ${DB_NAME} 是否存在..."
DB_EXISTS=$(run_og_admin_sql postgres "SELECT count(*) FROM pg_database WHERE datname='${DB_NAME}';")
DB_EXISTS=$(echo "${DB_EXISTS}" | tr -dc '0-9')

if [[ "${DB_EXISTS}" -eq 0 ]]; then
    echo "数据库 ${DB_NAME} 不存在，正在创建 (OWNER: ${APP_USER}, DBCOMPATIBILITY: PG)..."
    run_og_admin_sql postgres "CREATE DATABASE ${DB_NAME} OWNER ${APP_USER} DBCOMPATIBILITY='PG';"
    echo "[OK] 数据库 ${DB_NAME} 创建成功。"
else
    echo "[OK] 数据库 ${DB_NAME} 已存在。"
    run_og_admin_sql postgres "ALTER DATABASE ${DB_NAME} OWNER TO ${APP_USER};"
fi

# =============================================================================
# 6. 检查 safety schema 状态 (严格防覆盖审查)
# =============================================================================
echo ">>> 检查 safety schema 状态..."
SCHEMA_EXISTS=$(run_og_admin_sql "${DB_NAME}" "SELECT count(*) FROM information_schema.schemata WHERE schema_name='safety';")
SCHEMA_EXISTS=$(echo "${SCHEMA_EXISTS}" | tr -dc '0-9')

if [[ "${SCHEMA_EXISTS}" -gt 0 ]]; then
    TABLE_COUNT=$(run_og_admin_sql "${DB_NAME}" "SELECT count(*) FROM information_schema.tables WHERE table_schema='safety' AND table_type='BASE TABLE';")
    TABLE_COUNT=$(echo "${TABLE_COUNT}" | tr -dc '0-9')

    SEQ_COUNT=$(run_og_admin_sql "${DB_NAME}" "SELECT count(*) FROM information_schema.sequences WHERE sequence_schema='safety';")
    SEQ_COUNT=$(echo "${SEQ_COUNT}" | tr -dc '0-9')
    if [[ -z "${SEQ_COUNT}" || "${SEQ_COUNT}" -eq 0 ]]; then
        SEQ_COUNT=$(run_og_admin_sql "${DB_NAME}" "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind = 'S' AND n.nspname = 'safety';")
        SEQ_COUNT=$(echo "${SEQ_COUNT}" | tr -dc '0-9')
    fi

    if [[ "${TABLE_COUNT:-0}" -gt 0 || "${SEQ_COUNT:-0}" -gt 0 ]]; then
        echo "=============================================================================" >&2
        echo "EXISTING_SAFETY_SCHEMA_REQUIRES_REVIEW" >&2
        echo "safety schema 已存在且包含业务对象 (Tables: ${TABLE_COUNT}, Sequences: ${SEQ_COUNT})。" >&2
        echo "现有表清单:" >&2
        run_og_admin_sql "${DB_NAME}" "SELECT table_name FROM information_schema.tables WHERE table_schema='safety' AND table_type='BASE TABLE' ORDER BY table_name;" >&2
        echo "现有序列清单:" >&2
        run_og_admin_sql "${DB_NAME}" "SELECT c.relname AS sequence_name FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind = 'S' AND n.nspname = 'safety' ORDER BY c.relname;" >&2
        echo "根据设计规范严禁自动覆盖、DROP 或 TRUNCATE！请人工核实处理。" >&2
        echo "=============================================================================" >&2
        exit 2
    else
        echo "safety schema 已存在且为空，可以继续安全执行 DDL。"
    fi
else
    echo "创建 safety schema..."
    run_og_admin_sql "${DB_NAME}" "CREATE SCHEMA IF NOT EXISTS safety AUTHORIZATION ${APP_USER};"
    run_og_admin_sql "${DB_NAME}" "GRANT ALL ON SCHEMA safety TO ${APP_USER};"
    echo "[OK] safety schema 创建成功并授权给 ${APP_USER}。"
fi

# =============================================================================
# 7. 通过 stdin 管道执行权威 DDL 文件 (避免任何临时文件与字符集解析问题)
# =============================================================================
echo ">>> 正在执行 SCS 权威 DDL: ${DDL_FILE}..."
run_og_admin_file "${DB_NAME}" "${DDL_FILE}"
echo "[OK] DDL 语句执行完毕。"

# =============================================================================
# 8. 授权 safety_admin
# =============================================================================
echo ">>> 授予 ${APP_USER} 在 safety schema 下所有表和序列的完整权限..."
run_og_admin_sql "${DB_NAME}" "GRANT ALL ON ALL TABLES IN SCHEMA safety TO ${APP_USER};"
run_og_admin_sql "${DB_NAME}" "GRANT ALL ON ALL SEQUENCES IN SCHEMA safety TO ${APP_USER};"
run_og_admin_sql "${DB_NAME}" "ALTER DEFAULT PRIVILEGES IN SCHEMA safety GRANT ALL ON TABLES TO ${APP_USER};"
run_og_admin_sql "${DB_NAME}" "ALTER DEFAULT PRIVILEGES IN SCHEMA safety GRANT ALL ON SEQUENCES TO ${APP_USER};"
run_og_admin_sql "${DB_NAME}" "ALTER USER ${APP_USER} SET search_path TO safety, public;"
echo "[OK] 权限授权与默认权限配置完成。"

# =============================================================================
# 9. 验证数据库对象数量 (25 Tables, 21 Sequences)
# =============================================================================
echo ">>> 验证数据库对象数量..."
FINAL_TABLE_COUNT=$(run_og_admin_sql "${DB_NAME}" "SELECT count(*) FROM information_schema.tables WHERE table_schema='safety' AND table_type='BASE TABLE';")
FINAL_TABLE_COUNT=$(echo "${FINAL_TABLE_COUNT}" | tr -dc '0-9')

FINAL_SEQ_COUNT=$(run_og_admin_sql "${DB_NAME}" "SELECT count(*) FROM information_schema.sequences WHERE sequence_schema='safety';")
FINAL_SEQ_COUNT=$(echo "${FINAL_SEQ_COUNT}" | tr -dc '0-9')
if [[ -z "${FINAL_SEQ_COUNT}" || "${FINAL_SEQ_COUNT}" -eq 0 ]]; then
    FINAL_SEQ_COUNT=$(run_og_admin_sql "${DB_NAME}" "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind = 'S' AND n.nspname = 'safety';")
    FINAL_SEQ_COUNT=$(echo "${FINAL_SEQ_COUNT}" | tr -dc '0-9')
fi

echo "============================================================================="
echo "SCS DDL 对象核验结果:"
echo "Tables: ${FINAL_TABLE_COUNT} / 25"
echo "Sequences: ${FINAL_SEQ_COUNT} / 21"
echo "============================================================================="

if [[ "${FINAL_TABLE_COUNT}" -eq 25 && "${FINAL_SEQ_COUNT}" -eq 21 ]]; then
    echo "[SUCCESS] openGauss SCS schema: 25 tables and 21 sequences initialized successfully."
else
    echo "ERROR: Object count mismatch! Expected 25 tables, got ${FINAL_TABLE_COUNT}; expected 21 sequences, got ${FINAL_SEQ_COUNT}." >&2
    exit 3
fi

# =============================================================================
# 10. 验证 safety_admin 真实 TCP (127.0.0.1:5432) 连通性与权限
# =============================================================================
echo ">>> 验证 ${APP_USER} 真实 TCP (127.0.0.1:5432) 连通性与权限..."
TCP_TEST_OUT="$(sudo "${ISULA_BIN}" exec "${CONTAINER_NAME}" runuser -u omm -- \
    sh -c "export GAUSSHOME='${OG_GAUSSHOME}'; export PATH='${OG_PATH}'; export LD_LIBRARY_PATH='${OG_LD_LIBRARY_PATH}'; exec '${OG_GSQL_PATH}' -h 127.0.0.1 -p 5432 -d '${DB_NAME}' -U '${APP_USER}' -W \"\$1\" -v ON_ERROR_STOP=1 -q -t -A -c \"\$2\"" \
    _ "${APP_PASSWORD}" "SELECT current_database(), current_user;" 2>/dev/null || true)"

if [[ "${TCP_TEST_OUT}" != *"${DB_NAME}|${APP_USER}"* && "${TCP_TEST_OUT}" != *"${DB_NAME}"* ]]; then
    echo "ERROR: safety_admin TCP login failed: SAFETY_ADMIN_TCP_LOGIN_FAILED" >&2
    echo "Unable to login as ${APP_USER} to ${DB_NAME} on 127.0.0.1:5432" >&2
    exit 4
fi
echo "[OK] safety_admin TCP login passed (database: ${DB_NAME}, user: ${APP_USER})"

APP_TABLE_COUNT="$(sudo "${ISULA_BIN}" exec "${CONTAINER_NAME}" runuser -u omm -- \
    sh -c "export GAUSSHOME='${OG_GAUSSHOME}'; export PATH='${OG_PATH}'; export LD_LIBRARY_PATH='${OG_LD_LIBRARY_PATH}'; exec '${OG_GSQL_PATH}' -h 127.0.0.1 -p 5432 -d '${DB_NAME}' -U '${APP_USER}' -W \"\$1\" -v ON_ERROR_STOP=1 -q -t -A -c \"\$2\"" \
    _ "${APP_PASSWORD}" "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'safety';" 2>/dev/null || true)"
APP_TABLE_COUNT="$(echo "${APP_TABLE_COUNT}" | tr -dc '0-9')"
echo "[OK] safety_admin 可以正常查询 safety schema: ${APP_TABLE_COUNT} / 25 表。"

echo "============================================================================="
echo "openGauss 初始化与验证全部通过！"
echo "下一步: 在 runtime/backend.env 中配置真实 Secret，并执行 verify-node4-step0.sh。"
echo "============================================================================="
exit 0
