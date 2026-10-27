"""Real C-on-Android smoke test. Explicitly emulator-only; does not touch a personal phone."""
import argparse
import json
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import gateway as g
from wms_bridge import Bridge, sim


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--serial', default='emulator-5554')
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = ['adb', '-s', args.serial]

    def cmd(*a, binary=False):
        return subprocess.check_output(adb + list(a), text=not binary, encoding=None if binary else 'utf-8', errors=None if binary else 'replace')

    if cmd('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        raise SystemExit('This test is emulator-only; personal-phone state must not be changed by smoke tests.')

    def ui():
        cmd('shell', 'uiautomator', 'dump', '/sdcard/wms-smoke.xml')
        return ET.fromstring(cmd('shell', 'cat', '/sdcard/wms-smoke.xml'))

    def tap(label):
        for _ in range(3):
            tree = ui()
            for node in tree.iter('node'):
                if node.get('text') == label:
                    a, b, c, d = map(int, re.findall(r'\d+', node.get('bounds', '')))
                    if c > a and d > b:
                        cmd('shell', 'input', 'tap', str((a + c) // 2), str((b + d) // 2))
                        return
            time.sleep(.3)
        raise AssertionError('visible UI action not found: ' + label)

    cmd('shell', 'am', 'force-stop', 'cn.projectb.terminal')
    cmd('shell', 'am', 'start', '-n', 'cn.projectb.terminal/.MainActivity')
    # First label is known and finite; queue may already contain interrupted emulator test data.
    tap('读取标签并保存')
    first = ui()
    queued = next(n.get('text') for n in first.iter('node') if '待发扫码' in n.get('text', ''))
    cmd('shell', 'am', 'force-stop', 'cn.projectb.terminal')
    cmd('shell', 'am', 'start', '-n', 'cn.projectb.terminal/.MainActivity')
    restored = ui()
    restored_text = next(n.get('text') for n in restored.iter('node') if '待发扫码' in n.get('text', ''))
    assert queued == restored_text, (queued, restored_text)
    tap('连接')
    tap('启动 USB／仅本机服务')
    forwards = {}
    results = {'scope': 'android-emulator-native-C/local-WMS-demo', 'personal_phone': 'NOT_TESTED',
               'public_base': 'NOT_TESTED', 'queue_restart': 'PASS', 'channels': {}}
    try:
        for name, (_, _, remote) in g.DEVICES.items():
            forwards[name] = int(cmd('forward', 'tcp:0', 'tcp:' + str(remote)).strip())
        # A missing ACK must leave the same original bytes queued after reconnect.
        with socket.create_connection(('127.0.0.1', forwards['handheld']), timeout=5) as sock:
            _, unacked = g.scanner_frame(sock)
        out = g.Outbox(args.output / 'handheld')
        with socket.create_connection(('127.0.0.1', forwards['handheld']), timeout=5) as sock:
            values, evidence = g.acquire(sock, 'handheld')
            assert bytes.fromhex(evidence[0]['response_hex']) == unacked
            event = out.capture('handheld', 'android-smoke', 'boot-' + str(time.time_ns()), values, evidence)
            sock.sendall(b'\x06')
        out.export()
        out.db.close()
        results['ack_disconnect_recovery'] = 'PASS'
        results['channels']['handheld'] = {'event_id': event['event_id'], 'raw_hex': unacked.hex()}
        cfg = json.loads((ROOT.parent / 'config.example.json').read_text(encoding='utf-8'))
        cfg.pop('additional_orders', None)
        cfg['state_dir'] = str(args.output / 'wms-demo')
        engine = sim.Engine(cfg)
        bridge = Bridge(engine)
        # This receipt is an explicitly labelled local fixture, not a public-base claim.
        receipt = {'event_id': event['event_id'], 'event': event, 'state': 'COMMITTED', 'broker_message_id': 'LOCAL-CONTRACT-FIXTURE'}
        staged = bridge.stage(receipt)
        assert staged['status'] == 'SCANNED'
        assert engine.snapshot()['demo_inventory'] == 0
        engine.confirm()
        engine.process_once()
        assert engine.snapshot()['demo_inventory'] == 1
        bridge.stage(receipt)
        assert engine.snapshot()['demo_inventory'] == 1
        results['wms_demo'] = {'inventory': 1, 'source_links': bridge.source_links(), 'public_base_receipt': False}
        for name in ['scale', 'silo', 'unbox', 'chute']:
            out = g.Outbox(args.output / name)
            with socket.create_connection(('127.0.0.1', forwards[name]), timeout=5) as sock:
                values, evidence = g.acquire(sock, name)
                e = out.capture(name, 'android-smoke', 'boot-' + str(time.time_ns()), values, evidence)
            out.export()
            out.db.close()
            results['channels'][name] = {'event_id': e['event_id'], 's2_points': sum(p['point_code'].startswith('S2-') for p in e['payload']['points']), 'raw_exchanges': len(evidence)}
            if name == 'scale':
                pts = {p['point_code']: p for p in e['payload']['points']}
                assert pts['S2-PT-IMG-05-007']['value'] == 20
                assert pts['S2-PT-IMG-05-008']['value'] == 32.5
                assert pts['S2-PT-IMG-05-009']['value'] == 12.5
        with socket.create_connection(('127.0.0.1', forwards['silo']), timeout=5) as sock:
            sock.sendall(bytes.fromhex('000100000006010600000001'))
            assert g.exact(sock, 9).hex() == '000100000003018601'
            sock.sendall(bytes.fromhex('0002000000060103ffff0001'))
            assert g.exact(sock, 9).hex() == '000200000003018302'
        results['write_rejected'] = 'PASS'
        results['invalid_register_rejected'] = 'PASS'
        tap('扫码')
        (args.output / 'android-terminal.png').write_bytes(cmd('exec-out', 'screencap', '-p', binary=True))
        results['result'] = 'PASS'
    finally:
        for port in forwards.values():
            cmd('forward', '--remove', 'tcp:' + str(port))
        (args.output / 'android-smoke.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
