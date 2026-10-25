import http.client
import http.server
import json
import threading
import unittest
import urllib.parse
from cross_domain_sso import CentralBridge, EdgeBridge, OneUseStore, Denied, challenge, reply


class StoreTests(unittest.TestCase):
    def test_one_use_and_binding(self):
        store=OneUseStore();token=store.put({'aud':'wms'})
        with self.assertRaises(Denied):store.consume(token,lambda r:r['aud']=='trust')
        self.assertEqual('wms',store.consume(token,lambda r:r['aud']=='wms')['aud'])
        with self.assertRaises(Denied):store.consume(token,lambda r:True)
    def test_expiry(self):
        store=OneUseStore(ttl=-1);token=store.put({})
        with self.assertRaises(Denied):store.consume(token,lambda r:True)
    def test_capacity_fails_closed(self):
        store=OneUseStore(capacity=1);store.put({})
        with self.assertRaises(Denied):store.put({})


class BrowserFlowTests(unittest.TestCase):
    def setUp(self):
        self.servers=[];self.exchanges=[];self.authorized=True;self.logged_in=True;self.minted=[];self.native_code=0
        owner=self
        class Native(http.server.BaseHTTPRequestHandler):
            def log_message(self,*_):pass
            def do_POST(self):
                data=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                owner.exchanges.append(data)
                reply(self,200,{'ok':True,'code':owner.native_code},cookies=['JSESSIONID=native-only; Path=/; HttpOnly'])
        native=self.serve(Native)
        class Edge(http.server.BaseHTTPRequestHandler):
            def log_message(self,*_):pass
            def do_GET(self):owner.edge.dispatch(self)
        edge=self.serve(Edge)
        class Central(http.server.BaseHTTPRequestHandler):
            def log_message(self,*_):pass
            def do_GET(self):owner.central.dispatch(self)
            def do_POST(self):owner.central.dispatch(self)
        central=self.serve(Central)
        self.edge_url=f'http://127.0.0.1:{edge.server_port}'
        self.central_url=f'http://127.0.0.1:{central.server_port}'
        self.secret='service-credential-for-tests-only-000000'
        def navigation(session):
            return {'systems':[{'appCode':'WMS','entryBaseUrl':self.edge_url+'/', 'entryBasePath':'/wms/',
                'children':[{'stableMenuKey':'WMS:balances','entryPath':'/wms/#/inventory/balances','canLaunch':self.authorized}]}]}
        def ticket(session,aud):self.minted.append(aud);return 'ticket-stays-on-server'
        self.central=CentralBridge({'wms':{'origin':self.edge_url,'credential':self.secret}},
            lambda h:{'token':'never-in-browser'} if self.logged_in else None,navigation,ticket,allow_http=True)
        self.edge=EdgeBridge('wms',self.edge_url,self.central_url,self.secret,
            f'http://127.0.0.1:{native.server_port}/exchange',allow_http=True)
    def serve(self,handler):
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),handler);self.servers.append(server)
        threading.Thread(target=server.serve_forever,daemon=True).start();return server
    def tearDown(self):
        for s in self.servers:s.shutdown();s.server_close()
    def request(self,url,cookie=None,body=None,headers=None):
        u=urllib.parse.urlsplit(url);conn=http.client.HTTPConnection(u.hostname,u.port,timeout=3)
        h=headers or {}
        if cookie:h={**h,'Cookie':cookie}
        if body is not None:h={**h,'Content-Type':'application/json'}
        conn.request('GET' if body is None else 'POST',u.path+('?' + u.query if u.query else ''),
            None if body is None else json.dumps(body),h)
        r=conn.getresponse();value=(r.status,dict(r.getheaders()),r.read());conn.close();return value
    def begin(self):
        status,headers,_=self.request(self.edge_url+'/sso/start?page=WMS%3Abalances')
        self.assertEqual(303,status)
        return headers['Location'],headers['Set-Cookie'].split(';')[0]
    def test_complete_browser_redirect_native_session(self):
        authorization,cookie=self.begin()
        status,headers,_=self.request(authorization);self.assertEqual(303,status)
        callback=headers['Location']
        self.assertNotIn('never-in-browser',callback);self.assertNotIn('ticket-stays',callback)
        status,headers,_=self.request(callback,cookie)
        self.assertEqual(303,status);self.assertEqual(self.edge_url+'/#/inventory/balances',headers['Location'])
        self.assertEqual([{'ticket':'ticket-stays-on-server'}],self.exchanges)
        self.assertEqual(['wms'],self.minted)
        self.assertEqual(401,self.request(callback,cookie)[0])
    def test_native_success_code_is_explicitly_registered(self):
        self.native_code='OK'
        authorization,cookie=self.begin();_,headers,_=self.request(authorization)
        self.assertEqual(502,self.request(headers['Location'],cookie)[0])
        self.edge.success_codes=('OK',)
        authorization,cookie=self.begin();_,headers,_=self.request(authorization)
        self.assertEqual(303,self.request(headers['Location'],cookie)[0])
    def test_missing_browser_transaction_rejected(self):
        authorization,_=self.begin();_,headers,_=self.request(authorization)
        self.assertEqual(401,self.request(headers['Location'])[0]);self.assertFalse(self.exchanges)
    def test_tampered_state_rejected(self):
        authorization,cookie=self.begin();_,headers,_=self.request(authorization)
        u=urllib.parse.urlsplit(headers['Location']);q=urllib.parse.parse_qs(u.query);q['state']=['x'*43]
        forged=urllib.parse.urlunsplit(u._replace(query=urllib.parse.urlencode(q,doseq=True)))
        self.assertEqual(403,self.request(forged,cookie)[0]);self.assertFalse(self.exchanges)
    def test_unavailable_function_cannot_launch(self):
        self.authorized=False;authorization,_=self.begin()
        self.assertEqual(403,self.request(authorization)[0]);self.assertFalse(self.minted)
    def test_anonymous_redirects_to_single_platform_login(self):
        self.logged_in=False;authorization,_=self.begin();status,headers,_=self.request(authorization)
        self.assertEqual(303,status);self.assertTrue(headers['Location'].startswith('/portal/?ssoReturn='))
    def test_service_credential_is_required(self):
        self.assertEqual(403,self.request(self.central_url+'/sso/code/exchange',body={'audience':'wms','code':'x'*43,'verifier':'v'*43})[0])
    def test_wrong_pkce_cannot_consume_code(self):
        authorization,_=self.begin();_,headers,_=self.request(authorization)
        code=urllib.parse.parse_qs(urllib.parse.urlsplit(headers['Location']).query)['code'][0]
        self.assertEqual(403,self.request(self.central_url+'/sso/code/exchange',body={'audience':'wms','code':code,'verifier':'v'*43},headers={'X-SSO-Service-Token':self.secret})[0])
        self.assertFalse(self.exchanges)


if __name__=='__main__':unittest.main()
