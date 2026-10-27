"""One-command Android C -> separate gateway -> local SQLite/HTTP -> exact readback.

Emulator only. Refuses an existing scanner queue or running service. Never opens
a personal phone camera, sends public-base requests, or calls WMS receipts.
"""
import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import uuid
import xml.etree.ElementTree as ET
import gateway as g
from local_demo_client import LocalClient

ROOT = Path(__file__).resolve().parent


def unused(port):
    with socket.socket() as sock:
        sock.settimeout(.2)
        return sock.connect_ex(('127.0.0.1', port)) != 0


class FaultProxy:
    """Explicit transport fault injection after reading an actual C scale frame."""
    def __init__(self, port, scenario):
        self.upstream, self.scenario = port, scenario
        self.stop = threading.Event()
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', 0))
        self.listener.listen(1)
        self.listener.settimeout(.25)
        self.port = self.listener.getsockname()[1]
        self.evidence = {'scope': 'local-demo transport fault; not device firmware behavior', 'scenario': scenario}
        self.thread = threading.Thread(target=self.work, daemon=True)
        self.thread.start()

    def work(self):
        try:
            while not self.stop.is_set():
                try:
                    conn, _ = self.listener.accept()
                    break
                except socket.timeout:
                    pass
            else:
                return
            with conn, socket.create_connection(('127.0.0.1', self.upstream), timeout=5) as source:
                raw = g.exact(source, 18)
                self.evidence['C_original_hex'] = raw.hex()
                self.evidence['C_original_sha256'] = hashlib.sha256(raw).hexdigest()
                if self.scenario == 'bad-checksum':
                    corrupt = raw[:-1] + bytes([raw[-1] ^ 1])
                    self.evidence['gateway_received_hex'] = corrupt.hex()
                    conn.sendall(corrupt)
                else:
                    self.evidence['gateway_received_hex'] = None
                    self.evidence['withheld_response'] = True
                    self.stop.wait(7)
        except Exception as exc:
            self.evidence['error'] = type(exc).__name__ + ': ' + str(exc)

    def close(self):
        self.stop.set()
        self.thread.join(timeout=8)
        self.listener.close()
        if self.thread.is_alive():
            raise RuntimeError('fault proxy did not stop')


