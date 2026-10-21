#!/usr/bin/env python3
"""Restore one explicitly authorized isolated database into a NEW database, without workers.

Run as the database operator. Passwords belong in the client's restricted password file or
environment, never the manifest. Stop only the dedicated application before this command.
The source and restored databases, dump, and receipt are retained; no DROP is issued.
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess


def normalize_dump_casts(text):
    # openGauss A-mode dump/reparse distributes an array-to-text cast over its
    # varchar string literals. Normalize ONLY this provably equivalent shape;
    # preserve all values, operators, column references and other casts.
    literal = r"'(?:[^']|'')*'::character varying"
    pattern = r"\(ARRAY\[(" + literal + r"(?:, " + literal + r")*)\]\)::text\[\]"
    return re.sub(pattern, lambda m: 'ARRAY[' + ', '.join(
        '(' + item + ')::text' for item in re.findall(literal, m.group(1))) + ']', text)


def validate(m):
    if m.get('schemaVersion') != 1 or m.get('purpose') != 'isolated-recovery':
        raise ValueError('Explicit isolated recovery manifest required')
    for name in ('instance', 'sourceDatabase', 'targetDatabase', 'owner', 'databaseOwner'):
        if not re.fullmatch(r'[a-z][a-z0-9_\-]{1,62}', m.get(name, '')):
            raise ValueError('Invalid identifier: ' + name)
    if not m['sourceDatabase'].startswith('trust_iam_') or not m['targetDatabase'].startswith('trust_restore_'):
        raise ValueError('Only isolated TRUST source and new recovery database allowed')
    if m['sourceDatabase'] == m['targetDatabase'] or m.get('compatibility') not in ('A', 'PG'):
        raise ValueError('Unsafe target or missing compatibility')
    if m.get('engine') not in ('opengauss', 'postgresql') or not 1024 <= int(m['port']) <= 65535:
        raise ValueError('Invalid engine/port')
    if m['engine'] == 'postgresql' and m['compatibility'] != 'PG':
        raise ValueError('PostgreSQL requires PG compatibility')
    root = pathlib.Path(m['resourceRoot']).resolve(strict=True)
    binary = pathlib.Path(m['binaryDirectory']).resolve(strict=True)
    output = pathlib.Path(m['outputDirectory']).resolve()
    if root not in output.parents or output.exists() or root not in binary.parents:
        raise ValueError('Binary/output must belong to explicit resource root; output must be new')
    host = m['host']
    if host not in ('127.0.0.1', 'localhost'):
        if not pathlib.Path(host).is_absolute():
            raise ValueError('Socket path must be absolute')
        socket = pathlib.Path(host).resolve(strict=True)
        if socket != root / 'runtime':
            raise ValueError('Only loopback or this resource runtime socket allowed')
    allow = pathlib.Path(m['resourceAllowlist']).resolve(strict=True)
    if root not in allow.parents:
        raise ValueError('Allowlist must be inside resource root')
    resource = json.loads(allow.read_text(encoding='utf8'))['instances'].get(m['instance'])
    expected = {k: m[k] for k in ('sourceDatabase', 'owner', 'databaseOwner', 'host', 'port', 'compatibility', 'engine')}
    if resource != expected:
        raise ValueError('Instance/resource allowlist mismatch')
    return binary, output


def execute(m):
    binary, output = validate(m)
    os.umask(0o077)
    output.mkdir(mode=0o700)
    pg = m['engine'] == 'postgresql'
    suffix = '.exe' if os.name == 'nt' else ''
    client = str(binary / (('psql' if pg else 'gsql') + suffix))
    common = ['-h', m['host'], '-p', str(m['port']), '-U', m['owner']]
    def run(args, label, data=None):
        result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300)
        (output / (label + '.stderr')).write_bytes(result.stderr)
        if result.returncode:
            raise RuntimeError(label + ' failed; inspect private output directory')
        return result.stdout
    def query(database, sql, label):
        return run([client, *common, '-d', database, '-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1'],
                   label, sql.encode()).decode('utf8').strip()
    source, target = m['sourceDatabase'], m['targetDatabase']
    if query('postgres', "SELECT count(*) FROM pg_database WHERE datname='" + target + "';", 'target-check') != '0':
        raise ValueError('Target already exists; refusing overwrite')
    mode = 'PG' if pg else query('postgres', "SELECT datcompatibility FROM pg_database WHERE datname='" + source + "';", 'mode')
    if mode != m['compatibility']:
        raise ValueError('Source compatibility mismatch')
    actual_owner = query('postgres', "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='" + source + "';", 'source-owner')
    if actual_owner != m['databaseOwner']:
        raise ValueError('Source database owner mismatch')
    def quiescent():
        # The operator must keep the dedicated application stopped through verification.
        count = query('postgres', "SELECT count(*) FROM pg_stat_activity WHERE datname='" + source + "';", 'source-sessions')
        if count != '0': raise ValueError('Source has sessions: stop its dedicated application first')
    quiescent()
    dump = output / 'source.dump'
    run([str(binary / (('pg_dump' if pg else 'gs_dump') + suffix)), *common, '-F', 'c', '-f', str(dump), source], 'dump')
    quiescent()
    run([str(binary / (('pg_restore' if pg else 'gs_restore') + suffix)), '-l', str(dump)], 'dump-catalog')
    clause = '' if pg else " DBCOMPATIBILITY='" + mode + "'"
    query('postgres', f'CREATE DATABASE "{target}" OWNER "{m["databaseOwner"]}"{clause};', 'create-target')
    query('postgres', f'REVOKE ALL ON DATABASE "{target}" FROM PUBLIC;', 'quarantine-target')
    run([str(binary / (('pg_restore' if pg else 'gs_restore') + suffix)), *common, '-d', target, str(dump)], 'restore')
    query('postgres', f'ALTER DATABASE "{target}" SET default_transaction_read_only=on;', 'read-only-target')
    raw_constraint_digests = {}
    def fingerprints(database):
        tables = query(database, "SELECT tablename FROM pg_tables WHERE schemaname='trust_data' ORDER BY tablename;", database + '-tables').splitlines()
        if not {'events', 'tasks', 'generic_event_data', 'audit_delivery', 'databasechangelog'} <= set(tables):
            raise ValueError('Incomplete TRUST schema')
        values = {}
        for table in tables:
            if not re.fullmatch('[a-z_]+', table): raise ValueError('Unexpected table')
            # Hash each complete row (including ciphertext, original JSON and receipts), sorted
            # independent of physical row order. No plaintext appears in the public receipt.
            rows = query(database, f'SELECT row_to_json(t)::text FROM trust_data."{table}" t ORDER BY 1;', database + '-' + table)
            values[table] = {'rows': len(rows.splitlines()) if rows else 0, 'digest': hashlib.sha256(rows.encode()).hexdigest()}
        for label, sql in {
            'constraints': "SELECT conrelid::regclass::text,conname,pg_get_constraintdef(oid) FROM pg_constraint WHERE connamespace=(SELECT oid FROM pg_namespace WHERE nspname='trust_data') ORDER BY 1,2;",
            'indexes': "SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='trust_data' ORDER BY 1,2;",
            'tableGrants': "SELECT grantee,table_name,privilege_type,is_grantable FROM information_schema.table_privileges WHERE table_schema='trust_data' ORDER BY 1,2,3;",
            'columnGrants': "SELECT grantee,table_name,column_name,privilege_type,is_grantable FROM information_schema.column_privileges WHERE table_schema='trust_data' ORDER BY 1,2,3,4;",
            'relationOwnersAndAcl': "SELECT c.relname,pg_get_userbyid(c.relowner),c.relacl::text FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='trust_data' ORDER BY 1;",
            'schemaOwnerAndAcl': "SELECT pg_get_userbyid(nspowner),nspacl::text FROM pg_namespace WHERE nspname='trust_data';",
        }.items():
            definition = query(database, sql, database + '-' + label)
            if label == 'constraints':
                raw_constraint_digests[database] = hashlib.sha256(definition.encode()).hexdigest()
                definition = normalize_dump_casts(definition)
            values[label] = hashlib.sha256(definition.encode()).hexdigest()
        sequences = query(database, "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='trust_data' AND c.relkind='S' ORDER BY 1;", database + '-sequences').splitlines()
        for sequence in sequences:
            if not re.fullmatch('[a-z_0-9]+', sequence): raise ValueError('Unexpected sequence')
            values['sequence:' + sequence] = hashlib.sha256(query(database, f'SELECT last_value,is_called FROM trust_data."{sequence}";', database + '-' + sequence).encode()).hexdigest()
        return values
    original = fingerprints(source)
    recovered = fingerprints(target)
    quiescent()
    receipt = {'instance': m['instance'], 'sourceDatabase': source, 'targetDatabase': target,
               'compatibility': mode, 'dumpSha256': hashlib.sha256(dump.read_bytes()).hexdigest(),
               'source': original, 'restored': recovered, 'equal': original == recovered,
               'rawConstraintDigests': raw_constraint_digests,
               'constraintNormalization': 'Only varchar literal array cast distribution to text; no other differences ignored',
               'workersStarted': False, 'sourceOverwritten': False}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2), encoding='utf8')
    if original != recovered: raise RuntimeError('Restored fingerprints differ; retained for inspection')
    print(json.dumps({'equal': True, 'receipt': str(output / 'receipt.json'), 'workersStarted': False}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=pathlib.Path)
    args = parser.parse_args()
    execute(json.loads(args.manifest.read_text(encoding='utf8')))
