"""Safety boundaries for the new-database restore tool; no server access."""
import importlib.util
import json
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('restore', pathlib.Path(__file__).resolve().parents[1] / 'deploy/verify-isolated-restore.py')
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)

class RestoreBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = pathlib.Path(self.tmp.name)
        (root / 'tools').mkdir()
        (root / 'runtime').mkdir()
        self.m = dict(schemaVersion=1, purpose='isolated-recovery', instance='test-instance',
                      sourceDatabase='trust_iam_test_fixture', targetDatabase='trust_restore_fixture',
                      owner='test_operator', databaseOwner='test_owner', engine='opengauss', compatibility='A', host=str(root / 'runtime'),
                      port=25432, resourceRoot=str(root), binaryDirectory=str(root / 'tools'),
                      outputDirectory=str(root / 'new-backup'), resourceAllowlist=str(root / 'allow.json'))
        self.allow()

    def allow(self):
        pathlib.Path(self.m['resourceAllowlist']).write_text(json.dumps({'instances': {self.m['instance']:
            {k: self.m[k] for k in ('sourceDatabase','owner','databaseOwner','host','port','compatibility','engine')}}}))

    def test_explicit_resources_are_accepted(self):
        restore.validate(self.m)

    def test_only_literal_array_cast_distribution_is_normalized(self):
        left = "CHECK (mode = ANY ((ARRAY['real'::character varying, 'it''s'::character varying])::text[]))"
        right = "CHECK (mode = ANY (ARRAY[('real'::character varying)::text, ('it''s'::character varying)::text]))"
        self.assertEqual(restore.normalize_dump_casts(left), right)
        self.assertNotEqual(restore.normalize_dump_casts(left.replace("'real'", "'other'")), right)
        for text in ["(ARRAY[column_a, column_b])::text[]", "(ARRAY['a'::character(2)])::text[]"]:
            self.assertEqual(restore.normalize_dump_casts(text), text)

    def test_source_overwrite_and_wrong_instance_rejected(self):
        for change in ({'targetDatabase': self.m['sourceDatabase']}, {'instance': 'other'},
                       {'sourceDatabase': 'trust'}, {'sourceDatabase': 'trust_iam_test_other'},
                       {'compatibility': 'PG'}, {'host': '192.0.2.1'}, {'owner': 'x;DROP'},
                       {'databaseOwner': 'other_owner'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                restore.validate({**self.m, **change})

    def test_existing_output_or_outside_path_rejected(self):
        pathlib.Path(self.m['outputDirectory']).mkdir()
        with self.assertRaises(ValueError): restore.validate(self.m)
        with self.assertRaises(ValueError): restore.validate({**self.m, 'outputDirectory': str(pathlib.Path(self.tmp.name).parent / 'outside')})

if __name__ == '__main__': unittest.main()
