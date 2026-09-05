"""第五幕成本版本影子迁移回归测试（REQ-051～062/073/074）。"""

from __future__ import annotations

from decimal import Decimal
import os
from pathlib import Path
import re
import unittest
from uuid import uuid4

import pymysql


REPO_ROOT = Path(__file__).resolve().parents[2]
DATAGEN_DIR = REPO_ROOT / "datagen"
V001_PATH = DATAGEN_DIR / "ddl" / "V001__energy_domain.sql"
V002_PATH = DATAGEN_DIR / "ddl" / "V002__act5_cost_versioning.sql"
LEGACY_FIXTURE_PATH = (
    DATAGEN_DIR / "tests" / "fixtures" / "pre_act5_cost_record.sql"
)
TARGET_TABLES = (
    "e_cost_record_act5",
    "e_cost_recompute_record",
    "e_report_archive",
)
LEGACY_ANCHOR_EVIDENCE = (
    ("2026-05", Decimal("138014.5000"), "LEGACY-COST-2026-05-SIGNATURE"),
    ("2026-06", Decimal("161920.0300"), "LEGACY-COST-2026-06-SIGNATURE"),
    ("2026-07", Decimal("59501.9900"), "LEGACY-COST-2026-07-SIGNATURE"),
)
LEGACY_SYSTEM_ELECTRICITY_EVIDENCE = tuple(
    ("system", None, stat_month, "electricity", total_cost, signature)
    for stat_month, total_cost, signature in LEGACY_ANCHOR_EVIDENCE
)
V001_TABLE_BY_TARGET = {
    "e_cost_record_act5": "e_cost_record",
    "e_cost_recompute_record": "e_cost_recompute_record",
    "e_report_archive": "e_report_archive",
}
REFERENCE_TABLE_BY_TARGET = {
    target: f"{target}_reference" for target in TARGET_TABLES
}
LEGACY_ROW_QUERY = """
select
  id, object_type, object_id, stat_month, energy_type_code,
  usage_qty, peak_qty, flat_qty, valley_qty,
  peak_cost, flat_cost, valley_cost, total_cost,
  tariff_version_no, alloc_rule_version_no, status, signature,
  computed_at, frozen_at
from `{table_name}`
order by id
"""
LEGACY_ANCHOR_INSERT = """
insert into e_cost_record (
  object_type, object_id, stat_month, energy_type_code,
  usage_qty, peak_qty, flat_qty, valley_qty,
  peak_cost, flat_cost, valley_cost, total_cost,
  tariff_version_no, alloc_rule_version_no, status, signature,
  computed_at, frozen_at
) values (
  'system', null, %s, 'electricity',
  0, 0, 0, 0,
  0, %s, 0, %s,
  'ELEC-v1', 'ALLOC-v1', 'reviewed', %s,
  '2026-07-12 23:59:00', null
)
"""


def _without_line_comments(sql: str) -> str:
    return "\n".join(
        line for line in sql.splitlines() if not line.lstrip().startswith(("--", "#"))
    )


def _split_sql_statements(sql: str) -> list[str]:
    source = _without_line_comments(sql)
    return [statement.strip() for statement in source.split(";") if statement.strip()]


def _extract_create_table(sql: str, table_name: str) -> str:
    match = re.search(
        rf"create\s+table\s+{re.escape(table_name)}\s*\(.*?\)\s*"
        rf"engine\s*=\s*innodb[^;]*;",
        sql,
        re.IGNORECASE | re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"missing CREATE TABLE for {table_name}")
    return match.group(0)


def _normalized_show_create(sql: str) -> str:
    normalized = re.sub(
        r"^CREATE TABLE `[^`]+`",
        "CREATE TABLE `<table>`",
        sql,
        count=1,
        flags=re.IGNORECASE,
    )
    return re.sub(r"AUTO_INCREMENT=\d+\s+", "", normalized)


def _legacy_rows(cursor, table_name: str) -> tuple:
    if not re.fullmatch(r"e_cost_record(?:_pre_act5)?", table_name):
        raise AssertionError(f"unexpected legacy table name: {table_name}")
    cursor.execute(LEGACY_ROW_QUERY.format(table_name=table_name))
    return cursor.fetchall()


def _legacy_anchor_evidence(rows: tuple) -> tuple:
    return tuple(
        (row[1], row[2], row[3], row[4], row[12], row[16]) for row in rows
    )


def _admin_connection(database: str | None = None):
    config = {
        "host": os.environ.get("DB_HOST", "127.0.0.1"),
        "port": int(os.environ.get("DB_PORT", "3306")),
        "user": os.environ.get("MYSQL_ROOT_USER", "root"),
        "password": os.environ.get("MYSQL_ROOT_PASSWORD", "bdemo_root"),
        "charset": "utf8mb4",
        "autocommit": True,
    }
    if database is not None:
        config["database"] = database
    return pymysql.connect(**config)


