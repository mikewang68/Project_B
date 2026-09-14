import http.cookiejar,json,pathlib,urllib.request,urllib.error,urllib.parse,uuid,time

ROOT=pathlib.Path(__file__).resolve().parent.parent
class Client:
    def __init__(self,username='admin',base='http://127.0.0.1:18180',accounts=None):
        self.base=base;self.token=''
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        path=pathlib.Path(accounts) if accounts else ROOT/'.local/development-accounts.json'
        user=next(u for u in json.loads(path.read_text()) if u['username']==username)
        self.token=self.request('GET','/csrf')[1]['token']
        body=urllib.parse.urlencode({'username':username,'password':user['password']}).encode()
        self.request('POST','/login',body=body,ctype='application/x-www-form-urlencoded',expected=200)
        self.token=self.request('GET','/csrf')[1]['token']
    def request(self,method,path,data=None,body=None,ctype=None,expected=None):
        headers={}
        if method!='GET':headers['X-CSRF-TOKEN']=self.token
        if data is not None:body=json.dumps(data,ensure_ascii=False).encode();ctype='application/json'
        if ctype:headers['Content-Type']=ctype
        req=urllib.request.Request(self.base+'/api/v1'+path,data=body,headers=headers,method=method)
        try:r=self.opener.open(req,timeout=120)
        except urllib.error.HTTPError as e:r=e
        raw=r.read()
        value=json.loads(raw) if 'application/json' in r.headers.get('Content-Type','') else raw
        if expected is not None and r.status!=expected:raise AssertionError(f'{method} {path}: expected {expected}, got {r.status}: {str(value)[:400]}')
        return r.status,value
    def upload(self,name,bytes,path='/evidence'):
        boundary='Trust'+uuid.uuid4().hex
        body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+bytes+f'\r\n--{boundary}--\r\n'.encode()
        return self.request('POST',path,body=body,ctype='multipart/form-data; boundary='+boundary)
    def event(self,source_id=None,**overrides):
        e={'sourceSystem':'TEST','sourceEventId':source_id or uuid.uuid4().hex,'eventType':'ARRIVAL','businessObjectId':'STEEL-TEST','batchId':'TEST-'+uuid.uuid4().hex[:10],
           'occurredAt':'2026-09-10T01:00:00Z','bundleIds':[],'relatedBatchIds':[],'relatedEventRefs':[],'evidenceIds':[],'details':{},'quantity':100,'unit':'吨','location':'开发验证货场'}
        e.update(overrides);return e
    def wait(self,id,predicate=None,timeout=100):
        predicate=predicate or (lambda e:e['chain_state']=='COMMITTED')
        end=time.monotonic()+timeout;last={}
        while time.monotonic()<end:
            status,last=self.request('GET','/events/'+id)
            if status==200 and predicate(last):return last
            time.sleep(.5)
        raise AssertionError('Event did not reach expected state: '+str(last)[:1800])
