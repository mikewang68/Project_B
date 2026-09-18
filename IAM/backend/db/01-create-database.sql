-- ============================================================================
-- Project_B IAM/SYS 建库与账号脚本（openGauss 6.0.5，PG 兼容模式）
--
-- 以 openGauss 管理员账号（如 gaussdb）执行：
--   gsql -d postgres -h <host> -p 5432 -U gaussdb -f 01-create-database.sql
--
-- 注意：
-- 1. 请把下面的占位密码替换为真实强密码（大小写字母+数字+特殊符号，8 位以上），
--    真实密码不得提交 Git；后端通过环境变量 DB_PASSWORD / JWT_SECRET 注入。
-- 2. 本脚本只建库、schema 与账号；表结构见 02-iam-schema.sql 与
--    SYS/backend/db/01-sys-schema.sql；种子数据见 03-iam-seed.sql / 02-sys-seed.sql。
-- 3. CREATE DATABASE 不能在事务块中执行，gsql -f 单语句执行即可。
-- ============================================================================

-- 1) 数据库（已存在时请跳过本句）
CREATE DATABASE project_b DBCOMPATIBILITY 'PG' ENCODING 'UTF8' TEMPLATE template0;

-- 后续对象建在 project_b 中
\c project_b

-- 2) Schema
CREATE SCHEMA IF NOT EXISTS iam;
CREATE SCHEMA IF NOT EXISTS sys;

-- 3) 应用账号（请替换密码！）
-- IAM 后端：读写 iam schema，并向 sys.sys_operation_log 写日志
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'iam_app') THEN
        CREATE ROLE iam_app LOGIN PASSWORD 'ChangeMe_IamApp_2026';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sys_app') THEN
        CREATE ROLE sys_app LOGIN PASSWORD 'ChangeMe_SysApp_2026';
    END IF;
END
$$;

-- 连接默认 schema（应用 JDBC URL 中也显式带 currentSchema）
ALTER ROLE iam_app SET search_path TO iam;
ALTER ROLE sys_app SET search_path TO sys;

-- 4) 表级授权在各自 schema 脚本末尾完成（GRANT 需要表已创建）：
--    02-iam-schema.sql : sys_app 对 iam 认证相关表的只读权限
--    SYS 01-sys-schema.sql : iam_app 对 sys.sys_operation_log 的写入权限