def _migration_source(test_case: unittest.TestCase) -> str:
    test_case.assertTrue(
        V002_PATH.exists(),
        f"missing migration: {V002_PATH.relative_to(REPO_ROOT)}",
    )
    return V002_PATH.read_text(encoding="utf-8")


class Act5MigrationSourceTest(unittest.TestCase):
    def test_v002_contains_exactly_three_target_create_statements(self) -> None:
        statements = _split_sql_statements(_migration_source(self))
        self.assertEqual(len(statements), 3)
        actual_tables = []
        for statement in statements:
            match = re.match(
                r"create\s+table\s+([a-z0-9_]+)\s*\(",
                statement,
                re.IGNORECASE,
            )
            self.assertIsNotNone(match, statement[:120])
            actual_tables.append(match.group(1).lower())
        self.assertEqual(tuple(actual_tables), TARGET_TABLES)

    def test_v002_rejects_destructive_dml_reuse_and_fake_snapshots(self) -> None:
        source = _without_line_comments(_migration_source(self))
        self.assertNotRegex(
            source,
            r"(?i)\b(?:drop|alter|rename|insert|update|delete|select)\b",
        )
        self.assertNotRegex(source, r"(?i)\bif\s+not\s+exists\b")
        self.assertNotRegex(source, r"(?i)\bjson_(?:object|array)\s*\(")
        self.assertNotRegex(source, r"(?s)'\s*[\{\[]")

    def test_all_three_target_definitions_match_new_v001_definitions(self) -> None:
        migration = _migration_source(self)
        v001 = V001_PATH.read_text(encoding="utf-8")
        for target_table, v001_table in V001_TABLE_BY_TARGET.items():
            with self.subTest(target_table=target_table):
                expected = _extract_create_table(v001, v001_table)
                actual = _extract_create_table(migration, target_table)
                expected = re.sub(
                    rf"(?i)^create\s+table\s+{re.escape(v001_table)}\b",
                    "create table <target>",
                    expected,
                )
                actual = re.sub(
                    rf"(?i)^create\s+table\s+{re.escape(target_table)}\b",
                    "create table <target>",
                    actual,
                )
                self.assertEqual(actual, expected)


