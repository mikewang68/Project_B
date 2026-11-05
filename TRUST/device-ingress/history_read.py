"""Durable public-event journal and cursor example; does not apply business rules."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import sqlite3
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, ProxyHandler, build_opener

from client_read import NoRedirect

API = '/api/device-data/v1'


def open_journal(path, base_url, device_id, access_mode='legacy', principal_id='', policy_revision='', consumer_id=''):
    if consumer_id and not re.fullmatch(r'[A-Za-z0-9_.-]{1,64}', consumer_id):
        raise ValueError('invalid consumer identity')
    if access_mode not in ('legacy', 'scoped'):
        raise ValueError('invalid access mode')
    if access_mode == 'scoped' and (not device_id or not principal_id or not policy_revision):
        raise ValueError('scoped journal requires device, trusted principal and policy revision')
    if access_mode == 'legacy' and (principal_id or policy_revision):
        raise ValueError('legacy journal does not accept scoped identity')
    db = sqlite3.connect(path, timeout=10)
    db.execute('PRAGMA journal_mode=WAL')
    db.execute('PRAGMA synchronous=FULL')
    try:
        with db:
            db.execute('CREATE TABLE IF NOT EXISTS checkpoint(id INTEGER PRIMARY KEY CHECK(id=1),base_url TEXT NOT NULL,device_id TEXT NOT NULL,cursor TEXT)')
            db.execute('CREATE TABLE IF NOT EXISTS public_events(event_id TEXT PRIMARY KEY,wire_sha256 TEXT NOT NULL,receipt TEXT NOT NULL)')
            row = db.execute('SELECT base_url,device_id FROM checkpoint WHERE id=1').fetchone()
            if row and row != (base_url, device_id):
                raise ValueError('journal endpoint/device scope cannot change')
            db.execute('CREATE TABLE IF NOT EXISTS journal_consumer(id INTEGER PRIMARY KEY CHECK(id=1),consumer_id TEXT NOT NULL)')
            owner = db.execute('SELECT consumer_id FROM journal_consumer WHERE id=1').fetchone()
            if (owner and owner[0] != consumer_id) or (row and not owner and consumer_id):
                raise ValueError('consumer cannot inherit another or legacy cursor; use a new journal')
            db.execute('INSERT OR IGNORE INTO journal_consumer VALUES(1,?)', (consumer_id,))
            db.execute('CREATE TABLE IF NOT EXISTS journal_scope(id INTEGER PRIMARY KEY CHECK(id=1),mode TEXT NOT NULL,principal_id TEXT NOT NULL,policy_revision TEXT NOT NULL)')
            scope = db.execute('SELECT mode,principal_id,policy_revision FROM journal_scope WHERE id=1').fetchone()
            wanted = (access_mode, principal_id, policy_revision)
            if (scope and scope != wanted) or (row and not scope and access_mode != 'legacy'):
                raise ValueError('cursor identity cannot change; use a new journal and idempotent replay')
            db.execute('INSERT OR IGNORE INTO journal_scope VALUES(1,?,?,?)', wanted)
            db.execute('INSERT OR IGNORE INTO checkpoint VALUES(1,?,?,NULL)', (base_url, device_id))
    except BaseException:
        db.close()
        raise
    return db


def device_journal_path(directory, base_url, device_id, access_mode, principal_id, policy_revision, consumer_id=''):
    # Do not put tokens or user-controlled path components in filenames.
    parts = [base_url, device_id, access_mode, principal_id, policy_revision]
    if consumer_id:
        if not re.fullmatch(r'[A-Za-z0-9_.-]{1,64}', consumer_id):
            raise ValueError('invalid consumer identity')
        parts.append(consumer_id)
    namespace = json.dumps(parts, separators=(',', ':'))
    return Path(directory) / (hashlib.sha256(namespace.encode()).hexdigest() + '.sqlite')


def consume_page(db, fetch, device_id='', limit=100, apply=None):
    registered = db.execute('SELECT device_id FROM checkpoint WHERE id=1').fetchone()[0]
    if registered != device_id:
        raise ValueError('page device does not match journal')
    cursor = db.execute('SELECT cursor FROM checkpoint WHERE id=1').fetchone()[0]
    query = {'limit': limit}
    if cursor:
        query['cursor'] = cursor
    if device_id:
        query['device_id'] = device_id
    page = fetch(API + '/history?' + urlencode(query))
    count = 0
    for item in page['items']:
        receipt = fetch(API + '/events?' + urlencode({'event_id': item['event_id']}))
        if (receipt.get('state') != 'COMMITTED' or receipt.get('event_id') != item['event_id']
                or receipt.get('wire_sha256') != item['wire_sha256']
                or receipt.get('event', {}).get('event_id') != item['event_id']
                or not receipt.get('broker_message_id')):
            raise ValueError('history receipt identity/hash mismatch')
        if device_id and (receipt.get('event', {}).get('device_id') != device_id
                          or item.get('device_id', device_id) != device_id):
            raise ValueError('history returned another device')
        with db:
            existing = db.execute('SELECT wire_sha256 FROM public_events WHERE event_id=?', (item['event_id'],)).fetchone()
            if existing and existing[0] != item['wire_sha256']:
                raise ValueError('historical identity conflict')
            if not existing:
                # Real adapters must commit business effects and this checkpoint
                # together, or use an idempotent inbox keyed by event_id.
                if apply:
                    apply(db, receipt)
                db.execute('INSERT INTO public_events VALUES(?,?,?)',
                           (item['event_id'], item['wire_sha256'], json.dumps(receipt, ensure_ascii=False)))
                count += 1
            db.execute('UPDATE checkpoint SET cursor=? WHERE id=1', (item['cursor'],))
    # Empty pages keep the previous position. Never advance to a wall-clock time.
    return {'new_events': count, 'page_events': len(page['items']), 'has_more': page['has_more']}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', type=Path)
    p.add_argument('--base-url')
    p.add_argument('--token-file', type=Path)
    p.add_argument('--device-id', action='append', default=[])
    journals = p.add_mutually_exclusive_group(required=True)
    journals.add_argument('--journal', type=Path)
    journals.add_argument('--journal-dir', type=Path)
    p.add_argument('--access-mode', choices=('legacy', 'scoped'), default='legacy')
    p.add_argument('--principal-id', default='')
    p.add_argument('--policy-revision', default='')
    p.add_argument('--consumer-id', default='', help='Stable independent consumer namespace; omitted only for legacy journals')
    p.add_argument('--limit', type=int, default=100)
    p.add_argument('--max-pages', type=int, default=100)
    a = p.parse_args()
    config = json.loads(a.config.read_text(encoding='utf-8')) if a.config else {}
    base = a.base_url or config.get('base_url')
    token_path = a.token_file or config.get('token_file')
    if not base or not token_path or not 1 <= a.limit <= 100 or a.max_pages < 1:
        p.error('connection arguments, limit 1..100 and positive max-pages required')
    endpoint = urlsplit(base)
    if (endpoint.scheme != 'http' or endpoint.hostname != '127.0.0.1' or not endpoint.port
            or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment or endpoint.path not in ('', '/')):
        p.error('only an explicit HTTP loopback endpoint is supported')
    base = base.rstrip('/')
    devices = a.device_id or ['']
    if len(set(devices)) != len(devices) or (a.journal and len(devices) != 1):
        p.error('use journal-dir for multiple distinct device IDs')
    if a.access_mode == 'scoped' and ('' in devices or not a.principal_id or not a.policy_revision):
        p.error('scoped mode requires explicit device IDs, principal-id and policy-revision')
    token = Path(token_path).read_text(encoding='utf-8').strip()
    opener = build_opener(ProxyHandler({}), NoRedirect())
    def fetch(path):
        with opener.open(Request(base + path, headers={'Authorization': 'Bearer ' + token}), timeout=10) as response:
            data = response.read(1048577)
        if len(data) > 1048576:
            raise ValueError('response too large')
        return json.loads(data)
    reports = []
    if a.journal_dir:
        a.journal_dir.mkdir(parents=True, exist_ok=True)
    for device in devices:
        path = a.journal or device_journal_path(a.journal_dir, base, device, a.access_mode, a.principal_id, a.policy_revision, a.consumer_id)
        db = open_journal(path, base, device, a.access_mode, a.principal_id, a.policy_revision, a.consumer_id)
        total = 0
        try:
            for _ in range(a.max_pages):
                result = consume_page(db, fetch, device, a.limit)
                total += result['new_events']
                if not result['has_more']:
                    break
            reports.append({'device_id': device, 'new_events': total, 'has_more': result['has_more'],
                            'journal_events': db.execute('SELECT count(*) FROM public_events').fetchone()[0],
                            'business_acceptance': False})
        finally:
            db.close()
    print(json.dumps(reports[0] if len(reports) == 1 else {'devices': reports, 'business_acceptance': False}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'status': 'FAIL', 'error_type': type(error).__name__}))
        raise SystemExit(1)
