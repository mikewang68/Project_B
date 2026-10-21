"""Real localhost HTTP/process test, ONLY synthetic data + private H2. Never contacts lab/TRUST."""
import argparse
import datetime as dt
import hashlib
import http.server as http_server
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--h2-jar', required=True, type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    audit_jar = repo / 'AUDIT/service/target/audit-service-1.1.0.jar'
    ehm_jar = repo / 'EHMS/source/backend/target/ehm-demo-0.1.0.jar'
    for artifact in (audit_jar, ehm_jar, args.h2_jar):
        if not artifact.is_file():
            raise RuntimeError('Build both services first and provide the local test H2 jar')
    run = repo / 'AUDIT/.local' / ('http-e2e-' + uuid.uuid4().hex)
    run.mkdir(parents=True, mode=0o700)
    writer, reader, other, session, second, readonly = ('SYNTHETIC-' + secrets.token_hex(32) for _ in range(6))
    clients = run / 'clients.json'
    clients.write_text(json.dumps([
        dict(id='ehm-local', token=writer, appCode='ehealth', domainId='local-test', zoneId='zone-a', permissions=['WRITE']),
        dict(id='reader-local', token=reader, appCode='ehealth', domainId='local-test', zoneId='zone-a', permissions=['READ', 'EXPORT']),
        dict(id='other-local', token=other, appCode='ehealth', domainId='other-test', zoneId='zone-a', permissions=['READ']),
    ]), encoding='utf-8')
    token_file = run / 'writer.token'; token_file.write_text(writer, encoding='utf-8')
    reader_file = run / 'reader.token'; reader_file.write_text(reader, encoding='utf-8')
    if os.name != 'nt':
        clients.chmod(0o600); token_file.chmod(0o600)
    checks = []
    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        checks.append(dict(name=name, passed=True))
        print('PASS ' + name, flush=True)
    def port():
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); return sock.getsockname()[1]
    ehm_port, audit_port = port(), port()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def http(base, path, token=None, method='GET', payload=None, headers=None):
        hs = {'Accept': 'application/json', **(headers or {})}
        if token:
            hs['Authorization'] = 'Bearer ' + token
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode('utf-8')
        if data is not None:
            hs['Content-Type'] = 'application/json'
        req = urllib.request.Request(base + path, data=data, headers=hs, method=method)
        try:
            response = opener.open(req, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read(1048577)
            if len(raw) > 1048576:
                raise RuntimeError('Unexpected oversized local response')
            try:
                body = json.loads(raw) if raw else None
            except ValueError:
                body = None
            return response.code, body, response.headers, raw
    class Identity(http_server.BaseHTTPRequestHandler):
        def do_GET(self):
            token = self.headers.get('Authorization')
            if self.path != '/auth/me' or token not in ('Bearer ' + session, 'Bearer ' + second, 'Bearer ' + readonly):
                self.send_response(401); self.end_headers(); return
            data = json.dumps({'code': 0, 'data': {'user': {'id': 'local-u1' if token.endswith(session) else 'local-readonly' if token.endswith(readonly) else 'local-u2', 'status': 'active'}, 'roles': ['SUPER_ADMIN','READONLY_AUDITOR'] if token.endswith(readonly) else []}}).encode()
            self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
        def log_message(self, *unused):
            pass
    iam = http_server.ThreadingHTTPServer(('127.0.0.1', 0), Identity)
    threading.Thread(target=iam.serve_forever, daemon=True).start()
    env = {k: v for k, v in os.environ.items() if not k.startswith(('EHM_', 'AUDIT_', 'SPRING_')) and k not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'JAVA_OPTS')}
    processes, streams = [], []
    def launch(label, command, variables):
        log = (run / (label + '.log')).open('ab'); streams.append(log)
        child = subprocess.Popen(command, cwd=run, env={**env, **variables}, stdout=log, stderr=subprocess.STDOUT)
        processes.append(child); return child
    def stop(child):
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                child.kill(); child.wait(timeout=5)
    audit_base = 'http://127.0.0.1:' + str(audit_port)
    ehm_base = 'http://127.0.0.1:' + str(ehm_port)
    # Windows terminate also terminates the embedded database. Disable H2's delayed fsync for crash assertions.
    audit_env = dict(AUDIT_PORT=str(audit_port), AUDIT_BIND='127.0.0.1', AUDIT_CLIENTS_FILE=str(clients), AUDIT_AUTO_DDL='true', AUDIT_CHAIN_ENABLED='false', AUDIT_DB_URL='jdbc:h2:file:' + (run / 'audit-db').as_posix() + ';MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0', AUDIT_DB_USER='sa', AUDIT_DB_PASSWORD='')
    def start_audit():
        return launch('audit', [args.java, '-jar', str(audit_jar), '--spring.datasource.driver-class-name=org.h2.Driver'], audit_env)
    def ready(base, path, token, child):
        until = time.monotonic() + 60
        while time.monotonic() < until:
            if child.poll() is not None:
                raise RuntimeError('Local child failed; private logs are in AUDIT/.local')
            try:
                if http(base, path, token)[0] == 200:
                    return
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(.25)
        raise RuntimeError('Local service startup timed out')
    report = {'scope': 'LOCAL_REAL_HTTP_SYNTHETIC_H2_ONLY', 'chainEnabled': False, 'iam': 'LOCAL_STUB_NOT_REAL_SSO', 'checks': checks, 'passed': False}
    try:
        audit = start_audit(); ready(audit_base, '/api/logs/v1/whoami', reader, audit)
        ehm_env = dict(SPRING_PROFILES_ACTIVE='server', EHM_AUDIT_DOMAIN='local-test', EHM_AUDIT_ZONE='zone-a', EHM_AUDIT_ENDPOINT=audit_base + '/api/logs/v1/events', EHM_AUDIT_TOKEN_FILE=str(token_file), EHM_AUDIT_INTERVAL_MS='250', EHM_AUDIT_IAM_ME_URL='http://127.0.0.1:' + str(iam.server_port) + '/auth/me', EHM_AUDIT_IAM_REQUIRED='true', EHM_AUDIT_EVIDENCE_EXPORT_ENABLED='true', EHM_AUDIT_EVIDENCE_EXPORT_ACTORS='local-u1', EHM_OPENGAUSS_AUTO_DDL='true', EHM_OPENGAUSS_USERNAME='sa', EHM_OPENGAUSS_PASSWORD='', EHM_AI_ENABLED='false', EHM_OPENGEMINI_WRITE_ENABLED='false')
        ehm_env.update(EHM_AUTH_MODE='disabled',EHM_AUDIT_READER_TOKEN_FILE=str(reader_file),EHM_AUDIT_QUERY_ACTORS='local-u1')
        ehm = launch('ehm', [args.java, '-Dloader.path=' + str(args.h2_jar.resolve()), '-cp', str(ehm_jar), 'org.springframework.boot.loader.launch.PropertiesLauncher', '--server.address=127.0.0.1', '--server.port=' + str(ehm_port), '--spring.datasource.url=jdbc:h2:file:' + (run / 'ehm-db').as_posix() + ';MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0', '--spring.datasource.driver-class-name=org.h2.Driver', '--ehm.config.path=' + str(run / 'absent.json')], ehm_env)
        ready(ehm_base, '/health/live', None, ehm)
        prefix = '/api/ehm/v1'
        denied=http(ehm_base,prefix+'/devices',headers={'X-User-Id':'forged'})
        check('early401-has-trace-header',denied[0]==401 and bool(denied[2].get('X-Trace-Id')))
        veto=http(ehm_base,prefix+'/devices',readonly,'POST',{'code':'MUST-NOT-CREATE','name':'blocked'})
        check('trusted-readonly-overrides-superadmin',veto[0]==403 and bool(veto[2].get('X-Trace-Id')))
        check('readonly-export-hard-veto',http(ehm_base,prefix+'/system/audit-evidence/export',readonly)[0]==403)
        fixture = {'code': 'SYNTHETIC-01', 'name': 'Synthetic local equipment', 'area': 'local-area', 'health': 80}
        check('create-real-business-record', http(ehm_base, prefix + '/devices', session, 'POST', fixture)[0] == 201)
        check('read-and-page', http(ehm_base, prefix + '/devices?page=0&size=10', session)[0] == 200)
        fixture['name'] = 'Synthetic updated equipment'
        check('update-real-business-record', http(ehm_base, prefix + '/devices/SYNTHETIC-01', session, 'PUT', fixture)[0] == 200)
        check('validation-failure-is-not-success', http(ehm_base, prefix + '/devices', session, 'POST', {'name': ''})[0] == 400)
        check('forged-actor-header-is-not-authentication', http(ehm_base, prefix + '/devices', headers={'X-User-Id': 'local-u1'})[0] == 401)
        check('expired-session-denied', http(ehm_base, prefix + '/devices', 'SYNTHETIC-expired')[0] == 401)
        failed = http(ehm_base, prefix + '/devices/SYNTHETIC-01/health-assessments/run', session, 'POST')
        check('real-health-task-denotes-insufficient-data', failed[0] == 409 and bool(failed[2].get('X-EHM-Audit-Task-Id')))
        exported = http(ehm_base, prefix + '/system/audit-evidence/export', session)
        check('backend-evidence-generated-from-h2', exported[0] == 200 and exported[1]['databaseProduct'] == 'H2')
        check('export-byte-hash-and-terminal-state', hashlib.sha256(exported[3]).hexdigest() == exported[2].get('X-EHM-Evidence-Sha256') and exported[2].get('X-EHM-Audit-Task-State') == 'SUCCEEDED')
        check('nonallowlisted-user-cannot-export', http(ehm_base, prefix + '/system/audit-evidence/export', second)[0] == 403)
        tasks = http(ehm_base, prefix + '/system/audit-tasks', session)[1]['tasks']
        check('task-success-and-failure-persisted', {'SUCCEEDED', 'FAILED'}.issubset({t['state'] for t in tasks}))
        before = http(audit_base, '/api/logs/v1/events?size=100', reader)[1]['items']
        before_ids = {i['eventId'] for i in before}
        check('logs-present-before-audit-restart', bool(before_ids))
        stop(audit)
        fixture['name'] = 'Synthetic while AUDIT stopped'
        check('business-and-outbox-work-while-audit-is-offline', http(ehm_base, prefix + '/devices/SYNTHETIC-01', session, 'PUT', fixture)[0] == 200)
        pending = http(ehm_base, prefix + '/system/audit-delivery', session)[1]['recentEvents']
        offline_id = next(i['eventId'] for i in pending if i['action'] == 'UPDATE' and i['outcome'] == 'SUCCESS')
        audit = start_audit(); ready(audit_base, '/api/logs/v1/whoami', reader, audit)
        check('logical-delete-real-record', http(ehm_base, prefix + '/devices/SYNTHETIC-01', session, 'DELETE')[0] == 204)
        check('deleted-record-filtered', http(ehm_base, prefix + '/devices/SYNTHETIC-01', session)[0] == 404)
        items = []
        for _ in range(80):
            items = http(audit_base, '/api/logs/v1/events?size=100', reader)[1]['items']
            actions = {item['action'] for item in items}
            if offline_id in {i['eventId'] for i in items} and {'CREATE', 'READ', 'UPDATE', 'DELETE', 'EXPORT'}.issubset(actions) and any(i['outcome'] == 'DENIED' for i in items):
                break
            time.sleep(.25)
        check('crud-export-and-denial-reach-common-service-over-http', {'CREATE', 'READ', 'UPDATE', 'DELETE', 'EXPORT'}.issubset({i['action'] for i in items}) and any(i['outcome'] == 'DENIED' for i in items))
        check('specific-offline-event-is-retransmitted', offline_id in {i['eventId'] for i in items})
        page=http(ehm_base,prefix+'/system/audit-events?action=UPDATE&size=1',session)
        check('journal-paging-and-action-filter-over-http',page[0]==200 and page[1]['total']>=1 and len(page[1]['items'])==1 and page[1]['items'][0]['action']=='UPDATE')
        check('journal-user-scope-denies-cross-user',http(ehm_base,prefix+'/system/audit-events?userId=local-u1',second)[0]==403)
        proof=http(ehm_base,prefix+'/system/audit-events/'+offline_id+'/receipt',session)
        check('journal-live-receipt-hash-verified-not-chain-confirmed',proof[0]==200 and proof[1]['lookupState']=='VERIFIED_PUBLIC_RECEIPT' and proof[1]['chainState']=='NOT_CONFIGURED')
        check('common-service-logs-survive-process-restart', all(http(audit_base, '/api/logs/v1/events/' + event_id + '/receipt', reader)[0] == 200 for event_id in before_ids))
        check('trusted-and-anonymous-actors-are-not-fabricated', any(i['userId'] == 'local-u1' and i['actorTrust'] == 'AUTHENTICATED' for i in items) and any(i['userId'] == 'anonymous' and i['outcome'] == 'DENIED' for i in items))
        check('no-fake-chain-confirmation', all(i['receipt']['chainState'] == 'NOT_CONFIGURED' for i in items))
        event = dict(schemaVersion=1, eventId='SYNTHETIC-IDEMPOTENT', occurredAt='2026-01-01T00:00:00Z', appCode='ehealth', domainId='local-test', zoneId='zone-a', userId='local-u1', actorTrust='AUTHENTICATED', menuId='asset-tree', action='READ', objectId='synthetic', outcome='SUCCESS', method='GET', path='/synthetic', traceId='', durationMs=1, sourceKind='REQUEST', summary='Synthetic local test only')
        first = http(audit_base, '/api/logs/v1/events', writer, 'POST', event)
        replay = http(audit_base, '/api/logs/v1/events', writer, 'POST', event)
        check('idempotent-same-event-over-http', first[0] == replay[0] == 202 and first[1]['payloadHash'] == replay[1]['payloadHash'])
        check('same-id-different-body-is409', http(audit_base, '/api/logs/v1/events', writer, 'POST', {**event, 'summary': 'Changed content'})[0] == 409)
        check('read-only-service-cannot-write', http(audit_base, '/api/logs/v1/events', reader, 'POST', event)[0] == 403)
        check('cross-domain-receipt-isolated', http(audit_base, '/api/logs/v1/events/SYNTHETIC-IDEMPOTENT/receipt', other)[0] == 404)
        stop(ehm)
        # The EHM process is restarted against the SAME private H2 database and file-backed queue.
        command = processes[1].args
        ehm = launch('ehm-restarted', command, ehm_env); ready(ehm_base, '/health/live', None, ehm)
        persisted = http(ehm_base, prefix + '/system/audit-tasks', session)[1]['tasks']
        check('ehm-task-journal-survives-process-restart', any(t['taskId'] == exported[2].get('X-EHM-Audit-Task-Id') and t['state'] == 'SUCCEEDED' for t in persisted))
        report['passed'] = True
    finally:
        for child in reversed(processes):
            stop(child)
        iam.shutdown(); iam.server_close()
        for stream in streams:
            stream.close()
        report['checkedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
        report['artifacts'] = {str(p.relative_to(repo)).replace('\\', '/'): hashlib.sha256(p.read_bytes()).hexdigest() for p in (audit_jar, ehm_jar)}
        (run / 'sanitized-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Local HTTP integration PASS; sanitized-report.json is under AUDIT/.local/http-e2e-*', flush=True)


if __name__ == '__main__':
    main()