class Act5MigrationIntegrationTest(unittest.TestCase):
    database = ""
    legacy_rows_before: tuple = ()
    legacy_ddl_before = ""

    @classmethod
    def setUpClass(cls) -> None:
        if not V002_PATH.exists():
            raise unittest.SkipTest("V002 is intentionally absent during the TDD red run")
        cls.database = f"codex_act5_migration_{uuid4().hex[:10]}_test"
        if not re.fullmatch(r"codex_[a-z0-9_]+_test", cls.database):
            raise AssertionError("migration test database name is not disposable")
        with _admin_connection() as conn, conn.cursor() as cursor:
            cursor.execute(
                f"CREATE DATABASE `{cls.database}` "
                "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
            )
        try:
            with _admin_connection(cls.database) as conn, conn.cursor() as cursor:
                legacy_fixture = LEGACY_FIXTURE_PATH.read_text(encoding="utf-8")
                cursor.execute(_extract_create_table(legacy_fixture, "e_cost_record"))
                cursor.executemany(
                    LEGACY_ANCHOR_INSERT,
                    tuple(
                        (stat_month, total_cost, total_cost, signature)
                        for stat_month, total_cost, signature in LEGACY_ANCHOR_EVIDENCE
                    ),
                )
                cls.legacy_rows_before = _legacy_rows(cursor, "e_cost_record")
                cursor.execute("SHOW CREATE TABLE e_cost_record")
                cls.legacy_ddl_before = cursor.fetchone()[1]

                v001 = V001_PATH.read_text(encoding="utf-8")
                for target_table, v001_table in V001_TABLE_BY_TARGET.items():
                    reference_table = REFERENCE_TABLE_BY_TARGET[target_table]
                    v001_ddl = _extract_create_table(v001, v001_table)
                    reference_ddl = re.sub(
                        rf"(?i)^create\s+table\s+{re.escape(v001_table)}\b",
                        f"create table {reference_table}",
                        v001_ddl,
                        count=1,
                    )
                    cursor.execute(reference_ddl)
                for statement in _split_sql_statements(
                    V002_PATH.read_text(encoding="utf-8")
                ):
                    cursor.execute(statement)
        except Exception:
            cls._drop_database()
            raise

    @classmethod
    def tearDownClass(cls) -> None:
        cls._drop_database()

    @classmethod
    def _drop_database(cls) -> None:
        if not cls.database:
            return
        with _admin_connection() as conn, conn.cursor() as cursor:
            cursor.execute(f"DROP DATABASE IF EXISTS `{cls.database}`")

    def test_atomic_cutover_and_failed_gate_rollback_preserve_legacy_evidence(
        self,
    ) -> None:
        self.assertEqual(len(self.legacy_rows_before), 3)
        self.assertEqual(
            _legacy_anchor_evidence(self.legacy_rows_before),
            LEGACY_SYSTEM_ELECTRICITY_EVIDENCE,
        )
        with _admin_connection(self.database) as conn, conn.cursor() as cursor:
            self.assertEqual(
                _legacy_rows(cursor, "e_cost_record"), self.legacy_rows_before
            )
            cursor.execute("SHOW CREATE TABLE e_cost_record")
            self.assertEqual(cursor.fetchone()[1], self.legacy_ddl_before)

            for target_table in TARGET_TABLES:
                with self.subTest(target_table=target_table):
                    cursor.execute(f"SHOW CREATE TABLE `{target_table}`")
                    target_ddl = _normalized_show_create(cursor.fetchone()[1])
                    reference_table = REFERENCE_TABLE_BY_TARGET[target_table]
                    cursor.execute(f"SHOW CREATE TABLE `{reference_table}`")
                    reference_ddl = _normalized_show_create(cursor.fetchone()[1])
                    self.assertEqual(target_ddl, reference_ddl)

            for table_name in TARGET_TABLES:
                cursor.execute(f"SELECT COUNT(*) FROM `{table_name}`")
                self.assertEqual(cursor.fetchone()[0], 0, table_name)

            statements = _split_sql_statements(V002_PATH.read_text(encoding="utf-8"))
            with self.assertRaisesRegex(pymysql.OperationalError, "already exists"):
                for statement in statements:
                    cursor.execute(statement)

            cursor.execute(
                "RENAME TABLE "
                "e_cost_record TO e_cost_record_pre_act5, "
                "e_cost_record_act5 TO e_cost_record"
            )

            cursor.execute("SELECT COUNT(*) FROM e_cost_record")
            self.assertEqual(cursor.fetchone()[0], 0)
            cursor.execute("SHOW CREATE TABLE e_cost_record")
            current_ddl = _normalized_show_create(cursor.fetchone()[1])
            cursor.execute("SHOW CREATE TABLE e_cost_record_act5_reference")
            reference_ddl = _normalized_show_create(cursor.fetchone()[1])
            self.assertEqual(current_ddl, reference_ddl)

            self.assertEqual(
                _legacy_rows(cursor, "e_cost_record_pre_act5"),
                self.legacy_rows_before,
            )
            self.assertEqual(
                _legacy_anchor_evidence(
                    _legacy_rows(cursor, "e_cost_record_pre_act5")
                ),
                LEGACY_SYSTEM_ELECTRICITY_EVIDENCE,
            )
            cursor.execute("SHOW CREATE TABLE e_cost_record_pre_act5")
            self.assertEqual(
                _normalized_show_create(cursor.fetchone()[1]),
                _normalized_show_create(self.legacy_ddl_before),
            )
            for table_name in ("e_cost_recompute_record", "e_report_archive"):
                cursor.execute(f"SELECT COUNT(*) FROM `{table_name}`")
                self.assertEqual(cursor.fetchone()[0], 0, table_name)

            cursor.execute(
                "RENAME TABLE "
                "e_cost_record TO e_cost_record_act5_failed, "
                "e_cost_record_pre_act5 TO e_cost_record"
            )

            self.assertEqual(
                _legacy_rows(cursor, "e_cost_record"), self.legacy_rows_before
            )
            self.assertEqual(
                _legacy_anchor_evidence(_legacy_rows(cursor, "e_cost_record")),
                LEGACY_SYSTEM_ELECTRICITY_EVIDENCE,
            )
            cursor.execute("SHOW CREATE TABLE e_cost_record")
            self.assertEqual(cursor.fetchone()[1], self.legacy_ddl_before)
            cursor.execute("SELECT COUNT(*) FROM e_cost_record_act5_failed")
            self.assertEqual(cursor.fetchone()[0], 0)
            cursor.execute("SHOW CREATE TABLE e_cost_record_act5_failed")
            failed_ddl = _normalized_show_create(cursor.fetchone()[1])
            self.assertEqual(failed_ddl, reference_ddl)
            cursor.execute(
                "SELECT table_name FROM information_schema.tables "
                "WHERE table_schema=%s AND table_name IN "
                "('e_cost_record', 'e_cost_record_pre_act5', "
                "'e_cost_record_act5_failed') ORDER BY table_name",
                (self.database,),
            )
            self.assertEqual(
                tuple(row[0] for row in cursor.fetchall()),
                ("e_cost_record", "e_cost_record_act5_failed"),
            )
            for table_name in ("e_cost_recompute_record", "e_report_archive"):
                cursor.execute(f"SELECT COUNT(*) FROM `{table_name}`")
                self.assertEqual(cursor.fetchone()[0], 0, table_name)


if __name__ == "__main__":
    unittest.main()
