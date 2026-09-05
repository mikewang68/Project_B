"""第五幕成本版本与报表归档结构回归测试（REQ-051～062/073/074/076）。"""

from pathlib import Path
import re
import unittest


DATAGEN_DIR = Path(__file__).resolve().parents[1]
DDL = (DATAGEN_DIR / "ddl" / "V001__energy_domain.sql").read_text(
    encoding="utf-8"
)
GENERATOR = (DATAGEN_DIR / "generate_demo_data.py").read_text(encoding="utf-8")
required_cost_fields = (
    "cost_version",
    "is_current",
    "normalized_object_id",
    "current_guard",
    "formula_version",
    "tariff_snapshot_json",
    "alloc_rule_snapshot_json",
    "source_stat_snapshot_json",
    "computed_by",
)


def table_body(table_name: str) -> str:
    match = re.search(
        rf"create table {table_name} \((.*?)\) engine=innodb",
        DDL,
        re.DOTALL | re.IGNORECASE,
    )
    if match is None:
        raise AssertionError(f"missing table {table_name}")
    return match.group(1)


class Act5SchemaTest(unittest.TestCase):
    def assert_fields(self, body: str, fields: tuple[str, ...]) -> None:
        for field in fields:
            with self.subTest(field=field):
                self.assertRegex(body, rf"\b{field}\b")

    def test_cost_record_is_versioned_and_has_one_current_row_per_scope(self) -> None:
        body = table_body("e_cost_record")
        self.assert_fields(body, required_cost_fields)
        self.assertRegex(
            body,
            r"normalized_object_id\s+bigint\(20\).*?"
            r"generated always as \(ifnull\(object_id,\s*0\)\)",
        )
        self.assertRegex(
            body,
            r"current_guard\s+varchar\(160\).*?generated always as\s*"
            r"\(case when is_current = 1 then .*? else null end\)",
        )
        self.assertRegex(
            body,
            r"unique key uk_cost_version\s*\(object_type, normalized_object_id, "
            r"stat_month, energy_type_code, cost_version\)",
        )
        self.assertRegex(body, r"unique key uk_cost_current\s*\(current_guard\)")
        self.assertNotRegex(body, r"\buk_object_month_type\b")

    def test_cost_snapshots_and_full_signature_are_not_nullable(self) -> None:
        body = table_body("e_cost_record")
        for field in (
            "formula_version",
            "tariff_snapshot_json",
            "alloc_rule_snapshot_json",
            "source_stat_snapshot_json",
            "signature",
            "computed_by",
        ):
            with self.subTest(field=field):
                self.assertRegex(body, rf"\b{field}\b[^,\n]*\bnot null\b")
        self.assertRegex(body, r"\bsignature\s+varchar\(96\)\s+not null\b")

    def test_cost_lifecycle_uses_contract_tokens_and_review_timestamp(self) -> None:
        body = table_body("e_cost_record")
        self.assertRegex(body, r"\breviewed_at\s+datetime\s+default null\b")
        status_line = re.search(r"^\s*status\b.*$", body, re.MULTILINE)
        self.assertIsNotNone(status_line)
        self.assertIn("pendingReview", status_line.group(0))
        self.assertIn("pendingRecompute", status_line.group(0))
        self.assertNotIn("pending_review", status_line.group(0))
        self.assertNotIn("pending_recompute", status_line.group(0))

    def test_act5_tables_drop_children_before_cost_then_create_in_order(self) -> None:
        drop_archive = DDL.index("drop table if exists e_report_archive;")
        drop_recompute = DDL.index("drop table if exists e_cost_recompute_record;")
        drop_cost = DDL.index("drop table if exists e_cost_record;")
        create_cost = DDL.index("create table e_cost_record (")
        create_recompute = DDL.index("create table e_cost_recompute_record (")
        create_archive = DDL.index("create table e_report_archive (")
        self.assertLess(
            drop_archive,
            drop_recompute,
            "report archives must be dropped before recomputation evidence",
        )
        self.assertLess(
            drop_recompute,
            drop_cost,
            "recomputation evidence must be dropped before cost versions",
        )
        self.assertLess(drop_cost, create_cost)
        self.assertLess(create_cost, create_recompute)
        self.assertLess(create_recompute, create_archive)

    def test_recompute_record_freezes_header_diff_and_review_evidence(self) -> None:
        body = table_body("e_cost_recompute_record")
        self.assert_fields(
            body,
            (
                "recompute_id",
                "period_key",
                "stat_month",
                "energy_type_code",
                "scope",
                "old_cost_version",
                "new_cost_version",
                "trigger_reason",
                "trigger_type",
                "triggered_by",
                "triggered_at",
                "tariff_snapshot_json",
                "alloc_rule_snapshot_json",
                "diff_summary_json",
                "review_status",
                "reviewed_by",
                "reviewed_at",
                "review_remark",
            ),
        )
        self.assertRegex(body, r"\bdiff_summary_json\s+longtext\s+not null\b")
        self.assertNotRegex(body, r"\b(?:alert|suggestion)_(?:id|event_id)\b")
        self.assertNotRegex(body, r"\bforeign key\b")

    def test_report_archive_freezes_canonical_payload_and_full_signature(self) -> None:
        body = table_body("e_report_archive")
        self.assert_fields(
            body,
            (
                "archive_id",
                "template_code",
                "template_version",
                "period_start",
                "period_end",
                "filters_snapshot_json",
                "payload_snapshot_json",
                "version_snapshots_json",
                "full_signature",
                "generated_at",
                "archived_by",
                "archived_at",
            ),
        )
        for field in (
            "filters_snapshot_json",
            "payload_snapshot_json",
            "version_snapshots_json",
        ):
            with self.subTest(field=field):
                self.assertRegex(body, rf"\b{field}\s+longtext\s+not null\b")
        self.assertRegex(
            body, r"\bfull_signature\s+varchar\(96\)\s+not null\b"
        )
        self.assertNotRegex(body, r"\bforeign key\b")

    def test_idempotent_truncate_clears_act5_results_in_dependency_order(self) -> None:
        truncate_block = re.search(
            r"def truncate_business_data\(.*?tables = \[(.*?)\]",
            GENERATOR,
            re.DOTALL,
        )
        self.assertIsNotNone(truncate_block)
        tables = truncate_block.group(1)
        archive = tables.index('"e_report_archive"')
        recompute = tables.index('"e_cost_recompute_record"')
        cost = tables.index('"e_cost_record"')
        self.assertLess(archive, recompute)
        self.assertLess(recompute, cost)

    def test_generator_does_not_seed_act5_result_tables(self) -> None:
        for table in (
            "e_cost_record",
            "e_cost_recompute_record",
            "e_report_archive",
        ):
            with self.subTest(table=table):
                self.assertNotRegex(
                    GENERATOR,
                    rf"INSERT\s+(?:IGNORE\s+)?INTO\s+{table}\b",
                )

    def test_cost_and_report_menu_branch_is_released_together(self) -> None:
        for menu_id in (2012, 2013, 2014):
            with self.subTest(menu_id=menu_id):
                menu = re.search(rf"\({menu_id},.*?\),", GENERATOR)
                self.assertIsNotNone(menu, f"menu {menu_id} missing")
                self.assertRegex(menu.group(0), r'"0",\s*"[^"]*第五幕已开发[^"]*"\),$')
                self.assertNotIn("隐藏", menu.group(0))
                self.assertNotIn("未开发", menu.group(0))


if __name__ == "__main__":
    unittest.main()
