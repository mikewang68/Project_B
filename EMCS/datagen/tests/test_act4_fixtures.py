"""第四幕建议历史 fixture 隔离库集成测试（REQ-045～050）。"""

from __future__ import annotations

import copy
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import unittest

import pymysql

from datagen import generate_demo_data as generator
from module_energy.domain.suggestion_calculation import (
    VALID_QUALITY_CODES,
    build_calculation_signature,
    calculate_daily_metric_series,
    calculate_history_verification,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
EXPECTED_STATUS_COUNTS = {
    "pending": 1,
    "dispatched": 1,
    "executing": 1,
    "verifying": 1,
    "valid_closed": 1,
    "invalid_closed": 2,
    "deferred": 1,
}
HISTORY_CASES = {
    "H4": {
        "title": "B 区筒仓风机运行效率验证",
        "point_code": "SF-B1-E",
        "area_code": "AREA-B",
        "anchors": ("9.61", "15.52", "2.38"),
        "status": "effective",
    },
    "H5": {
        "title": "B 区空压机 2 号运行电耗优化复盘",
        "point_code": "AC-B2-E",
        "area_code": "AREA-B",
        "anchors": ("8.50", "14.78", "1.18"),
        "status": "effective",
    },
    "H6": {
        "title": "A 区皮带输送机人工巡检效果复盘",
        "point_code": "BC-A1-E",
        "area_code": "AREA-A",
        "anchors": ("-4.76", "-5.29", "-14.04"),
        "status": "ineffective",
    },
}
HISTORY_PRIORITIES = {
    "A 区龙门吊 2 号待机用能复核": (58.00, "medium"),
    "A 区转运平车班后用能巡检": (60.75, "medium"),
    "A 区除尘风机运行效率巡检": (70.50, "medium"),
    "B 区筒仓风机运行效率验证": (71.50, "medium"),
    "B 区空压机 2 号运行电耗优化复盘": (78.00, "high"),
    "A 区皮带输送机人工巡检效果复盘": (60.35, "medium"),
    "B 区气力输送系统重复建议归档": (42.25, "low"),
    "A 区冲洗水泵维护窗口协调延期": (66.50, "medium"),
}
HISTORY_PATHS = {
    "A 区龙门吊 2 号待机用能复核": ["pending"],
    "A 区转运平车班后用能巡检": ["pending", "dispatched"],
    "A 区除尘风机运行效率巡检": [
        "pending",
        "dispatched",
        "executing",
    ],
    "B 区筒仓风机运行效率验证": [
        "pending",
        "dispatched",
        "executing",
        "verifying",
    ],
    "B 区空压机 2 号运行电耗优化复盘": [
        "pending",
        "dispatched",
        "executing",
        "verifying",
        "valid_closed",
    ],
    "A 区皮带输送机人工巡检效果复盘": [
        "pending",
        "dispatched",
        "executing",
        "verifying",
        "invalid_closed",
    ],
    "B 区气力输送系统重复建议归档": ["pending", "invalid_closed"],
    "A 区冲洗水泵维护窗口协调延期": [
        "pending",
        "dispatched",
        "deferred",
    ],
}
FORBIDDEN_CONTROL_KEYS = {
    "controlinstruction",
    "devicecommand",
    "startstopcommand",
    "poweradjustment",
    "remotevalvecommand",
}
FORBIDDEN_CONTROL_TEXT = ("设备启停", "远程关阀", "功率调节", "自动控制", "指令下发")
MANUAL_SNAPSHOT_FIELDS = {
    "sourceType",
    "sourceLabel",
    "templateCode",
    "templateName",
    "category",
    "sourceRuleCode",
    "applicableObjectType",
    "actionContent",
    "requiredData",
    "estimatedSaving",
    "costImpact",
    "reliabilityImpact",
    "verificationMethod",
    "defaultImplementationDifficulty",
    "defaultSafetyImpact",
    "enabled",
    "version",
}


def _connect(database: str | None = None, *, autocommit: bool = False):
    config = dict(generator.DB_CONF)
    if database is None:
        config.pop("database", None)
    else:
        config["database"] = database
    return pymysql.connect(**config, autocommit=autocommit)


def _suggestion_domain_counts(database: str) -> tuple[int | None, ...]:
    tables = (
        "e_suggestion_template",
        "e_suggestion",
        "e_suggestion_flow_log",
        "e_suggestion_verification",
    )
    with _connect(database) as conn, conn.cursor() as cur:
        counts: list[int | None] = []
        for table in tables:
            cur.execute(
                "SELECT COUNT(*) FROM information_schema.tables "
                "WHERE table_schema=%s AND table_name=%s",
                (database, table),
            )
            if cur.fetchone()[0] == 0:
                counts.append(None)
            else:
                cur.execute(f"SELECT COUNT(*) FROM {table}")
                counts.append(cur.fetchone()[0])
        return tuple(counts)


def _should_drop_fixture_database(
    act4_database: str, act5_database: str | None
) -> bool:
    return not act5_database or act5_database != act4_database


class Act4FixtureDatabaseLifecycleTest(unittest.TestCase):
    def test_act4_database_is_dropped_without_a_shared_act5_fixture(self) -> None:
        self.assertTrue(
            _should_drop_fixture_database("codex_act4_fixture_test", "")
        )
        self.assertTrue(
            _should_drop_fixture_database(
                "codex_act4_fixture_test", "codex_act5_fixture_test"
            )
        )

    def test_act4_database_is_retained_when_act5_reuses_the_same_fixture(self) -> None:
        self.assertFalse(
            _should_drop_fixture_database(
                "codex_shared_fixture_test", "codex_shared_fixture_test"
            )
        )


def _assert_no_control_surface(test_case: unittest.TestCase, value) -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            normalized = "".join(character for character in key.lower() if character.isalnum())
            test_case.assertNotIn(normalized, FORBIDDEN_CONTROL_KEYS)
            _assert_no_control_surface(test_case, item)
        return
    if isinstance(value, list):
        for item in value:
            _assert_no_control_surface(test_case, item)
        return
    if isinstance(value, str):
        for forbidden in FORBIDDEN_CONTROL_TEXT:
            test_case.assertNotIn(forbidden, value)


def _history_signature_from_comparisons(
    *,
    verification_input,
    comparisons: tuple[dict, dict, dict, dict],
    saving_value,
    saving_unit: str,
) -> str:
    """Rebuild the signed scope while deliberately excluding display labels."""
    signed_comparisons = copy.deepcopy(comparisons)
    for comparison in signed_comparisons:
        comparison["series"].pop("labels", None)
    usage, cost, workload, quality = signed_comparisons
    return build_calculation_signature(
        {
            "formulaVersion": verification_input.formula_version,
            "pointCode": verification_input.baseline.point_code,
            "energyType": verification_input.baseline.energy_type,
            "baselineWindow": {
                "start": verification_input.baseline.start,
                "end": verification_input.baseline.end,
            },
            "reportWindow": {
                "start": verification_input.report.start,
                "end": verification_input.report.end,
            },
            "validQualityCodes": sorted(VALID_QUALITY_CODES),
            "tariffVersions": sorted(
                set(cost["baseline"]["tariffVersions"])
                | set(cost["report"]["tariffVersions"])
            ),
            "usageComparison": usage,
            "costComparison": cost,
            "workloadComparison": workload,
            "qualityComparison": quality,
            "savingValue": saving_value,
            "savingUnit": saving_unit,
        }
    )


class Act4FixtureIntegrationTest(unittest.TestCase):
    generated = False
    test_db = ""
    shared_before: tuple[int | None, ...] | None = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.test_db = os.environ.get("ACT4_TEST_DB", "")
        if not cls.test_db:
            raise unittest.SkipTest("ACT4_TEST_DB is required for fixture integration")
        if not re.fullmatch(r"codex_[a-z0-9_]*_test", cls.test_db):
            raise AssertionError("ACT4_TEST_DB must be a disposable codex_*_test DB")
        cls.shared_before = _suggestion_domain_counts("b_demo")

    @classmethod
    def tearDownClass(cls) -> None:
        if not cls.test_db:
            return
        mismatch: AssertionError | None = None
        try:
            shared_after = _suggestion_domain_counts("b_demo")
            if cls.shared_before != shared_after:
                mismatch = AssertionError(
                    f"shared b_demo changed: {cls.shared_before} -> {shared_after}"
                )
        finally:
            if _should_drop_fixture_database(
                cls.test_db, os.environ.get("ACT5_TEST_DB")
            ):
                with _connect(autocommit=True) as conn, conn.cursor() as cur:
                    cur.execute("SHOW DATABASES LIKE %s", (cls.test_db,))
                    if cur.fetchone() is not None:
                        cur.execute(f"DROP DATABASE IF EXISTS `{cls.test_db}`")
        if mismatch is not None:
            raise mismatch

    def _ensure_generated(self) -> None:
        self.assertEqual(
            generator.DB_CONF["database"],
            self.test_db,
            "generator must honor ACT4_TEST_DB before any reset runs",
        )
        if self.__class__.generated:
            return
        environment = dict(os.environ)
        backend_path = str(REPO_ROOT / "backend")
        environment["PYTHONPATH"] = os.pathsep.join(
            filter(None, (backend_path, environment.get("PYTHONPATH")))
        )
        subprocess.run(
            [sys.executable, "datagen/generate_demo_data.py", "--reset"],
            cwd=REPO_ROOT,
            env=environment,
            check=True,
            timeout=180,
        )
        self.__class__.generated = True

    def test_generator_is_bound_to_disposable_database(self) -> None:
        self.assertEqual(generator.DB_CONF["database"], self.test_db)
        self.assertNotEqual(generator.DB_CONF["database"], "b_demo")

    def test_default_template_and_history_counts(self) -> None:
        self._ensure_generated()
        with _connect(self.test_db) as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT template_code, source_rule_code, "
                "applicable_object_type, default_implementation_difficulty, "
                "default_safety_impact, enabled, version "
                "FROM e_suggestion_template"
            )
            self.assertEqual(
                cur.fetchall(),
                (("TPL-R06-AIR-LEAK-DEFAULT", "R06", "area", 35, 70, 1, 1),),
            )
            for table, expected in (
                ("e_suggestion", 8),
                ("e_suggestion_flow_log", 25),
                ("e_suggestion_verification", 3),
            ):
                cur.execute(f"SELECT COUNT(*) FROM {table}")
                self.assertEqual(cur.fetchone()[0], expected, table)
            cur.execute(
                "SELECT source_type, COUNT(*) FROM e_suggestion GROUP BY source_type"
            )
            self.assertEqual(cur.fetchall(), (("manual", 8),))
            cur.execute(
                "SELECT status, COUNT(*) FROM e_suggestion GROUP BY status"
            )
            self.assertEqual(dict(cur.fetchall()), EXPECTED_STATUS_COUNTS)
            cur.execute(
                "SELECT title, priority_score, priority_band FROM e_suggestion "
                "ORDER BY suggestion_id"
            )
            priorities = {
                title: (float(score), band) for title, score, band in cur.fetchall()
            }
            self.assertEqual(priorities, HISTORY_PRIORITIES)
            cur.execute(
                "SELECT COUNT(*) FROM e_suggestion WHERE responsible_user='ops_user' "
                "AND responsible_role='ops'"
            )
            self.assertEqual(cur.fetchone()[0], 6)
            cur.execute(
                "SELECT COUNT(*) FROM e_suggestion_flow_log "
                "WHERE (operator='energy_mgr' AND operator_role <> 'energy_mgr') "
                "OR (operator='ops_user' AND operator_role <> 'ops')"
            )
            self.assertEqual(cur.fetchone()[0], 0)
            cur.execute(
                "SELECT close_type, COUNT(*) FROM e_suggestion "
                "WHERE close_type IS NOT NULL GROUP BY close_type"
            )
            self.assertEqual(
                dict(cur.fetchall()), {"implemented": 2, "archived_invalid": 1}
            )

    def test_suggestion_menu_is_visible_to_manager_and_operations_roles(self) -> None:
        self._ensure_generated()
        with _connect(self.test_db) as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT visible, status, perms FROM sys_menu WHERE menu_id=2011"
            )
            self.assertEqual(
                cur.fetchone(), ("0", "0", "energy:alert:suggestion")
            )
            cur.execute(
                "SELECT role_id FROM sys_role_menu WHERE menu_id=2011 "
                "AND role_id IN (3,4) ORDER BY role_id"
            )
            self.assertEqual(cur.fetchall(), ((3,), (4,)))

    def test_history_has_no_engine_or_r08_linkage(self) -> None:
        self._ensure_generated()
        with _connect(self.test_db) as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT COUNT(*) FROM e_suggestion WHERE source_alert_id IS NOT NULL "
                "OR source_fingerprint IS NOT NULL OR rule_code IS NOT NULL"
            )
            self.assertEqual(cur.fetchone()[0], 0)
            cur.execute(
                "SELECT COUNT(*) FROM e_suggestion WHERE object_type <> 'equipment' "
                "OR object_id IS NULL"
            )
            self.assertEqual(cur.fetchone()[0], 0)
            cur.execute(
                "SELECT COUNT(*) FROM e_suggestion_template "
                "WHERE source_rule_code='R08'"
            )
            self.assertEqual(cur.fetchone()[0], 0)
            cur.execute(
                "SELECT COUNT(*) FROM e_suggestion WHERE rule_code IN ('R06','R08')"
            )
            self.assertEqual(cur.fetchone()[0], 0)
            cur.execute(
                "SELECT action_content, required_data, estimated_saving, cost_impact, "
                "reliability_impact, verification_method FROM e_suggestion_template"
            )
            for row in cur.fetchall():
                for value in row:
                    _assert_no_control_surface(self, value)
            cur.execute(
                "SELECT measure_content, template_snapshot_json, priority_factors_json, "
                "attachments_json FROM e_suggestion ORDER BY suggestion_id"
            )
            for measure, template_json, priority_json, attachments_json in cur.fetchall():
                _assert_no_control_surface(self, measure)
                template_snapshot = json.loads(template_json)
                self.assertEqual(set(template_snapshot), MANUAL_SNAPSHOT_FIELDS)
                self.assertIsNone(template_snapshot["templateCode"])
                self.assertIsNone(template_snapshot["sourceRuleCode"])
                _assert_no_control_surface(self, template_snapshot)
                _assert_no_control_surface(self, json.loads(priority_json))
                if attachments_json:
                    _assert_no_control_surface(self, json.loads(attachments_json))
            cur.execute(
                "SELECT payload_snapshot_json FROM e_suggestion_flow_log ORDER BY flow_id"
            )
            for (payload_json,) in cur.fetchall():
                _assert_no_control_surface(self, json.loads(payload_json))

    def test_flow_order_and_placeholder_attachments_are_deterministic(self) -> None:
        self._ensure_generated()
        with _connect(self.test_db) as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT suggestion_id, MIN(flow_id), MAX(flow_id), COUNT(*) "
                "FROM e_suggestion_flow_log GROUP BY suggestion_id "
                "ORDER BY MIN(flow_id)"
            )
            groups = cur.fetchall()
            self.assertEqual(sum(row[3] for row in groups), 25)
            flattened = []
            for suggestion_id, _minimum, _maximum, _count in groups:
                cur.execute(
                    "SELECT flow_id FROM e_suggestion_flow_log "
                    "WHERE suggestion_id=%s ORDER BY flow_id ASC",
                    (suggestion_id,),
                )
                flattened.extend(flow_id for (flow_id,) in cur.fetchall())
            self.assertEqual(flattened, sorted(flattened))
            for title, expected_path in HISTORY_PATHS.items():
                cur.execute(
                    "SELECT f.to_status FROM e_suggestion s "
                    "JOIN e_suggestion_flow_log f ON f.suggestion_id=s.suggestion_id "
                    "WHERE s.title=%s ORDER BY f.flow_id",
                    (title,),
                )
                self.assertEqual(
                    [to_status for (to_status,) in cur.fetchall()], expected_path
                )
            cur.execute(
                "SELECT COUNT(*) FROM e_suggestion_flow_log "
                "WHERE to_status='executing' "
                "AND JSON_EXTRACT(payload_snapshot_json,'$.activity.type')="
                "'manual_inspection'"
            )
            self.assertEqual(cur.fetchone()[0], 4)

            cur.execute(
                "SELECT title, attachments_json FROM e_suggestion "
                "WHERE title IN (%s,%s) ORDER BY title",
                (HISTORY_CASES["H5"]["title"], HISTORY_CASES["H6"]["title"]),
            )
            attachments = cur.fetchall()
            self.assertEqual(len(attachments), 2)
            for _title, payload in attachments:
                metadata = json.loads(payload)
                self.assertEqual(len(metadata), 1)
                self.assertTrue(metadata[0]["name"].startswith("demo-placeholder-"))
                self.assertTrue(metadata[0]["url"].startswith("/demo/placeholders/"))

    def test_verification_snapshots_follow_verifying_and_precede_close(self) -> None:
        self._ensure_generated()
        with _connect(self.test_db) as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT s.title, s.status, v.generated_at, "
                "(SELECT MAX(vf.occur_time) FROM e_suggestion_flow_log vf "
                " WHERE vf.suggestion_id=s.suggestion_id "
                " AND vf.to_status='verifying') AS verifying_at, "
                "s.closed_at, "
                "(SELECT MAX(cf.occur_time) FROM e_suggestion_flow_log cf "
                " WHERE cf.suggestion_id=s.suggestion_id "
                " AND cf.to_status IN ('valid_closed','invalid_closed')) AS close_at "
                "FROM e_suggestion s JOIN e_suggestion_verification v "
                "ON v.suggestion_id=s.suggestion_id ORDER BY s.suggestion_id, v.version"
            )
            rows = cur.fetchall()
            self.assertEqual(len(rows), 3)
            for title, status, generated_at, verifying_at, closed_at, close_at in rows:
                with self.subTest(title=title):
                    self.assertIsNotNone(verifying_at)
                    self.assertGreaterEqual(generated_at, verifying_at)
                    if status in {"valid_closed", "invalid_closed"}:
                        self.assertIsNotNone(closed_at)
                        self.assertIsNotNone(close_at)
                        self.assertGreaterEqual(closed_at, generated_at)
                        self.assertGreaterEqual(close_at, generated_at)

    def test_persisted_verifications_match_shared_formula_and_anchors(self) -> None:
        self._ensure_generated()
        with _connect(self.test_db) as conn, conn.cursor() as cur:
            for case_code, case in HISTORY_CASES.items():
                cur.execute(
                    "SELECT s.suggestion_id, v.status, v.repair_at, "
                    "v.baseline_start, v.baseline_end, v.report_start, v.report_end, "
                    "v.usage_comparison_json, v.cost_comparison_json, "
                    "v.workload_comparison_json, v.quality_comparison_json, "
                    "v.saving_value, v.saving_unit, v.saving_pct, "
                    "v.formula_version, v.signature "
                    "FROM e_suggestion s JOIN e_suggestion_verification v "
                    "ON v.suggestion_id=s.suggestion_id WHERE s.title=%s",
                    (case["title"],),
                )
                row = cur.fetchone()
                self.assertIsNotNone(row, case_code)
                (
                    _suggestion_id,
                    persisted_status,
                    _repair_at,
                    baseline_start,
                    baseline_end,
                    report_start,
                    report_end,
                    usage_json,
                    cost_json,
                    workload_json,
                    quality_json,
                    saving_value,
                    saving_unit,
                    saving_pct,
                    formula_version,
                    signature,
                ) = row
                verification_input = generator.load_verification_input(
                    conn,
                    point_code=case["point_code"],
                    area_code=case["area_code"],
                    baseline_start=baseline_start,
                    baseline_end=baseline_end,
                    report_start=report_start,
                    report_end=report_end,
                )
                result = calculate_history_verification(verification_input)
                daily_series = calculate_daily_metric_series(verification_input)
                comparisons = (
                    json.loads(usage_json),
                    json.loads(cost_json),
                    json.loads(workload_json),
                    json.loads(quality_json),
                )
                for dimension, metric_name, comparison in zip(
                    ("usage", "cost", "workload", "quality"),
                    ("usage_rate", "cost_rate", "workload", "coverage_pct"),
                    comparisons,
                    strict=True,
                ):
                    with self.subTest(case=case_code, dimension=dimension):
                        series = comparison["series"]
                        self.assertEqual(
                            set(series), {"labels", "baseline", "report"}
                        )
                        self.assertTrue(series["baseline"])
                        self.assertTrue(series["report"])
                        self.assertEqual(
                            len(series["labels"]),
                            max(len(series["baseline"]), len(series["report"])),
                        )
                        for period in ("baseline", "report"):
                            expected_points = getattr(daily_series, period)
                            actual_values = series[period]
                            self.assertEqual(len(actual_values), len(expected_points))
                            for actual, point in zip(
                                actual_values, expected_points, strict=True
                            ):
                                expected = getattr(point, metric_name)
                                if expected is None:
                                    self.assertIsNone(actual)
                                else:
                                    self.assertAlmostEqual(
                                        float(actual), float(expected), delta=0.01
                                    )
                persisted_values = (
                    comparisons[0]["savingPct"],
                    comparisons[1]["savingPct"],
                    comparisons[2]["savingPct"],
                )
                recomputed_values = (
                    result.usage_saving_pct,
                    result.cost_saving_pct,
                    result.intensity_saving_pct,
                )
                for actual, recomputed, anchor in zip(
                    persisted_values,
                    recomputed_values,
                    case["anchors"],
                    strict=True,
                ):
                    self.assertAlmostEqual(float(actual), float(recomputed), delta=0.01)
                    self.assertAlmostEqual(float(actual), float(anchor), delta=0.01)
                self.assertEqual(persisted_status, result.status)
                self.assertEqual(persisted_status, case["status"])
                self.assertAlmostEqual(float(saving_value), float(result.saving_value), delta=0.01)
                self.assertEqual(saving_unit, result.saving_unit)
                self.assertAlmostEqual(
                    float(saving_pct), float(result.usage_saving_pct), delta=0.01
                )
                self.assertEqual(formula_version, result.formula_version)
                self.assertRegex(signature, r"^SV1-[0-9a-f]{16}$")
                (
                    generated_usage,
                    generated_cost,
                    generated_workload,
                    generated_quality,
                    regenerated_signature,
                ) = generator._verification_payloads(verification_input, result)
                generated_comparisons = (
                    generated_usage,
                    generated_cost,
                    generated_workload,
                    generated_quality,
                )
                self.assertEqual(signature, regenerated_signature)
                self.assertEqual(
                    signature,
                    _history_signature_from_comparisons(
                        verification_input=verification_input,
                        comparisons=generated_comparisons,
                        saving_value=saving_value,
                        saving_unit=saving_unit,
                    ),
                )
                for dimension_index, dimension in enumerate(
                    ("usage", "cost", "workload", "quality")
                ):
                    mutated = copy.deepcopy(generated_comparisons)
                    changed = False
                    for period in ("baseline", "report"):
                        for index, value in enumerate(
                            mutated[dimension_index]["series"][period]
                        ):
                            if value is None:
                                continue
                            mutated[dimension_index]["series"][period][index] = (
                                float(value) + 0.01
                            )
                            changed = True
                            break
                        if changed:
                            break
                    self.assertTrue(changed, f"{dimension} series has no numeric value")
                    self.assertNotEqual(
                        signature,
                        _history_signature_from_comparisons(
                            verification_input=verification_input,
                            comparisons=mutated,
                            saving_value=saving_value,
                            saving_unit=saving_unit,
                        ),
                        f"{dimension} numeric series must be signed",
                    )

                relabeled = copy.deepcopy(generated_comparisons)
                relabeled[0]["series"]["labels"][0] = "展示标签已调整"
                self.assertEqual(
                    signature,
                    _history_signature_from_comparisons(
                        verification_input=verification_input,
                        comparisons=relabeled,
                        saving_value=saving_value,
                        saving_unit=saving_unit,
                    ),
                )
                self.assertEqual(
                    comparisons[3]["baseline"]["coveragePct"],
                    float(result.baseline.coverage_pct),
                )
                self.assertEqual(
                    comparisons[3]["report"]["coveragePct"],
                    float(result.report.coverage_pct),
                )

    def test_shared_b_demo_counts_remain_unchanged(self) -> None:
        self._ensure_generated()
        self.assertEqual(
            _suggestion_domain_counts("b_demo"), self.__class__.shared_before
        )


if __name__ == "__main__":
    unittest.main()
