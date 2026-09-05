"""第四幕建议闭环数据结构回归测试（REQ-045～050/091/096）。"""

from pathlib import Path
import re
import unittest


DATAGEN_DIR = Path(__file__).resolve().parents[1]
DDL = (DATAGEN_DIR / "ddl" / "V001__energy_domain.sql").read_text(
    encoding="utf-8"
)
GENERATOR = (DATAGEN_DIR / "generate_demo_data.py").read_text(encoding="utf-8")


def table_body(table_name: str) -> str:
    match = re.search(
        rf"create table {table_name} \((.*?)\) engine=innodb",
        DDL,
        re.DOTALL | re.IGNORECASE,
    )
    if match is None:
        raise AssertionError(f"missing table {table_name}")
    return match.group(1)


class Act4SchemaTest(unittest.TestCase):
    def assert_fields(self, body: str, fields: tuple[str, ...]) -> None:
        for field in fields:
            with self.subTest(field=field):
                self.assertRegex(body, rf"\b{field}\b")

    def test_template_freezes_all_req_045_fields_by_version(self) -> None:
        body = table_body("e_suggestion_template")
        self.assert_fields(
            body,
            (
                "template_id",
                "template_code",
                "template_name",
                "category",
                "source_rule_code",
                "applicable_object_type",
                "action_content",
                "required_data",
                "estimated_saving",
                "cost_impact",
                "reliability_impact",
                "verification_method",
                "default_implementation_difficulty",
                "default_safety_impact",
                "enabled",
                "version",
                "create_time",
                "update_time",
            ),
        )
        self.assertRegex(
            body,
            r"unique key uk_template_code_version \(template_code, version\)",
        )

    def test_suggestion_keeps_source_priority_verification_and_close_evidence(self) -> None:
        body = table_body("e_suggestion")
        self.assert_fields(
            body,
            (
                "source_type",
                "source_alert_id",
                "source_fingerprint",
                "source_snapshot_json",
                "template_id",
                "template_version",
                "template_snapshot_json",
                "trigger_basis",
                "rule_code",
                "object_type",
                "object_id",
                "priority_score",
                "priority_band",
                "priority_formula_version",
                "priority_factors_json",
                "repair_at",
                "baseline_start",
                "baseline_end",
                "report_start",
                "report_end",
                "saving_value",
                "saving_unit",
                "close_reason",
                "rejection_reason",
                "invalid_category",
                "deferred_from_status",
                "defer_reason",
                "defer_until",
                "created_by",
                "closed_by",
                "closed_at",
                "row_version",
            ),
        )
        self.assertRegex(
            body,
            r"unique key uk_source_template \(source_fingerprint, template_id\)",
        )
        self.assertRegex(
            body,
            r"object_id\s+bigint\(20\)\s+default null",
            "system suggestions use objectType=system with objectId=null",
        )
        self.assertNotRegex(
            body,
            r"foreign key\s*\(\s*source_alert_id\s*\)",
            "reset/bootstrap can recreate alert IDs, so source_alert_id is not an FK",
        )

    def test_flow_and_verification_are_append_only_versioned_evidence(self) -> None:
        flow = table_body("e_suggestion_flow_log")
        self.assert_fields(
            flow,
            (
                "flow_id",
                "suggestion_id",
                "from_status",
                "to_status",
                "operator",
                "operator_role",
                "action",
                "remark",
                "payload_snapshot_json",
                "occur_time",
            ),
        )
        self.assertRegex(
            flow, r"key idx_suggestion_flow \(suggestion_id, flow_id\)"
        )

        verification = table_body("e_suggestion_verification")
        self.assert_fields(
            verification,
            (
                "verification_id",
                "suggestion_id",
                "version",
                "status",
                "repair_at",
                "baseline_start",
                "baseline_end",
                "report_start",
                "report_end",
                "usage_comparison_json",
                "cost_comparison_json",
                "workload_comparison_json",
                "quality_comparison_json",
                "saving_value",
                "saving_unit",
                "saving_pct",
                "calculation_note",
                "formula_version",
                "signature",
                "generated_by",
                "generated_at",
            ),
        )
        self.assertRegex(
            verification,
            r"unique key uk_suggestion_verification_version "
            r"\(suggestion_id, version\)",
        )

    def test_reset_orders_suggestion_children_before_parent_and_template(self) -> None:
        block = re.search(
            r"def truncate_business_data\(.*?tables = \[(.*?)\]",
            GENERATOR,
            re.DOTALL,
        )
        self.assertIsNotNone(block)
        reset_tables = block.group(1)
        expected_tables = (
            "e_suggestion_flow_log",
            "e_suggestion_verification",
            "e_suggestion",
            "e_suggestion_template",
        )
        for table in expected_tables:
            self.assertIn(f'"{table}"', reset_tables)
        positions = [
            reset_tables.index(f'"{table}"') for table in expected_tables
        ]
        self.assertEqual(positions, sorted(positions))

    def test_history_adapter_reads_raw_inputs_and_calls_shared_formula(self) -> None:
        adapter = re.search(
            r"def load_verification_input\(.*?(?=\ndef _priority_result)",
            GENERATOR,
            re.DOTALL,
        )
        self.assertIsNotNone(adapter)
        adapter_source = adapter.group(0)
        for source_table in (
            "e_meter_point",
            "e_raw_reading",
            "e_tariff_version",
            "e_work_order",
        ):
            self.assertIn(source_table, adapter_source)
        self.assertNotRegex(adapter_source, r"\be_stat_(hour|day|month)\b")
        self.assertIn("calculate_history_verification", GENERATOR)

    def test_suggestion_schema_has_no_control_instruction_surface(self) -> None:
        suggestion_domain = "\n".join(
            table_body(name)
            for name in (
                "e_suggestion_template",
                "e_suggestion",
                "e_suggestion_flow_log",
                "e_suggestion_verification",
            )
        )
        for forbidden in (
            "device_command",
            "control_instruction",
            "power_adjustment",
            "start_stop_command",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, suggestion_domain.lower())

    def test_act4_suggestion_menu_is_released_with_expected_permission(self) -> None:
        menu = re.search(r"\(2011,.*?\),", GENERATOR)
        self.assertIsNotNone(menu)
        self.assertIn('"energy:alert:suggestion"', menu.group(0))
        self.assertRegex(menu.group(0), r'"C",\s*"0",')
        self.assertIn("第四幕已开发", menu.group(0))


if __name__ == "__main__":
    unittest.main()
