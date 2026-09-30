"""Real isolated backend acceptance. IAM/business fixtures; real DB/IPFS/Fabric.

Run on application node, with the dedicated instance already configured.
Phases: setup (outputs policy for administrator), events, finish.
"""
import datetime, hashlib, http.cookiejar, io, json, pathlib, sys, time
import urllib.request, urllib.error, urllib.parse, uuid, zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / '.local/test-results/managed-real'
OUT.mkdir(parents=True, exist_ok=True)
BASE = 'http://127.0.0.1:28183'
config = dict(line.split('=', 1) for line in (ROOT/'runtime/secrets/isolated.env').read_text().splitlines() if '=' in line)
assert config['TRUST_DB_URL'].endswith('/trust_wallet_dev')
assert config['TRUST_FABRIC_CHANNEL'] == 'trust-wallet-dev'
cases = []

class Client:
    def __init__(self, user=None, password=None, org='B-PROJECT', service=False):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = ''
        self.service = service
        if user:
            self.csrf = self.call('GET', '/csrf')['token']
            self.call('POST', '/iam/login', {'username':user,'password':password,'orgId':org})
            self.csrf = self.call('GET', '/csrf')['token']

    def call(self, method, path, data=None, expected=200):
        headers = {'Content-Type':'application/json', 'X-CSRF-TOKEN':self.csrf}
        if self.service:
            headers['Authorization'] = 'Bearer '+(ROOT/'runtime/secrets/wms-token').read_text().strip()
        req = urllib.request.Request(BASE+'/api/v1'+path, data=None if data is None else json.dumps(data).encode(), headers=headers, method=method)
        try: response = self.opener.open(req, timeout=90)
        except urllib.error.HTTPError as e: response=e
        raw = response.read()
        value = json.loads(raw) if 'json' in response.headers.get('Content-Type','') else raw
        if response.status != expected:
            raise AssertionError(f'{method} {path}: expected {expected}, got {response.status}: {str(value)[:1200]}')
        return value

def record(id, evidence):
    cases.append({'id':id,'time':datetime.datetime.now(datetime.timezone.utc).isoformat(),'status':'PASS','evidence':evidence})
    (OUT/(sys.argv[1]+'-cases.json')).write_text(json.dumps(cases, ensure_ascii=False, indent=2))
    print(id, 'PASS', flush=True)

def propose(c, action, change):
    return c.call('POST','/wallets/requests',{'action':action,'change':change,'reason':'隔离验收：模拟身份与业务，真实组件'},200)['id']

def review(c,id,expected=200):
    return c.call('POST','/wallets/requests/'+id+'/review',{'approve':True},expected)

def event(id, kind='WAREHOUSE_IN', quantity=100, refs=None, batch=None):
    return {'sourceSystem':'WMS','sourceEventId':id,'eventType':kind,'businessObjectId':'SIMULATED-STEEL',
        'batchId':batch or state['batch'],'occurredAt':'2026-09-24T01:00:00Z' if kind=='WAREHOUSE_IN' else '2026-09-24T02:00:00Z',
        'quantity':quantity,'unit':'吨','location':'隔离模拟货场','bundleIds':[],'relatedBatchIds':[],
        'relatedEventRefs':refs or [],'evidenceIds':[],
        'details':{'companyCode':'C01','warehouseCode':'WH01','ownerCode':'O01','operatorId':'simulated-wms-operator-001','allocationId':'allocation-'+id,'simulation':'true'}}

def wait(id):
    deadline=time.monotonic()+100
    while time.monotonic()<deadline:
        d=operator.call('GET','/events/'+id)
        if d.get('chain_state')=='COMMITTED':return d
        time.sleep(.5)
    raise AssertionError(str(d))

