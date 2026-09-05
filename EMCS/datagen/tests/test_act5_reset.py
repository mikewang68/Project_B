"""第五幕 disposable DB、reset 与菜单权限回归测试（REQ-051～062/076）。"""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import unittest
from decimal import Decimal

import pymysql


REPO_ROOT = Path(__file__).resolve().parents[2]
VALID_TEST_DB = re.compile(r"codex_[a-z0-9_]*_test")
RESULT_TABLES = (
    "e_cost_record",
    "e_cost_recompute_record",
    "e_report_archive",
)
EXPECTED_ROLE_MENUS = {
    # 2015 = AI 巡检（REQ-AGENT-TBD），K.6 窄授权同报表页——仅 admin/energy_mgr 可见
    1: set(range(2000, 2016)),
    3: {1, 100, 1000, *range(2000, 2016)},
    4: {1, 100, 1000, *range(2000, 2012)},
    5: {2000, 2001, 2002, 2005},
    6: {2000, 2001, 2012, 2013, 2014},
}


def generator_env(**overrides: str) -> dict[str, str]:
    environment = dict(os.environ)
    environment.pop("ACT4_TEST_DB", None)
    environment.pop("ACT5_TEST_DB", None)
    environment.update(overrides)
    backend_path = str(REPO_ROOT / "backend")
    environment["PYTHONPATH"] = os.pathsep.join(
        filter(None, (backend_path, environment.get("PYTHONPATH")))
    )
    return environment


def import_generator(**environment: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            "-c",
            "from datagen.generate_demo_data import DB_CONF; "
            "print(DB_CONF['database'])",
        ],
        cwd=REPO_ROOT,
        env=generator_env(**environment),
        text=True,
        capture_output=True,
        timeout=30,
        check=False,
    )


class Act5DatabaseEnvironmentTest(unittest.TestCase):
    def test_act5_database_is_selected_when_set_alone(self) -> None:
        result = import_generator(ACT5_TEST_DB="codex_act5_reset_test")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "codex_act5_reset_test")

    def test_matching_act4_and_act5_databases_are_accepted(self) -> None:
        result = import_generator(
            ACT4_TEST_DB="codex_shared_reset_test",
            ACT5_TEST_DB="codex_shared_reset_test",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "codex_shared_reset_test")

    def test_conflicting_act4_and_act5_databases_fail_at_import(self) -> None:
        result = import_generator(
            ACT4_TEST_DB="codex_act4_reset_test",
            ACT5_TEST_DB="codex_act5_reset_test",
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(
            "ACT5_TEST_DB and ACT4_TEST_DB must match when both are set",
            result.stderr,
        )

    def test_each_test_database_variable_rejects_non_disposable_names(self) -> None:
        for variable in ("ACT4_TEST_DB", "ACT5_TEST_DB"):
            with self.subTest(variable=variable):
                result = import_generator(**{variable: "b_demo"})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(
                    "test database must match codex_*_test",
                    result.stderr,
                )


class Act5FreshResetIntegrationTest(unittest.TestCase):
    test_db = ""

    @classmethod
    def setUpClass(cls) -> None:
        cls.test_db = os.environ.get("ACT5_TEST_DB", "")
        if not cls.test_db:
            raise unittest.SkipTest("ACT5_TEST_DB is required for fresh reset integration")
        if VALID_TEST_DB.fullmatch(cls.test_db) is None:
            raise AssertionError("ACT5_TEST_DB must be a disposable codex_*_test DB")

        selected = import_generator(ACT5_TEST_DB=cls.test_db)
        if selected.returncode != 0:
            raise AssertionError(selected.stderr)
        if selected.stdout.strip() != cls.test_db:
            raise AssertionError(
                f"generator selected {selected.stdout.strip()!r}, expected {cls.test_db!r}"
            )

        subprocess.run(
            [sys.executable, "datagen/generate_demo_data.py", "--reset"],
            cwd=REPO_ROOT,
            env=generator_env(ACT5_TEST_DB=cls.test_db),
            check=True,
            timeout=180,
        )

    def connect(self):
        return pymysql.connect(
            host="127.0.0.1",
            port=3306,
            user="demo",
            password="bdemo_dev",
            database=self.test_db,
            charset="utf8mb4",
        )

    def test_reset_leaves_result_tables_and_runtime_audits_empty(self) -> None:
        with self.connect() as conn, conn.cursor() as cur:
            for table in (*RESULT_TABLES, "e_alert_event", "e_audit_security"):
                with self.subTest(table=table):
                    cur.execute(f"SELECT COUNT(*) FROM {table}")
                    self.assertEqual(cur.fetchone()[0], 0)

    def test_reset_preserves_deterministic_tariff_and_allocation_inputs(self) -> None:
        with self.connect() as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT energy_type_code, tou_period, price, version_no "
                "FROM e_tariff_version ORDER BY tariff_id"
            )
            self.assertEqual(
                cur.fetchall(),
                (
                    ("electricity", "peak", Decimal("1.2000"), 1),
                    ("electricity", "flat", Decimal("0.7500"), 1),
                    ("electricity", "valley", Decimal("0.4000"), 1),
                    ("water", "flat_only", Decimal("4.5000"), 1),
                    ("compressed_air", "flat_only", Decimal("0.1200"), 1),
                ),
            )
            cur.execute(
                "SELECT rule_name, scope, method, config_json, effective_from, version_no "
                "FROM e_cost_alloc_rule ORDER BY rule_id"
            )
            rows = cur.fetchall()
            self.assertEqual(len(rows), 1)
            rule_name, scope, method, config_json, effective_from, version_no = rows[0]
            self.assertEqual(
                (rule_name, scope, method, effective_from.isoformat(), version_no),
                ("按额定功率权重分摊", "area", "rated_power_weight", "2026-05-04", 1),
            )
            self.assertEqual(json.loads(config_json), {"basis": "equipment.rated_power_kw"})

    def test_cost_menu_branch_is_visible_without_expanding_role_menus(self) -> None:
        with self.connect() as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT menu_id, visible, status, remark FROM sys_menu "
                "WHERE menu_id IN (2012,2013,2014) ORDER BY menu_id"
            )
            menus = cur.fetchall()
            self.assertEqual([row[:3] for row in menus], [
                (2012, "0", "0"),
                (2013, "0", "0"),
                (2014, "0", "0"),
            ])
            for _, _, _, remark in menus:
                self.assertIn("第五幕已开发", remark)
                self.assertNotIn("隐藏", remark)
                self.assertNotIn("未开发", remark)

            cur.execute(
                "SELECT role_id, menu_id FROM sys_role_menu "
                "WHERE role_id IN (1,3,4,5,6) ORDER BY role_id, menu_id"
            )
            actual = {role_id: set() for role_id in EXPECTED_ROLE_MENUS}
            for role_id, menu_id in cur.fetchall():
                actual[role_id].add(menu_id)
            self.assertEqual(actual, EXPECTED_ROLE_MENUS)
            self.assertTrue(actual[4].isdisjoint({2012, 2013, 2014}))
            self.assertTrue(actual[5].isdisjoint({2012, 2013, 2014}))
            # AI 巡检（2015）K.6 窄授权同报表页：ops/dispatch/finance 均不可见
            for narrow_role in (4, 5, 6):
                self.assertNotIn(2015, actual[narrow_role])


if __name__ == "__main__":
    unittest.main()
