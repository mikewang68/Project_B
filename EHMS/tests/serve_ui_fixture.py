"""UI component fixture, loopback only. No real backend, database, credentials, IAM, AI, or PLC connection.
Changes last only in memory. Do not use this harness as a production server or acceptance of persistence.
"""
import argparse,http.server,json,pathlib,mimetypes,urllib.parse,datetime,hashlib

CODE='SAMPLE-UI-GT-01';NOW='2026-11-10T10:00:00Z'
ASSETS=[dict(code=CODE,name='[虚拟]1号门式起重机',type='门吊',area='界面验收区',owner='设备保障班',condition='检修',health=71,risk='维护关注',riskClass='warn',quality=98,ready='虚拟演示',alarm='测试维护提醒',maintenanceDate='2026-11-12',updatedAt=NOW)]
FILE=b'SIMULATED UI fixture only. Not manufacturer maintenance instructions.'
DOC=dict(id='b847190f-79e0-4279-8dc1-f75def6848ca',title='[虚拟]设备维护说明',kind='MAINTENANCE',revision='UI-TEST-1',filename='simulated-manual.txt',contentType='text/plain',size=len(FILE),sha256=hashlib.sha256(FILE).hexdigest(),status='ACTIVE',createdAt=NOW)
DOSSIER=dict(assetCode=CODE,manufacturer='虚拟厂家（界面样例）',model='DEMO-GT',serialNumber='SIM-001',commissionedOn='2026-01-01',processPosition='装卸工位A（虚拟）',externalCode='VENDOR-SIM-01',notes='仅用于本地界面检查，不代表任何现场厂家参数。',maintenanceBasis=None,documents=[DOC],version=1,updatedAt=NOW)

class Fixture(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def answer(self,value,code=200,kind='application/json'):
        content=value if isinstance(value,bytes) else json.dumps(value,ensure_ascii=False).encode();self.send_response(code);self.send_header('Content-Type',kind);self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(content)
    def do_PUT(self):
        if self.path.endswith('/dossier'):
            data=json.loads(self.rfile.read(int(self.headers.get('Content-Length',0))))
            if data.get('version')!=DOSSIER['version']:return self.answer({'message':'界面样例版本冲突，请刷新'},409)
            DOSSIER.update({k:v for k,v in data.items() if k not in ('documents','assetCode')});DOSSIER['version']+=1;return self.answer(DOSSIER)
        self.answer({'message':'界面样例不执行此业务操作'},501)
    def do_POST(self):self.answer({'message':'界面样例不上传文件；真实文件上传由后端集成测试验证'},501)
    def do_DELETE(self):self.answer({'message':'界面样例不执行删除'},501)
    def do_GET(self):
        path=urllib.parse.urlsplit(self.path).path
        if path=='/api/ehm/v1/auth/me':return self.answer(dict(enabled=True,mode='local',user=dict(id='UI-FIXTURE',username='界面样例',displayName='界面样例',role='SUPER_ADMIN'),projectId='SIM_UI_FIXTURE',projectIds=['SIM_UI_FIXTURE'],permissions=[],readOnly=False))
        if path=='/api/ehm/v1/devices':return self.answer(dict(content=ASSETS,page=0,size=50,totalElements=len(ASSETS),totalPages=1))
        if path.endswith('/dossier'):return self.answer(DOSSIER)
        if path.endswith('/dossier/maintenance-due'):return self.answer(dict(status='UNCONFIGURED',message='界面样例：尚未登记维护依据，不自动套用其他设备周期。'))
        if path.endswith('/content'):return self.answer(FILE,kind='text/plain')
        if path=='/api/ehm/v1/dashboard/summary':return self.answer(dict(totalDevices=1,onlineDevices=1,onlineRate=100,healthyDevices=0,highRiskDevices=0,openAlarms=0,criticalOpenAlarms=0,activeWorkOrders=0,pendingWorkOrders=0,dataQuality=98))
        if path.endswith('/assistant/models'):return self.answer(dict(defaultModelId='lab-qwen',models=[dict(id='lab-qwen',label='本地界面样例',status=dict(ready=False,model='未调用模型'))]))
        if path.endswith('/assistant/status'):return self.answer(dict(ready=False,provider='界面样例',model='未调用',message='本地界面预览不调用任何模型'))
        if path.startswith('/api/'):
            if path.endswith(('/alarms','/work-orders')):return self.answer(dict(content=[],page=0,size=50,totalElements=0,totalPages=0))
            return self.answer([])
        name='index.html' if path=='/' else urllib.parse.unquote(path).lstrip('/');file=(self.server.web/name).resolve()
        if not file.is_relative_to(self.server.web) or not file.is_file():return self.send_error(404)
        content=file.read_bytes()
        if name=='index.html':content=content.replace(b'<body>',('<body><div style="height:32px;background:#fff4dc;color:#754800;padding:3px 16px;font:13px/26px sans-serif">本地界面样例 · 全部为虚拟数据 · 保存仅在内存 · 不连接真实服务、数据库或账号</div><style>.app-shell,.workspace,.sidebar{height:calc(100vh - 32px)}</style>').encode())
        self.answer(content,kind=mimetypes.guess_type(name)[0] or 'application/octet-stream')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--web',required=True,type=pathlib.Path);p.add_argument('--port',type=int,default=18394);a=p.parse_args();server=http.server.ThreadingHTTPServer(('127.0.0.1',a.port),Fixture);server.web=a.web.resolve();print('UI_FIXTURE_ONLY; NO_BACKEND_OR_CREDENTIALS',flush=True);server.serve_forever()
