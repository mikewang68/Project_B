"""Routing/security unit checks; these do not claim real business E2E coverage."""
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

os.environ['BASE_DIR']=tempfile.mkdtemp(prefix='gateway-unit-')
os.environ['E12_SECRETS_FILE']=str(Path(os.environ['BASE_DIR'])/'absent.env')
spec=importlib.util.spec_from_file_location('gateway',Path(__file__).with_name('e21_bff.py'))
gateway=importlib.util.module_from_spec(spec);spec.loader.exec_module(gateway)

class Probe(gateway.LinuxGatewayHandler):
    def __init__(self,method,path,origin=None):
        self.command=method;self.path=path;self.headers={'Content-Length':'2'}
        if origin:self.headers['Origin']=origin
        self.rfile=io.BytesIO(b'{}');self.wfile=io.BytesIO();self.result=None;self.status=None
    def enforce_candidate_gate(self,path):return False
    def proxy_passthrough(self,*args,**kwargs):self.result=('native',args,kwargs)
    def forward_sso_request(self,*args,**kwargs):self.result=('sso',args,kwargs)
    def send_json(self,status,*args,**kwargs):self.status=status

class GatewayRoutesTest(unittest.TestCase):
    def test_psms_api_uses_identity_exchange_for_reads_and_writes(self):
        for method in ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']:
            with self.subTest(method=method):
                h = Probe(method, '/production-dispatch/api/plans?page=2')
                getattr(h, 'do_' + method)()
                self.assertEqual('sso', h.result[0])
                self.assertEqual('psms', h.result[1][0])
                self.assertEqual('/api/plans?page=2', h.result[1][2])

    def test_psms_patch_rejects_cross_origin_before_exchange(self):
        h = Probe('PATCH', '/production-dispatch/api/plans/PLAN-001', 'https://untrusted.example')
        h.do_PATCH()
        self.assertEqual(403, h.status)
        self.assertIsNone(h.result)
        self.assertEqual(b'', h.rfile.read())

    def test_emcs_keeps_api_prefix_for_every_supported_method(self):
        for method in ['GET','POST','PUT','DELETE']:
            with self.subTest(method=method):
                h=Probe(method,'/emcs-api/captchaImage?nonce=1')
                getattr(h,'do_'+method)()
                self.assertEqual('native',h.result[0]);self.assertEqual('/emcs-api/captchaImage?nonce=1',h.result[1][1])
                self.assertFalse(h.result[2].get('html_rewrite'))

    def test_scs_api_never_goes_to_static_server_and_keeps_query(self):
        for method in ['GET','POST','PUT','DELETE']:
            with self.subTest(method=method):
                h=Probe(method,'/safety/api/v1/fences?page=2')
                getattr(h,'do_'+method)()
                self.assertEqual('sso',h.result[0]);self.assertEqual('scs',h.result[1][0])
                self.assertEqual('/api/v1/fences?page=2',h.result[1][2])

    def test_cross_origin_native_writes_are_rejected_and_body_drained(self):
        for method in ['POST','PUT','DELETE']:
            h=Probe(method,'/emcs-api/login','https://evil127.0.0.1:'+str(gateway.BFF_PORT))
            getattr(h,'do_'+method)();self.assertEqual(403,h.status);self.assertEqual(b'',h.rfile.read())

    def test_same_origin_native_write_is_allowed(self):
        h=Probe('POST','/emcs-api/login','http://127.0.0.1:'+str(gateway.BFF_PORT))
        h.do_POST();self.assertEqual('native',h.result[0])

    def test_session_me_does_not_expose_iam_token(self):
        h=Probe('GET','/api/v1/portal/session/me')
        h.get_client_session=lambda:('unused',{'token':'offline-private-test-value','user':{},'roles':[], 'permissions':[], 'superAdmin':False})
        out=[];h.send_json=lambda status,data:out.append(data)
        h.do_GET();self.assertNotIn('token',out[0]['data'])

    def test_unconfigured_scs_fails_closed(self):
        h=Probe('POST','/safety/api/v1/fences')
        gateway.LinuxGatewayHandler.forward_sso_request(h,'scs','', '/api/v1/fences','/api/v1/auth/sso/exchange')
        self.assertEqual(503,h.status);self.assertEqual(b'',h.rfile.read())

    def test_registered_public_origin_is_allowed_and_other_origins_rejected(self):
        with patch.dict(os.environ, {'BFF_PUBLIC_ORIGIN':'https://portal.example.test'}):
            self.assertTrue(Probe('POST','/api/v1/iam/auth/login','https://portal.example.test').check_csrf())
            self.assertFalse(Probe('POST','/api/v1/iam/auth/login','https://other.example.test').check_csrf())

    def test_failed_global_logout_preserves_the_portal_session_for_retry(self):
        h=Probe('POST','/api/v1/portal/session/logout')
        h.get_client_session=lambda:('test-sid',{'token':'test-private-token'})
        with patch.object(gateway.urllib.request,'urlopen',side_effect=OSError('offline')),patch.object(gateway,'revoke_session') as revoke:
            h.handle_logout()
            self.assertEqual(503,h.status)
            revoke.assert_not_called()

if __name__=='__main__':unittest.main()
