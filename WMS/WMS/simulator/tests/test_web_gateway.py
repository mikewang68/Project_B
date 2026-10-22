import copy
import http.client
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import simulator as sim
import web_gateway as web


class WebTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.cfg = json.loads((ROOT / 'config.example.json').read_text(encoding='utf-8'))
        self.cfg['state_dir'] = self.tmp.name
        self.cfg['target'].update(company_code='default', warehouse_code='default', owner_code='default')
        self.session = sim.Simulation(self.cfg)
        self.user = dict(id=10, username='operator', companyCode='default', permissions=['stockin:read', 'stockin:write'],
                         tenant=dict(currentWarehouse=dict(code='default'), currentOwner=dict(code='default')))
        self.orders = {o['id']: dict(id=o['id'], externalOrderCode=o['external_order_code'], orderCode='IN-'+str(o['id']),
            state='DRAFT', plannedQty=len(o['tags']), receivedQty=0,
            lines=[dict(id=o['line_id'], goodCode=o['good_code'], plannedQty=len(o['tags']), receivedQty=0,
                        preferredLocationCode=o['location_code'])]) for o in [self.cfg['order'], *self.cfg['additional_orders']]}
        self.posts = 0
        self.uncertain = False
        fixture = self
        class Client:
            def call(self, method, path, body=None):
                if path == '/api/v1/auth/me':
                    return copy.deepcopy(fixture.user)
                if path == '/api/v1/master-data/goods':
                    return [dict(code=o['good_code'], unit=o['unit'], status='ENABLED') for o in [fixture.cfg['order'], *fixture.cfg['additional_orders']]]
                if path == '/api/v1/stockin' and method == 'GET':
                    return list(copy.deepcopy(fixture.orders).values())
                if path == '/api/v1/stockin' and method == 'POST':
                    fixture.posts += 1
                    idx = max(fixture.orders)+1
                    line = body['lines'][0]
                    result = dict(id=idx, externalOrderCode=body['externalOrderCode'], orderCode='IN-'+str(idx), state='DRAFT',
                        plannedQty=line['plannedQty'], receivedQty=0, lines=[dict(id=idx, goodCode=line['goodCode'],
                        plannedQty=line['plannedQty'], receivedQty=0, preferredLocationCode=line['preferredLocationCode'])])
                    fixture.orders[idx] = result
                    if fixture.uncertain:
                        raise sim.RetryLater('lost acknowledgement')
                    return copy.deepcopy(result)
                return copy.deepcopy(fixture.orders[int(path.rsplit('/', 1)[1])])
        self.client = Client()
        def factory(cfg, cookie, csrf):
            if cookie.startswith('expired'):
                raise sim.WmsHttpError(401, 'AUTH_UNAUTHENTICATED')
            return self.client
        self.factory = factory
        self.gateway = web.WebGateway(self.session, factory)
        self.server = web.make_web_server(self.gateway, 0, {'test.local'}, allow_http=True)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def request(self, method='GET', path='/api/state', body=None, **headers):
        base = {'Host':'test.local', 'Cookie':'JSESSIONID=test; XSRF-TOKEN=token', 'Content-Type':'application/json',
                'X-XSRF-TOKEN':'token', 'Origin':'http://test.local'}
        base.update(headers)
        conn = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=10)
        conn.request(method, path, None if body is None else json.dumps(body), base)
        response = conn.getresponse()
        raw = response.read()
        status = response.status
        conn.close()
        return status, json.loads(raw) if response.getheader('Content-Type').startswith('application/json') else raw

    def spec(self, **extra):
        return dict(task_id=self.session.active_task, request_id='a'*32, material='SIM-STEEL-001', count=3, **extra)

    def test_anonymous_and_expired(self):
        self.assertEqual(self.request(Cookie='')[0], 401)
        self.assertEqual(self.request(Cookie='expired')[0], 401)
        self.assertEqual(self.posts, 0)

    def test_read_only_read_but_no_write_with_valid_csrf(self):
        self.user['permissions'] = ['stockin:read']
        status, state = self.request()
        self.assertEqual(status, 200)
        self.assertFalse(state['user']['can_write'])
        status, data = self.request('POST', '/api/batches', self.spec())
        self.assertEqual((status, data['code']), (403, 'AUTH_FORBIDDEN'))
        self.assertEqual(self.posts, 0)

    def test_wrong_scope_and_no_read(self):
        self.user['tenant']['currentWarehouse']['code'] = 'other'
        self.assertEqual(self.request()[1]['code'], 'SCOPE_FORBIDDEN')
        self.user['tenant']['currentWarehouse']['code'] = 'default'
        self.user['permissions'] = ['stockin:write']
        self.assertEqual(self.request()[0], 403)

    def test_readonly_role_blocks_delegated_writes_even_with_write_permission(self):
        # A valid session and CSRF are intentional: this is an authorization
        # rejection, not a CSRF failure or a missing stockin:write permission.
        self.assertIn('stockin:write', self.user['permissions'])
        for role in ('AUDITOR', 'readonly', 'ROLE_READONLY', 'READONLY_AUDITOR', 'PLATFORM_AUDITOR_READONLY'):
            with self.subTest(role=role):
                self.user['roles'] = [role]
                status, state = self.request()
                self.assertEqual(status, 200)
                self.assertFalse(state['user']['can_write'])
                body = self.spec()
                body.update(auto=True, auto_confirm=True, device='SIM-PDA-01', epc=self.cfg['order']['tags'][0],
                            selected=self.session.active_task, gross='2', tare='1')
                for endpoint in ('batches','task','scan','weigh','confirm','tick','flush','settings'):
                    status, data = self.request('POST', '/api/'+endpoint, body)
                    self.assertEqual((status, data['code']), (403, 'AUTH_READONLY'))
        self.assertEqual(self.posts, 0)
        self.assertEqual(self.session.snapshot()['events'], [])
        self.assertEqual(self.session.snapshot()['jobs'], [])
        self.assertFalse(self.session.auto)
        self.assertFalse(self.session.auto_confirm)

    def test_csrf_origin_host_and_duplicate_cookies(self):
        for header in ({'X-XSRF-TOKEN':''}, {'Origin':'https://evil.example'}, {'Host':'evil.example'},
                       {'Cookie':'JSESSIONID=test; XSRF-TOKEN=token; XSRF-TOKEN=other'}):
            self.assertIn(self.request('POST', '/api/batches', self.spec(), **header)[0], (400,403))
        self.assertEqual(self.posts, 0)

    def test_https_proxy_required(self):
        self.server.allow_http = False
        self.assertEqual(self.request()[0], 403)
        self.assertEqual(self.request(**{'X-Forwarded-Proto':'https'})[0], 200)

    def test_state_has_native_quantity_no_secrets(self):
        status, state = self.request()
        self.assertEqual(status, 200)
        self.assertEqual(state['actual']['receivedQty'], 0)
        text = json.dumps(state)
        for value in ('JSESSIONID', 'lab_approval_sha256', 'password_env'):
            self.assertNotIn(value, text)

    def test_stale_task_prevents_mutation(self):
        status, data = self.request('POST', '/api/scan', dict(task_id='old', device='SIM-PDA-01', epc='unknown'))
        self.assertEqual((status, data['code']), (409, 'STALE_TASK'))
        self.assertEqual(self.session.snapshot()['events'], [])

    def test_count_plan_idempotence_and_restart(self):
        spec = self.spec()
        self.assertEqual(self.request('POST', '/api/batches', spec)[0], 200)
        self.assertEqual(self.posts, 1)
        spec['task_id'] = self.session.active_task
        self.assertEqual(self.request('POST', '/api/batches', spec)[1]['created'], False)
        self.assertEqual(self.posts, 1)
        restored = sim.Simulation(self.cfg)
        web.WebGateway(restored, self.factory)
        self.assertIn('SIM-WEB-'+'a'*32, restored.engines)
        self.assertFalse(restored.auto)
        self.assertFalse(restored.auto_confirm)

    def test_changed_spec_or_actor_same_id_rejected(self):
        self.request('POST', '/api/batches', self.spec())
        spec = self.spec()
        spec['count'] = 4
        self.assertEqual(self.request('POST', '/api/batches', spec)[0], 400)
        self.user['id'] = 11
        spec['count'] = 3
        self.assertEqual(self.request('POST', '/api/batches', spec)[0], 400)
        self.assertEqual(self.posts, 1)

    def test_lost_creation_ack_only_reconciles(self):
        self.uncertain = True
        spec = self.spec()
        self.assertEqual(self.request('POST', '/api/batches', spec)[0], 409)
        self.assertEqual(self.request('POST', '/api/batches', spec)[0], 200)
        self.assertEqual(self.posts, 1)

    def test_missing_uncertain_order_never_posts_again(self):
        self.uncertain = True
        spec = self.spec()
        self.request('POST', '/api/batches', spec)
        self.orders.pop(max(self.orders))
        self.assertEqual(self.request('POST', '/api/batches', spec)[0], 409)
        self.assertEqual(self.posts, 1)

    def test_weight_plan_exact_and_count_limits(self):
        spec = self.spec()
        spec.update(material='SIM-FLYASH-BULK', weights=[dict(gross='32.555', tare='12.5')])
        spec.pop('count')
        self.assertEqual(self.request('POST', '/api/batches', spec)[0], 200)
        order = self.session.current.cfg['order']
        self.assertEqual(order['weighings'][order['tags'][0]], dict(gross='32.555', tare='12.5'))
        self.assertEqual(self.orders[max(self.orders)]['plannedQty'], 20.055)
        for n in (0,101,True,1.5):
            invalid = self.spec()
            invalid['count'] = n
            self.assertEqual(self.request('POST', '/api/batches', invalid)[0], 400)

    def test_only_approved_material_and_valid_weight(self):
        spec = self.spec()
        spec['material'] = 'REAL-STEEL'
        self.assertEqual(self.request('POST', '/api/batches', spec)[0], 400)
        spec.update(material='SIM-GRAVEL-001', weights=[dict(gross='2', tare='3')])
        self.assertEqual(self.request('POST', '/api/batches', spec)[0], 400)
        self.assertEqual(self.posts, 0)

    def test_unknown_endpoint_and_wrong_media(self):
        self.assertEqual(self.request(path='/api/credentials')[0],404)
        self.assertEqual(self.request('POST','/api/tick',dict(task_id=self.session.active_task), **{'Content-Type':'text/plain'})[0],415)

    def test_manual_scan_duplicate_and_switch_pauses_auto(self):
        body = dict(task_id=self.session.active_task, device='SIM-PDA-01', epc=self.cfg['order']['tags'][0])
        self.assertEqual(self.request('POST','/api/scan',body)[1]['status'],'SCANNED')
        self.assertEqual(self.request('POST','/api/scan',body)[1]['status'],'DUPLICATE')
        self.session.auto = self.session.auto_confirm = True
        self.request('POST','/api/task',dict(task_id=self.session.active_task,selected='SIM-INBOUND-CEMENT'))
        self.assertFalse(self.session.auto)
        self.assertFalse(self.session.auto_confirm)


if __name__ == '__main__':
    unittest.main()
