from pathlib import Path
import re
import unittest

class IdentityCatalogTest(unittest.TestCase):
    def test_permission_tree_has_no_overwritten_operation_keys(self):
        sql = (Path(__file__).parents[2] / 'IAM/backend/db/isolated/005-permissions.sql').read_text(encoding='utf-8')
        entries = re.findall(r"\('([^']+)','([^']+)','BUTTON','([^']+)'", sql)
        keys = [(parent, code.rsplit(':', 1)[-1]) for _, parent, code in entries]
        self.assertGreaterEqual(len(entries), 40)
        self.assertEqual(len(keys), len(set(keys)), 'PermissionService groups operations by parent and suffix; duplicates hide permissions')
        codes = {code for _, _, code in entries}
        for code in ('trust:wallet:read', 'trust:wallet:manage', 'trust:wallet:review', 'trust:identity:read', 'iam:identity:retry:execute', 'iam:identity:rotate:execute', 'iam:identity:revoke:execute'):
            self.assertIn(code, codes)

if __name__ == '__main__':
    unittest.main()