def main():
    global applicant, reviewer, state, phase, operator, source
    applicant=Client('wallet-applicant','fixture-applicant')
    reviewer=Client('wallet-reviewer','fixture-reviewer')
    phase=sys.argv[1]
    if phase=='setup':
        material={'label':'真实签名隔离验收','mspId':'Org1MSP','keyRef':'org1-user1-v1'}
        same=Client('wallet-self-reviewer','fixture-selfreview')
        other=Client('other-org-reviewer','fixture-otherreview','ORG-B')
        no=Client('no-permission','fixture-noperm')
        id=propose(same,'REGISTER',material)
        record('AUTH-SELF',review(same,id,403))
        record('AUTH-CROSS-ORG',review(other,id,404))
        record('AUTH-NO-PERM',no.call('GET','/wallets',expected=403))
        review(reviewer,id)
        bind=propose(applicant,'BIND',{'walletId':id,'sourceSystem':'WMS','expectedRevision':0})
        review(reviewer,bind)
        policy=applicant.call('GET','/wallets/requests/'+bind+'/policy?revision=1')
        (OUT/'policy.json').write_text(json.dumps(policy))
        state={'wallet':id,'binding':bind,'batch':'SIM-'+uuid.uuid4().hex[:10],'prefix':'ACC-'+uuid.uuid4().hex[:10]}
        (OUT/'state.json').write_text(json.dumps(state))
        record('WALLET-APPROVED',{'wallet':id,'binding':bind,'policy':policy})
    else:
        state=json.loads((OUT/'state.json').read_text())
        operator=Client('business-operator','fixture-operator')
        source=Client(service=True)
        if phase=='events':
            parent=event(state['prefix']+'-IN')
            first=event(state['prefix']+'-D60','DISPATCH',60,['WMS:'+parent['sourceEventId']])
            second=event(state['prefix']+'-D40','DISPATCH',40,['WMS:'+parent['sourceEventId']])
            d60=source.call('POST','/integrations/wms/events',first,202)['id']
            gap=operator.call('GET','/trace?kind=EVENT&value='+urllib.parse.quote('WMS:'+first['sourceEventId']))
            assert 'WMS:'+parent['sourceEventId'] in gap['missingReferences'],gap
            record('WMS-CHILD-FIRST',gap)
            pin=source.call('POST','/integrations/wms/events',parent,202)['id']
            d40=source.call('POST','/integrations/wms/events',second,202)['id']
            duplicate=source.call('POST','/integrations/wms/events',parent,202)
            assert duplicate['id']==pin
            record('WMS-IDEMPOTENT',duplicate)
            record('WMS-CONFLICT',source.call('POST','/integrations/wms/events',dict(parent,quantity=99),409))
            bad=event(state['prefix']+'-BAD','DISPATCH',1,['OTHER:missing'])
            record('WMS-BAD-REFERENCE',source.call('POST','/integrations/wms/events',bad,400))
            bad=event(state['prefix']+'-BAD-SCOPE');bad['details']['companyCode']='forbidden'
            record('WMS-BAD-SCOPE',source.call('POST','/integrations/wms/events',bad,403))
            isolated=event(state['prefix']+'-UNRELATED')
            unrelated=source.call('POST','/integrations/wms/events',isolated,202)['id']
            for input,id in [(parent,pin),(first,d60),(second,d40)]:
                detail=wait(id)
                assert detail['wallet_id']==state['wallet'] and detail['tx_id']
                record('LEDGER-'+input['sourceEventId'],detail)
                trace=operator.call('GET','/trace?kind=EVENT&value='+urllib.parse.quote('WMS:'+input['sourceEventId']))
                assert {r['id'] for r in trace['items']}=={pin,d60,d40},trace
                assert not trace['missingReferences'],trace
                record('TRACE-'+input['sourceEventId'],trace)
                verified=operator.call('POST','/events/'+id+'/verify',{})
                assert verified['ok'] is True, verified
                assert detail['evidence']==[], 'Fixture must not invent evidence attachments'
                record('VERIFY-'+input['sourceEventId'],verified)
                blob=operator.call('POST','/events/'+id+'/export',{})
                with zipfile.ZipFile(io.BytesIO(blob)) as z: assert z.testzip() is None
                (OUT/(id+'.zip')).write_bytes(blob)
                record('EXPORT-'+input['sourceEventId'],{'size':len(blob),'sha256':hashlib.sha256(blob).hexdigest()})
            wait(unrelated)
            state.update({'events':[pin,d60,d40],'unrelated':unrelated,'inputs':[parent,first,second]})
            (OUT/'state.json').write_text(json.dumps(state))
        elif phase=='lifecycle':
            correction=dict(state['inputs'][0],sourceEventId=state['prefix']+'-IN-CORRECTION',location='隔离模拟货场（追加更正）')
            corrected=operator.call('POST','/events/'+state['events'][0]+'/corrections',correction,202)['id']
            d=wait(corrected)
            assert len(d['versions'])==2 and d['version']==2
            assert operator.call('POST','/events/'+corrected+'/verify',{})['ok']
            record('APPEND-CORRECTION',d)
            disabled=propose(applicant,'REGISTER',{'label':'停用拒绝验收','mspId':'Org2MSP','keyRef':'org2-disabled-test-v1'})
            review(reviewer,disabled)
            def bind_to(wallet):
                rev=next(b['revision'] for b in applicant.call('GET','/wallets')['bindings'] if b['source_system']=='WMS')
                req=propose(applicant,'BIND',{'walletId':wallet,'sourceSystem':'WMS','expectedRevision':rev})
                review(reviewer,req)
                return req
            bind_to(disabled)
            stop=propose(applicant,'DISABLE',{'walletId':disabled})
            review(reviewer,stop)
            blocked=source.call('POST','/integrations/wms/events',event(state['prefix']+'-DISABLED'),202)['id']
            try:
                end=time.monotonic()+20
                while time.monotonic()<end:
                    failed=operator.call('GET','/events/'+blocked)
                    if failed['chain_state']=='FAILED':break
                    time.sleep(.25)
                assert failed['chain_state']=='FAILED' and '停用' in failed['last_error'],failed
                assert not failed['tx_id'] and not failed['wallet_id'],failed
                assert not any(s['event_id']==blocked for s in applicant.call('GET','/wallets')['signatures'])
                record('DISABLED-NO-SIGNATURE',failed)
            finally:
                state['restoredBinding']=bind_to(state['wallet'])
            operator.call('POST','/events/'+blocked+'/retry',{})
            recovered=wait(blocked)
            assert recovered['wallet_id']==state['wallet']
            record('DISABLED-REBOUND-RETRY',recovered)
            # Re-running a committed task must reconcile the existing transaction.
            tx=recovered['tx_id']
            operator.call('POST','/events/'+blocked+'/retry',{})
            time.sleep(2)
            reconciled=wait(blocked)
            assert reconciled['tx_id']==tx
            assert len([s for s in applicant.call('GET','/wallets')['signatures'] if s['event_id']==blocked])==1
            record('COMMITTED-RETRY-NO-DUPLICATE',{'eventId':blocked,'txId':tx})
            state.update(correction=corrected,disabledWallet=disabled,recoveredEvent=blocked)
            (OUT/'state.json').write_text(json.dumps(state))
        elif phase=='finish':
            signatures=applicant.call('GET','/wallets')['signatures']
            for id in state['events']:
                rows=[s for s in signatures if s['event_id']==id and s['state']=='COMMITTED']
                assert len(rows)==1,rows
                context=json.loads(rows[0]['context_json'])
                assert context['submittedBy']=='source:wms-simulator'
                assert context['businessEvent']['details']['operatorId']=='simulated-wms-operator-001'
                assert context['bindingApproval']['requestId']==state['binding']
                assert context['registrationApproval']['proposedBy']!=context['registrationApproval']['reviewedBy']
                assert hashlib.sha256(rows[0]['context_json'].encode()).hexdigest()==rows[0]['context_sha256']
            record('SIGNING-AUDIT-CLOSED',signatures)
        else:raise ValueError(phase)


if __name__ == '__main__':
    main()
