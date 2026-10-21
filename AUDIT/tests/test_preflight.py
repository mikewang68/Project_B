import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('preflight', Path(__file__).resolve().parents[1] / 'scripts/preflight-config.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)


class ConfigParsing(unittest.TestCase):
    def parse(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'config.env'; path.write_text(text, encoding='utf-8')
            return module.read_env(path)
    def test_quotes_and_comments(self):
        self.assertEqual(self.parse('# note\nexport A="literal value"\nB=plain'), {'A': 'literal value', 'B': 'plain'})
    def test_duplicate_rejected(self):
        with self.assertRaises(ValueError): self.parse('A=one\nA=two')
    def test_commands_are_never_executed(self):
        for line in ('A=$(touch file)', 'A=`command`', 'print secret'):
            with self.assertRaises(ValueError): self.parse(line)
    def test_invalid_key_rejected(self):
        with self.assertRaises(ValueError): self.parse('A;exit=value')


if __name__ == '__main__':
    unittest.main()
