"""第三幕数据结构与发布菜单回归测试（REQ-041/045～050）。"""

from pathlib import Path
import re
import unittest


DATAGEN_DIR = Path(__file__).resolve().parents[1]
DDL = (DATAGEN_DIR / "ddl" / "V001__energy_domain.sql").read_text(encoding="utf-8")
GENERATOR = (DATAGEN_DIR / "generate_demo_data.py").read_text(encoding="utf-8")


class Act3SchemaTest(unittest.TestCase):
    def test_alert_flow_log_keeps_full_transition_evidence(self) -> None:
        table = re.search(
            r"create table e_alert_flow_log \((.*?)\) engine=innodb",
            DDL,
            re.DOTALL | re.IGNORECASE,
        )
        self.assertIsNotNone(table, "REQ-041 requires e_alert_flow_log")
        body = table.group(1)
        for field in (
            "event_id",
            "from_status",
            "to_status",
            "operator",
            "remark",
            "occur_time",
        ):
            self.assertRegex(body, rf"\b{field}\b")
        self.assertRegex(body, r"key idx_event_time \(event_id, occur_time\)")

    def test_reset_truncates_alert_flow_log(self) -> None:
        truncate_block = re.search(
            r"def truncate_business_data\(.*?tables = \[(.*?)\]",
            GENERATOR,
            re.DOTALL,
        )
        self.assertIsNotNone(truncate_block)
        self.assertIn('"e_alert_flow_log"', truncate_block.group(1))

    def test_menu_entries_are_released_through_act5(self) -> None:
        expected_visibility = {
            2000: "0",
            2001: "0",
            2002: "0",
            2003: "1",
            2004: "0",
            2005: "1",
            2006: "0",
            2007: "1",
            2008: "0",
            2009: "0",
            2010: "0",
            2011: "0",
            2012: "0",
            2013: "0",
            2014: "0",
        }
        for menu_id, visible in expected_visibility.items():
            menu = re.search(rf"\({menu_id},.*?\),", GENERATOR)
            self.assertIsNotNone(menu, f"menu {menu_id} missing")
            self.assertRegex(menu.group(0), rf'"{visible}",\s*"[^"]+"\),$')

        for menu_id in (2002, 2004, 2009, 2010):
            menu = re.search(rf"\({menu_id},.*?\),", GENERATOR)
            self.assertIn("第三幕", menu.group(0))
            self.assertNotIn("A1", menu.group(0))

        suggestion_menu = re.search(r"\(2011,.*?\),", GENERATOR)
        self.assertIn("第四幕已开发", suggestion_menu.group(0))
        self.assertNotIn("隐藏", suggestion_menu.group(0))

        for menu_id in (2012, 2013, 2014):
            menu = re.search(rf"\({menu_id},.*?\),", GENERATOR)
            self.assertIn("第五幕已开发", menu.group(0))
            self.assertNotIn("隐藏", menu.group(0))

    def test_datagen_does_not_seed_computed_act3_results(self) -> None:
        self.assertNotRegex(GENERATOR, r"INSERT\s+INTO\s+e_alert_event")
        self.assertNotRegex(GENERATOR, r"INSERT\s+INTO\s+e_equipment_profile")


if __name__ == "__main__":
    unittest.main()
