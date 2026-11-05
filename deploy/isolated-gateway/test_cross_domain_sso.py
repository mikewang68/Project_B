import http.client
import http.server
import json
import threading
import unittest
from unittest.mock import patch
import urllib.parse
from cross_domain_sso import CentralBridge, EdgeBridge, OneUseStore, Denied, challenge, reply
from cross_domain_sso import DatabaseAddressSource


class PublishedAddressResponseTests(unittest.TestCase):
    def test_invalid_address_service_payloads_fail_closed(self):
        source=DatabaseAddressSource('http://127.0.0.1:19080/address',{'wms':'test-only-service-credential-000000'})
        valid={'appCode':'WMS','version':1,'baseUrl':'https://warehouse.example.test/','origin':'https://warehouse.example.test'}
        payloads=[[],{'code':0,'data':[]},{'code':0,'data':{**valid,'version':True}},
            {'code':0,'data':{**valid,'baseUrl':123}},
            {'code':0,'data':{**valid,'origin':'invalid'}},
            {'code':0,'data':{**valid,'baseUrl':'https://warehouse.example.test:bad/'}},
            {'code':0,'data':{**valid,'baseUrl':'https://warehouse.example.test/../'}}]
        for payload in payloads:
            with self.subTest(payload=payload),patch('cross_domain_sso.request_json',return_value=(payload,[])):
                with self.assertRaises(Denied)as failure:source('wms')
                self.assertEqual(503,failure.exception.status)


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

    def configure_iam(self):
        self.central.apps = {'iam': self.central.apps['wms']}
        self.edge.audience = 'iam'
        self.central.navigation = lambda session: {'systems': [{
            'appCode': 'IAM', 'entryBaseUrl': self.edge_url+'/', 'entryBasePath': '/iam/',
            'children': [{'stableMenuKey': 'IAM:scope-management',
                'entryPath': '/iam/system/scope-management?tab=users', 'canLaunch': self.authorized}]}]}

    def begin_iam(self, suffix=''):
        status, headers, _ = self.request(self.edge_url+'/sso/start?page=IAM%3Ascope-management'+suffix)
        self.assertEqual(303, status)
        return headers['Location'], headers['Set-Cookie'].split(';')[0]

    def test_iam_project_context_preserves_authorized_target_and_stays_out_of_native_ticket_request(self):
        self.configure_iam()
        authorization, cookie = self.begin_iam('&projectId=PROJECT_B')
        self.assertEqual(['PROJECT_B'], urllib.parse.parse_qs(urllib.parse.urlsplit(authorization).query)['projectId'])
        status, headers, _ = self.request(authorization)
        self.assertEqual(303, status)
        status, headers, _ = self.request(headers['Location'], cookie)
        self.assertEqual(303, status)
        self.assertEqual(self.edge_url+'/system/scope-management?tab=users&projectId=PROJECT_B', headers['Location'])
        self.assertEqual([{'ticket': 'ticket-stays-on-server'}], self.exchanges)
        self.assertEqual(['iam'], self.minted)

    def test_iam_without_project_hint_keeps_global_entry_unchanged(self):
        self.configure_iam()
        authorization, cookie = self.begin_iam()
        _, headers, _ = self.request(authorization)
        status, headers, _ = self.request(headers['Location'], cookie)
        self.assertEqual(303, status)
        self.assertEqual(self.edge_url+'/system/scope-management?tab=users', headers['Location'])

    def test_selected_project_replaces_conflicting_registered_workspace_hint(self):
        self.configure_iam()
        navigation = self.central.navigation
        def with_old_hint(session):
            tree = navigation(session)
            tree['systems'][0]['children'][0]['entryPath'] += '&workspace=global&projectId=OLD_PROJECT'
            return tree
        self.central.navigation = with_old_hint
        authorization, cookie = self.begin_iam('&projectId=PROJECT_B')
        _, headers, _ = self.request(authorization)
        status, headers, _ = self.request(headers['Location'], cookie)
        self.assertEqual(303, status)
        self.assertEqual(self.edge_url+'/system/scope-management?tab=users&projectId=PROJECT_B', headers['Location'])

    def test_explicit_iam_global_workspace_survives_sso_and_replaces_registered_project(self):
        self.configure_iam()
        navigation = self.central.navigation
        def with_old_hint(session):
            tree = navigation(session)
            tree['systems'][0]['children'][0]['entryPath'] += '&projectId=OLD_PROJECT'
            return tree
        self.central.navigation = with_old_hint
        authorization, cookie = self.begin_iam('&workspace=global')
        self.assertEqual(['global'], urllib.parse.parse_qs(urllib.parse.urlsplit(authorization).query)['workspace'])
        _, headers, _ = self.request(authorization)
        status, headers, _ = self.request(headers['Location'], cookie)
        self.assertEqual(303, status)
        self.assertEqual(self.edge_url+'/system/scope-management?tab=users&workspace=global', headers['Location'])
        self.assertEqual([{'ticket':'ticket-stays-on-server'}], self.exchanges)

    def test_workspace_hint_is_iam_only_global_only_and_exclusive_with_project(self):
        self.assertEqual(400, self.request(self.edge_url+'/sso/start?page=WMS%3Abalances&workspace=global')[0])
        authorization, _ = self.begin()
        self.assertEqual(400, self.request(authorization+'&workspace=global')[0])
        self.configure_iam()
        for suffix in ('&workspace=', '&workspace=all', '&workspace=global&workspace=global',
                       '&workspace=global&projectId=PROJECT_B'):
            with self.subTest(suffix=suffix):
                self.assertEqual(400, self.request(self.edge_url+'/sso/start?page=IAM%3Ascope-management'+suffix)[0])
        authorization, _ = self.begin_iam('&workspace=global')
        self.assertEqual(400, self.request(authorization+'&projectId=PROJECT_B')[0])
        self.assertFalse(self.minted)

    def test_global_workspace_removed_in_transit_cannot_establish_native_session(self):
        self.configure_iam()
        authorization, cookie = self.begin_iam('&workspace=global')
        status, headers, _ = self.request(authorization.replace('&workspace=global',''))
        self.assertEqual(303, status)
        self.assertEqual(403, self.request(headers['Location'], cookie)[0])
        self.assertFalse(self.exchanges)

    def test_project_context_is_rejected_for_business_audiences(self):
        self.assertEqual(400, self.request(self.edge_url+'/sso/start?page=WMS%3Abalances&projectId=PROJECT_B')[0])
        authorization, _ = self.begin()
        self.assertEqual(400, self.request(authorization+'&projectId=PROJECT_B')[0])
        self.assertFalse(self.minted)

    def test_invalid_or_duplicate_project_context_fails_before_transaction(self):
        self.configure_iam()
        for suffix in ('&projectId=', '&projectId=PROJECT_B&projectId=PROJECT_A',
                       '&projectId=%27OR%201%3D1', '&projectId=../PROJECT_B', '&projectId='+'A'*65):
            with self.subTest(suffix=suffix):
                self.assertEqual(400, self.request(self.edge_url+'/sso/start?page=IAM%3Ascope-management'+suffix)[0])
        self.assertFalse(self.edge.transactions.values)
        self.assertFalse(self.minted)

    def test_project_hint_cannot_make_an_unauthorized_menu_available(self):
        self.configure_iam(); self.authorized = False
        authorization, _ = self.begin_iam('&projectId=PROJECT_B')
        self.assertEqual(403, self.request(authorization)[0])
        self.assertFalse(self.minted)

    def test_project_hint_changed_or_removed_in_transit_cannot_establish_native_session(self):
        self.configure_iam()
        for replacement in ('&projectId=PROJECT_A', ''):
            with self.subTest(replacement=replacement):
                authorization, cookie = self.begin_iam('&projectId=PROJECT_B')
                tampered = authorization.replace('&projectId=PROJECT_B', replacement)
                status, headers, _ = self.request(tampered)
                self.assertEqual(303, status)
                self.assertEqual(403, self.request(headers['Location'], cookie)[0])
        self.assertFalse(self.exchanges)

    def test_duplicate_hint_at_central_is_rejected(self):
        self.configure_iam()
        authorization, _ = self.begin_iam('&projectId=PROJECT_B')
        self.assertEqual(400, self.request(authorization+'&projectId=PROJECT_A')[0])
        self.assertFalse(self.minted)

    def test_iam_canonical_address_redirect_retains_project_context(self):
        self.configure_iam()
        self.edge.address_provider = lambda audience: {'origin':self.edge_url,'version':1}
        status, headers, _ = self.request(self.edge_url+'/sso/start?page=IAM%3Ascope-management&projectId=PROJECT_B',
                                         headers={'Host':'old.example.test'})
        self.assertEqual(303, status)
        self.assertEqual(self.edge_url+'/sso/start?page=IAM%3Ascope-management&projectId=PROJECT_B', headers['Location'])

    def dynamic_addresses(self):
        self.address_version = 1
        def published(audience, version=None):
            selected = self.address_version if version is None else version
            if selected not in (1, 2):
                from cross_domain_sso import Denied
                raise Denied(409, 'Address version expired')
            return {'appCode':'WMS', 'version':selected, 'origin':self.edge_url,
                'baseUrl':self.edge_url+('/' if selected == 1 else '/updated/')}
        self.edge.address_provider = published
        self.central.address_provider = published

    def test_published_address_change_does_not_retarget_an_inflight_login(self):
        self.dynamic_addresses()
        authorization,cookie = self.begin()
        self.assertEqual(['1'], urllib.parse.parse_qs(urllib.parse.urlsplit(authorization).query)['addressVersion'])
        self.address_version = 2
        _,headers,_ = self.request(authorization)
        status,headers,_ = self.request(headers['Location'],cookie)
        self.assertEqual(303,status)
        self.assertEqual(self.edge_url+'/#/inventory/balances',headers['Location'])

    def test_new_logins_use_published_address_without_restarting_bridges(self):
        self.dynamic_addresses();self.address_version = 2
        authorization,cookie = self.begin()
        _,headers,_ = self.request(authorization)
        status,headers,_ = self.request(headers['Location'],cookie)
        self.assertEqual(303,status)
        self.assertEqual(self.edge_url+'/updated/#/inventory/balances',headers['Location'])

    def test_address_source_failure_cannot_fall_back_to_stale_static_origin(self):
        from cross_domain_sso import Denied
        def unavailable(*_):raise Denied(503,'Address service unavailable')
        self.edge.address_provider = unavailable
        self.assertEqual(503,self.request(self.edge_url+'/sso/start?page=WMS%3Abalances')[0])
        self.assertFalse(self.minted)

    def test_readiness_proves_application_key_without_sending_it_to_the_candidate(self):
        import base64,hashlib,hmac
        nonce='readiness-challenge-only-0001'
        status,_,body=self.request(self.edge_url+'/sso/readiness?challenge='+nonce)
        self.assertEqual(200,status)
        value=json.loads(body)
        expected=base64.urlsafe_b64encode(hmac.new(self.secret.encode(),('wms\n'+nonce).encode(),hashlib.sha256).digest()).decode().rstrip('=')
        self.assertEqual(expected,value['proof'])
        self.assertNotIn(self.secret,body.decode())


if __name__=='__main__':unittest.main()
