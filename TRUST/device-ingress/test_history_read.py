import tempfile
import unittest
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
from history_read import open_journal, consume_page, device_journal_path


class HistoryTests(unittest.TestCase):
    def test_independent_consumers_and_explicit_path_cannot_share_cursor(self):
        with tempfile.TemporaryDirectory() as root:
            args = ('http://127.0.0.1:28110', 'SIM-A', 'legacy', '', '')
            a = device_journal_path(root, *args, 'PSMS')
            b = device_journal_path(root, *args, 'WMS')
            self.assertNotEqual(a, b)
            db = open_journal(a, *args, 'PSMS')
            with db: db.execute("UPDATE checkpoint SET cursor='a-position'")
            db.close()
            with self.assertRaises(ValueError): open_journal(a, *args, 'WMS')
            with self.assertRaises(ValueError): open_journal(a, *args)
            db = open_journal(b, *args, 'WMS')
            self.assertIsNone(db.execute('SELECT cursor FROM checkpoint').fetchone()[0])
            db.close()
            legacy = device_journal_path(root, *args)
            open_journal(legacy, *args).close()
            with self.assertRaises(ValueError): open_journal(legacy, *args, 'PSMS')

    def test_scoped_devices_restart_and_identity_changes_do_not_reuse_cursor(self):
        with tempfile.TemporaryDirectory() as root:
            base='http://127.0.0.1:28110'
            for device in ('SIM-A','SIM-B'):
                path=device_journal_path(root,base,device,'scoped','synthetic-reader','revision-1')
                db=open_journal(path,base,device,'scoped','synthetic-reader','revision-1')
                def fetch(route):
                    q=parse_qs(urlsplit(route).query)
                    if '/history?' in route:
                        self.assertEqual(q['device_id'][0],device)
                        return {'items':[] if q.get('cursor') else [{'event_id':device+':1','wire_sha256':'sha','cursor':device+':cursor','device_id':device}], 'has_more':False}
                    return {'event_id':device+':1','wire_sha256':'sha','state':'COMMITTED','broker_message_id':'broker','event':{'event_id':device+':1','device_id':device}}
                self.assertEqual(consume_page(db,fetch,device)['new_events'],1); db.close()
                db=open_journal(path,base,device,'scoped','synthetic-reader','revision-1')
                self.assertEqual(consume_page(db,fetch,device)['new_events'],0)
                with self.assertRaises(ValueError): consume_page(db,fetch,'SIM-OTHER')
                db.close()
                with self.assertRaises(ValueError): open_journal(path,base,device,'scoped','different-reader','revision-1')
                with self.assertRaises(ValueError): open_journal(path,base,device,'scoped','synthetic-reader','revision-2')
            old=Path(root)/'legacy.sqlite'; open_journal(old,base,'SIM-A').close()
            with self.assertRaises(ValueError): open_journal(old,base,'SIM-A','scoped','synthetic-reader','revision-1')
            self.assertNotEqual(device_journal_path(root,base,'SIM-A','scoped','synthetic-reader','revision-1'),device_journal_path(root,base,'SIM-A','scoped','synthetic-reader','revision-2'))

    def test_wrong_device_response_never_advances_or_applies(self):
        with tempfile.TemporaryDirectory() as root:
            db=open_journal(Path(root)/'scope.sqlite','http://127.0.0.1:28110','SIM-A','scoped','synthetic-reader','v1')
            def fetch(route):
                if '/history?' in route: return {'items':[{'event_id':'b','wire_sha256':'s','cursor':'c'}],'has_more':False}
                return {'event_id':'b','wire_sha256':'s','state':'COMMITTED','broker_message_id':'m','event':{'event_id':'b','device_id':'SIM-B'}}
            try:
                with self.assertRaises(ValueError): consume_page(db,fetch,'SIM-A')
                self.assertIsNone(db.execute('SELECT cursor FROM checkpoint').fetchone()[0])
                self.assertEqual(db.execute('SELECT count(*) FROM public_events').fetchone()[0],0)
            finally: db.close()

    def test_failure_rolls_back_effect_and_cursor_then_restart_catches_up(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'journal.sqlite'
            db = open_journal(path, 'http://127.0.0.1:28110', '')
            db.execute('CREATE TABLE business(event_id TEXT PRIMARY KEY)')
            def fetch(route):
                if '/history?' in route:
                    cursor = parse_qs(urlsplit(route).query).get('cursor', ['0'])[0]
                    items = [{'event_id': str(i), 'wire_sha256': str(i), 'cursor': str(i)} for i in (1, 2) if i > int(cursor)]
                    return {'items': items, 'has_more': False}
                eid = parse_qs(urlsplit(route).query)['event_id'][0]
                return {'event_id': eid, 'wire_sha256': eid, 'state': 'COMMITTED',
                        'broker_message_id': 'test', 'event': {'event_id': eid}}
            def broken_apply(connection, receipt):
                connection.execute('INSERT INTO business VALUES(?)', (receipt['event_id'],))
                if receipt['event_id'] == '2':
                    raise RuntimeError('business transaction failed')
            with self.assertRaises(RuntimeError):
                consume_page(db, fetch, apply=broken_apply)
            self.assertEqual(db.execute('SELECT cursor FROM checkpoint').fetchone()[0], '1')
            self.assertEqual(db.execute('SELECT count(*) FROM business').fetchone()[0], 1)
            db.close()
            db = open_journal(path, 'http://127.0.0.1:28110', '')
            def apply(connection, receipt):
                connection.execute('INSERT INTO business VALUES(?)', (receipt['event_id'],))
            self.assertEqual(consume_page(db, fetch, apply=apply)['new_events'], 1)
            self.assertEqual(consume_page(db, fetch)['new_events'], 0)
            self.assertEqual(db.execute('SELECT count(*) FROM business').fetchone()[0], 2)
            with db:
                db.execute("UPDATE checkpoint SET cursor='0'")
            self.assertEqual(consume_page(db, fetch, apply=apply)['new_events'], 0)
            self.assertEqual(db.execute('SELECT count(*) FROM business').fetchone()[0], 2)
            db.close()
            with self.assertRaises(ValueError):
                open_journal(path, 'http://127.0.0.1:28110', 'SIM-OTHER')


if __name__ == '__main__':
    unittest.main()
