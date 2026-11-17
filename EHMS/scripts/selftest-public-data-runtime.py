"""Exercise the packaged CLI over a LOCAL fixture HTTP server and disposable H2 (never the public service)."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from urllib.parse import urlsplit,parse_qs

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT.parents[1]/'tools/jdk17-audit/microsoft/jdk-17.0.20.1+1/bin/java.exe'
STAGE=Path(json.loads((ROOT/'.local/public-data-package-state.json').read_text(encoding='utf-8'))['stage'])
H2=Path.home()/'.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar'

def main():
    run=Path(tempfile.mkdtemp(prefix='ehms-pd-smoke-',dir=ROOT/'.local'))
    url='jdbc:h2:file:'+run.joinpath('business').as_posix()
    cp=';'.join([str(STAGE/'bin/ehms-public-data-worker.jar'),str(STAGE/'lib/*'),str(H2)])
    def shell(sql):
        result=subprocess.run([str(JAVA),'-cp',cp,'org.h2.tools.Shell','-url',url,'-user','sa','-password','','-list','-sql',sql],check=True,capture_output=True,text=True)
        if 'Exception' in result.stdout:raise RuntimeError('LOCAL_FIXTURE_SQL_FAILED')
        return result.stdout
    shell("CREATE SCHEMA ehm; CREATE TABLE ehm.aggregate_store(project_id VARCHAR(64),region_id VARCHAR(64),subsystem_code VARCHAR(64),aggregate_type VARCHAR(64),aggregate_id VARCHAR(192),payload TEXT,updated_at TIMESTAMP WITH TIME ZONE,PRIMARY KEY(project_id,region_id,subsystem_code,aggregate_type,aggregate_id));")
    catalog=json.loads((STAGE/'mapping/owner-catalog.json').read_text(encoding='utf-8'))
    events={};token=uuid.uuid4().hex;stamp=int(time.time()*1000)
    for index,d in enumerate(catalog['devices']):
        point=next(p for p in d['fields'] if p.get('provenance',{}).get('wire_type')=='f32' and not p.get('missing_mapping'))
        event=dict(schema_version='b.device-event.v1',simulated=True,event_id='LOCAL-SELFTEST-'+str(index),device_id=d['device_id'],source_ts_ms=stamp,observed_ts_ms=stamp,
            payload=dict(points=[dict(point_code=point['point_code'],value=None if index==3 else 12.5,unit=point.get('unit'),quality=['GOOD','BAD','UNKNOWN',None][index])]))
        raw=json.dumps(event,separators=(',',':')).encode()
        events[d['device_id']]=dict(state='COMMITTED',event_id=event['event_id'],wire_sha256=hashlib.sha256(raw).hexdigest(),broker_message_id='LOCAL-ONLY-NO-BROKER',event=event)
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_GET(self):
            if self.headers.get('Authorization')!='Bearer '+token:self.send_error(401);return
            parsed=urlsplit(self.path);q=parse_qs(parsed.query)
            if parsed.path.endswith('/history'):
                receipt=events[q['device_id'][0]]
                response=dict(items=[] if q.get('cursor') else [dict(event_id=receipt['event_id'],wire_sha256=receipt['wire_sha256'],device_id=receipt['event']['device_id'],cursor='LOCAL-CURSOR-1')],has_more=False)
            elif parsed.path.endswith('/events'):response=next(r for r in events.values() if r['event_id']==q['event_id'][0])
            else:self.send_error(404);return
            raw=json.dumps(response).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    try:
        (run/'db-password').write_text('');(run/'http-token').write_text(token)
        config=json.loads((STAGE/'config/worker.config.example.json').read_text(encoding='utf-8'))
        config.update(enabled=True,jdbcUrl=url,dbUser='sa',dbPasswordFile=str(run/'db-password'),httpTokenFile=str(run/'http-token'),httpBase='http://127.0.0.1:'+str(server.server_port),catalogFile=str(STAGE/'mapping/owner-catalog.json'),allocationFile=str(STAGE/'mapping/owner-allocation.json'))
        private=run/'worker.private.json';private.write_text(json.dumps(config),encoding='utf-8')
        def cli(*args):
            result=subprocess.run([str(JAVA),'-cp',cp,'com.bproject.ehm.publicdata.PublicDataMain',str(private),*args],capture_output=True,text=True,timeout=90)
            if result.returncode:raise RuntimeError('PACKAGED_CLI_FAILED: '+result.stderr[-300:])
            return json.loads(result.stdout.strip().splitlines()[-1])
        cli('init');cli('http','60','100');before=cli('status');cli('http','60','100');after=cli('status')
        if before!=after:raise RuntimeError('LOCAL_REOPEN_DUPLICATION')
        output=shell("SELECT (SELECT count(*) FROM ehm.ehms_pd_inbox WHERE status='APPLIED') AS APPLIED,(SELECT count(*) FROM ehm.aggregate_store WHERE aggregate_type='asset') AS ASSETS,(SELECT count(*) FROM ehm.aggregate_store WHERE aggregate_type='measurement-point') AS POINTS,(SELECT count(*) FROM ehm.aggregate_store WHERE aggregate_type='committed-observation') AS OBSERVATIONS,(SELECT count(*) FROM ehm.aggregate_store WHERE aggregate_type='committed-telemetry') AS TELEMETRY;")
        counts={key:int(value) for key,value in re.findall(r'(APPLIED|ASSETS|POINTS|OBSERVATIONS|TELEMETRY)\s*:\s*(\d+)',output)}
        expected=dict(APPLIED=4,ASSETS=4,POINTS=410,OBSERVATIONS=4,TELEMETRY=4)
        if counts!=expected:raise RuntimeError('LOCAL_CLI_COUNT_MISMATCH: '+repr(counts))
        facts=dict(mode='LOCAL_FIXTURE_HTTP_AND_H2_ONLY',result='PASS',counts=counts,reopenDuplicateEffects=0,publicNetworkUsed=False,realMqConnected=False)
        (ROOT/'.local/public-data-runtime-smoke.json').write_text(json.dumps(facts,indent=2),encoding='utf-8');print(json.dumps(facts))
    finally:server.shutdown();server.server_close()
if __name__=='__main__':main()
