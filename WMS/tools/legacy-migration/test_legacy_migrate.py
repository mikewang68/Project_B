import unittest
from decimal import Decimal
import os
import sqlite3
import tempfile

import legacy_migrate as migration


class LegacyMigrationTransformTest(unittest.TestCase):
    def test_state_and_type_mappings(self):
        self.assertEqual("RECEIVING", migration.enum("part", migration.IN_STATES, "DRAFT"))
        self.assertEqual("COMPLETED", migration.enum("done", migration.OUT_STATES, "DRAFT"))
        self.assertEqual("MATERIAL_RETURN", migration.enum("material_return", migration.INBOUND_TYPES, "NORMAL"))
        self.assertEqual("NORMAL", migration.enum("unknown", migration.INBOUND_TYPES, "NORMAL"))

    def test_invalid_and_negative_numbers_are_safe(self):
        self.assertEqual(0, migration.number("not-a-number"))
        self.assertEqual(0, migration.number("-10"))
        self.assertEqual(Decimal("1.25"), migration.number("1.25"))

    def test_text_is_trimmed_and_limited(self):
        self.assertEqual("abc", migration.text("  abc  "))
        self.assertEqual("ab", migration.text("abcd", limit=2))
        self.assertEqual("fallback", migration.text(None, "fallback"))

    def test_sensitive_archive_values_are_redacted(self):
        result = migration.redact({"username": "demo", "password": "plain", "qimen_secret": "secret"})
        self.assertEqual("demo", result["username"])
        self.assertEqual("***REDACTED***", result["password"])
        self.assertEqual("***REDACTED***", result["qimen_secret"])

    def test_sqlite_adapter_reads_business_and_auth_in_read_only_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            business = os.path.join(directory, "business.sqlite3")
            auth = os.path.join(directory, "auth.sqlite3")
            connection = sqlite3.connect(business)
            try:
                connection.execute("CREATE TABLE sample(id INTEGER PRIMARY KEY, company_code TEXT)")
                connection.execute("INSERT INTO sample VALUES(1,'default')")
                connection.commit()
            finally:
                connection.close()
            connection = sqlite3.connect(auth)
            try:
                connection.execute("CREATE TABLE u_user(id INTEGER PRIMARY KEY, company_code TEXT, code TEXT)")
                connection.execute("INSERT INTO u_user VALUES(1,'default','demo')")
                connection.commit()
            finally:
                connection.close()
            adapter = migration.SQLiteSourceAdapter(business, auth)
            try:
                with adapter.cursor() as cursor:
                    cursor.execute("SELECT * FROM `u_user` WHERE company_code=%s", ("default",))
                    self.assertEqual("demo", cursor.fetchone()["code"])
                self.assertEqual({"id", "company_code", "code"}, migration.table_columns(adapter, business, "u_user"))
                self.assertEqual(64, len(migration.source_fingerprint(adapter, business)))
            finally:
                adapter.close()


if __name__ == "__main__":
    unittest.main()