class Demo:
    def __init__(self, args):
        self.args, self.output = args, args.output.resolve()
        self.adb = [args.adb, '-s', args.serial]
        self.forwards = {}
        self.proxy = None
        self.store = None
        self.store_log = None
        self.service_started = False
        self.original_tab = '扫码'
        self.manifest = {'scope': 'local-demo-only', 'scenario': args.scenario,
                         'Android_serial': args.serial, 'personal_phone_touched': False,
                         'public_base': False, 'server_WMS_accepted': False}

    def cmd(self, *parts):
        return subprocess.check_output(self.adb + list(parts), text=True, encoding='utf-8', errors='replace', timeout=20)

    def ui(self):
        self.cmd('shell', 'uiautomator', 'dump', '/sdcard/wms-local-demo.xml')
        return ET.fromstring(self.cmd('shell', 'cat', '/sdcard/wms-local-demo.xml'))

    def tap(self, label):
        for _ in range(3):
            for node in self.ui().iter('node'):
                if node.get('text') == label:
                    a,b,c,d = map(int, re.findall(r'\d+', node.get('bounds', '')))
                    if c>a and d>b:
                        self.cmd('shell', 'input', 'tap', str((a+c)//2), str((b+d)//2))
                        return
            time.sleep(.2)
        raise ValueError('visible action not found: ' + label)

    def live(self):
        texts = [n.get('text', '') for n in self.ui().iter('node')]
        row = next((s for s in texts if '待发扫码 ' in s), '')
        match = re.search(r'待发扫码 (\d+)', row)
        if not match:
            raise ValueError('cannot determine queue state')
        return row, int(match.group(1))

    def prepare(self):
        if self.cmd('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
            raise ValueError('emulator only: personal phones are refused before any activity/UI action')
        self.cmd('shell', 'am', 'start', '-W', '-n', 'cn.projectb.terminal/.MainActivity')
        text = [n.get('text', '') for n in self.ui().iter('node')]
        for title, tab in [('汽车衡仪表','称重'),('筒仓、拆箱房、空气斜槽','工艺'),('连接网关','连接')]:
            if title in text:
                self.original_tab = tab
        live, count = self.live()
        if '已停止' not in live or count:
            raise ValueError('refusing running service or existing queue; preserve it and use an idle emulator')
        self.tap('称重')
        weigh = self.ui()
        self.manifest['weighing_inputs'] = [n.get('text') for n in weigh.iter('node') if n.get('class') == 'android.widget.EditText']
        self.manifest['weighing_flags'] = {n.get('text'): n.get('checked') == 'true' for n in weigh.iter('node') if n.get('class') == 'android.widget.CheckBox'}
        self.tap('工艺')
        process = self.ui()
        self.manifest['process_inputs'] = [n.get('text') for n in process.iter('node') if n.get('class') == 'android.widget.EditText']
        self.manifest['process_flags'] = {n.get('text'): n.get('checked') == 'true' for n in process.iter('node') if n.get('class') == 'android.widget.CheckBox'}
        self.tap('扫码')
        if self.args.scenario == 'normal':
            tag = next((n.get('text') for n in self.ui().iter('node') if n.get('class') == 'android.widget.EditText'), None)
            if tag != 'SIM-STEEL-B001':
                raise ValueError('select SIM-STEEL-B001 before normal demo; existing selection is not overwritten')
        self.manifest['initial_live'] = live
        package = self.cmd('shell', 'pm', 'path', 'cn.projectb.terminal').strip().splitlines()
        if not package:
            raise ValueError('install Android terminal 0.2.0 first')
        self.manifest['installed_apk_sha256'] = self.cmd('shell', 'sha256sum', package[0].removeprefix('package:')).split()[0]
        self.write_manifest()

    def write_manifest(self):
        (self.output / 'manifest.json').write_text(json.dumps(self.manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    def start(self):
        self.store_log = (self.output / 'local-store.log').open('w', encoding='utf-8')
        self.store = subprocess.Popen([sys.executable, str(ROOT/'local_demo_store.py'), '--db', str(self.output/'local-events.sqlite3'),
                                       '--manifest', str(self.output/'local-store-manifest.json'), '--stop-on-stdin'],
                                      stdin=subprocess.PIPE, stdout=self.store_log, stderr=subprocess.STDOUT)
        deadline = time.monotonic()+10
        while not (self.output/'local-store-manifest.json').exists():
            if self.store.poll() is not None or time.monotonic()>deadline:
                raise RuntimeError('local store failed to start; see local-store.log')
            time.sleep(.05)
        self.store_url = json.loads((self.output/'local-store-manifest.json').read_text())['url']
        self.manifest['local_store_url'] = self.store_url
        if self.args.scenario == 'normal':
            self.tap('读取标签并保存')
            if self.live()[1] != 1:
                raise ValueError('test scan was not saved; existing state preserved')
        self.tap('连接')
        self.tap('启动 USB／仅本机服务')
        self.service_started = True
        names = list(g.DEVICES) if self.args.scenario == 'normal' else ['scale']
        for name in names:
            self.forwards[name] = int(self.cmd('forward', 'tcp:0', 'tcp:'+str(g.DEVICES[name][2])).strip())
        if self.args.scenario != 'normal':
            self.proxy = FaultProxy(self.forwards['scale'], self.args.scenario)
        self.manifest['adb_forward_ports'] = self.forwards
        self.write_manifest()

    def run(self):
        self.prepare()
        self.start()
        run_id = 'local-demo-'+uuid.uuid4().hex[:16]
        readbacks = []
        channels = {}
        for device, port in self.forwards.items():
            dest = self.output/'gateway'/device
            acquisition_port = self.proxy.port if self.proxy else port
            completed = subprocess.run([sys.executable,str(ROOT/'gateway.py'),'--device',device,'--host','127.0.0.1',
                                        '--port',str(acquisition_port),'--count','1','--run-id',run_id,'--output',str(dest)],
                                       capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=20)
            (self.output/(device+'-gateway.log')).write_text(completed.stdout+completed.stderr,encoding='utf-8')
            if completed.returncode and self.args.scenario == 'normal':
                raise RuntimeError('gateway failed; saved evidence and any pending test scan are preserved')
            if not completed.returncode and self.args.scenario != 'normal':
                raise AssertionError('fault injection did not cause acquisition failure')
            events = [json.loads(line) for line in (dest/'events.jsonl').read_text(encoding='utf-8').splitlines()]
            if len(events) != 1:
                raise AssertionError('expected one bounded event')
            event = events[0]
            points = {p['point_code']:p for p in event['payload']['points']}
            if self.args.scenario != 'normal':
                if not all(p['value'] is None and p['quality_code']=='BAD' for p in points.values()):
                    raise AssertionError('fault must remain null/BAD')
                if self.proxy and self.proxy.evidence.get('error'):
                    raise RuntimeError(self.proxy.evidence['error'])
                if self.args.scenario == 'bad-checksum':
                    with closing(sqlite3.connect((dest/'gateway.sqlite3').as_uri()+'?mode=ro',uri=True)) as db:
                        raw = json.loads(db.execute('SELECT raw FROM events').fetchone()[0])
                    if raw[0].get('response_hex') != self.proxy.evidence['gateway_received_hex']:
                        raise AssertionError('damaged frame lost from acquisition evidence')
                self.proxy.evidence['event_id'] = event['event_id']
            if self.args.scenario == 'normal':
                self.verify_values(device, points)
            output = self.output/'http-query'/device
            published = subprocess.run([sys.executable,str(ROOT/'local_demo_client.py'),'--outbox',str(dest),
                                       '--store-url',self.store_url,'--output',str(output)],capture_output=True,text=True,
                                      encoding='utf-8',errors='replace',timeout=20)
            (self.output/(device+'-readback.log')).write_text(published.stdout+published.stderr,encoding='utf-8')
            if published.returncode:
                raise RuntimeError('independent HTTP readback failed')
            readbacks += json.loads((output/'http-readback.json').read_text(encoding='utf-8'))
            channels[device] = {'event_id':event['event_id'],'point_rows':len(points),'HTTP_readback':'PASS',
                                'acquisition':'PASS' if not completed.returncode else 'EXPECTED_FAILURE_PRESERVED'}
        client = LocalClient(self.store_url)
        if client.call('GET','/health')[1]['events'] != len(channels):
            raise AssertionError('unexpected local event count')
        (self.output/'business-readback.json').write_text(json.dumps(readbacks,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        return {'status':'PASS','scope':'local-demo-only: Android C -> separate gateway -> SQLite HTTP store -> independent HTTP readback',
                'scenario':self.args.scenario,'events_written':len(channels),'events_readback_equal':len(readbacks),
                'channels':channels,'duplicate_deduplicated':True,'same_ID_changed_bytes_rejected':True,
                'source_hashes':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'gateway.py',ROOT/'local_demo_store.py',ROOT/'local_demo_client.py',ROOT/'run_local_demo.py']},
                'installed_apk_sha256':self.manifest['installed_apk_sha256'],
                'public_base':False,'server_WMS_accepted':False,'personal_phone_touched':False}

    def verify_values(self, device, points):
        if device=='handheld':
            if points['S2-PT-EXT-06-020']['value'] != 'SIM-STEEL-B001':
                raise AssertionError('scanner value mismatch')
        elif device=='scale':
            gross,tare = map(float,self.manifest['weighing_inputs'])
            fault = self.manifest['weighing_flags']['模拟仪表超量程']
            stable = self.manifest['weighing_flags']['重量稳定']
            quality = 'BAD' if fault else 'GOOD' if stable else 'UNCERTAIN'
            for code,value in [('007',(gross-tare)/1000),('008',gross/1000),('009',tare/1000)]:
                p=points['S2-PT-IMG-05-'+code]
                if p['value'] != (None if fault else value) or p['quality_code'] != quality:
                    raise AssertionError('weight/quality mismatch')
        elif device=='silo':
            level = float(self.manifest['process_inputs'][0])
            if abs(points['S2-PT-IMG-06-017']['value']-level)>0.001 or abs(points['S2-PT-IMG-06-019']['value']-level*30)>0.01:
                raise AssertionError('silo calculation mismatch')

    def close(self):
        errors=[]
        ports=list(self.forwards.values())
        if self.proxy:
            ports.append(self.proxy.port)
            try:self.proxy.close()
            except Exception as exc:errors.append(str(exc))
            (self.output/'fault-proxy.json').write_text(json.dumps(self.proxy.evidence,indent=2)+'\n',encoding='utf-8')
        for port in self.forwards.values():
            try:self.cmd('forward','--remove','tcp:'+str(port))
            except Exception as exc:errors.append(str(exc))
        if self.service_started:
            try:
                self.tap('连接');self.tap('停止服务（模拟断连）');self.tap('扫码')
                self.manifest['queue_after'] = self.live()[1]
                if self.manifest['queue_after']:
                    errors.append('generated test scan still pending; preserved for review, not deleted')
                self.tap(self.original_tab)
            except Exception as exc:errors.append(str(exc))
        if self.store:
            if hasattr(self,'store_url'):ports.append(int(self.store_url.rsplit(':',1)[1]))
            try:
                if self.store.poll() is None:
                    self.store.stdin.write(b'STOP\n');self.store.stdin.flush();self.store.stdin.close();self.store.wait(timeout=6)
            except (BrokenPipeError,OSError,subprocess.TimeoutExpired):
                self.store.terminate();self.store.wait(timeout=5);errors.append('local store required forced termination')
        if self.store_log:self.store_log.close()
        deadline=time.monotonic()+3
        while time.monotonic()<deadline and any(not unused(p) for p in ports):time.sleep(.05)
        remaining=[p for p in ports if not unused(p)]
        self.manifest['ports_still_listening']=remaining
        self.manifest['cleanup_errors']=errors
        self.write_manifest()
        return remaining,errors


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    sdk_adb=Path(os.environ.get('ANDROID_HOME','D:/anddroidSDK'))/'platform-tools'/('adb.exe' if os.name=='nt' else 'adb')
    parser.add_argument('--adb',default=str(sdk_adb) if sdk_adb.is_file() else shutil.which('adb') or 'adb')
    parser.add_argument('--serial',default='emulator-5554')
    parser.add_argument('--scenario',choices=['normal','bad-checksum','timeout'],default='normal')
    parser.add_argument('--output',type=Path,default=ROOT/'runtime'/('local-demo-'+time.strftime('%Y%m%d-%H%M%S')))
    args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=False)
    demo=Demo(args)
    result={'status':'FAIL','scenario':args.scenario,'public_base':False,'server_WMS_accepted':False}
    try:
        result=demo.run()
    except Exception as exc:
        result['error']=type(exc).__name__+': '+str(exc)
    finally:
        remaining,errors=demo.close()
        result['ports_still_listening']=remaining
        result['cleanup_errors']=errors
        if remaining or errors:result['status']='FAIL'
        (args.output/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))
    return 0 if result['status']=='PASS' else 1


if __name__=='__main__':sys.exit(main())
