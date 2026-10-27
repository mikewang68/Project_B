"""Emulator-only camera permission/cancel check; no scan or WMS writes."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    adb = [args.adb, '-s', args.serial]

    def call(*parts):
        return subprocess.check_output(adb + list(parts), text=True, encoding='utf-8', errors='replace')

    assert call('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'emulator-only'
    args.output.mkdir(parents=True, exist_ok=True)

    def ui():
        call('shell', 'uiautomator', 'dump', '/sdcard/wms-camera-test.xml')
        return ET.fromstring(call('shell', 'cat', '/sdcard/wms-camera-test.xml'))

    def tap(*, text=None, resource=None):
        for node in ui().iter('node'):
            if (text is not None and node.get('text') == text) or (resource and node.get('resource-id', '').endswith(resource)):
                x0, y0, x1, y1 = map(int, re.findall(r'\d+', node.get('bounds')))
                if x1 > x0 and y1 > y0:
                    call('shell', 'input', 'tap', str((x0+x1)//2), str((y0+y1)//2))
                    return
        raise AssertionError(text or resource)

    def queued():
        for node in ui().iter('node'):
            match = re.search(r'待发扫码 (\d+)', node.get('text', ''))
            if match:
                return int(match.group(1))
        raise AssertionError('queue status missing')

    call('shell', 'am', 'force-stop', 'cn.projectb.terminal')
    call('shell', 'pm', 'revoke', 'cn.projectb.terminal', 'android.permission.CAMERA')
    call('shell', 'am', 'start', '-n', 'cn.projectb.terminal/.MainActivity')
    before = queued()
    tap(text='摄像头扫二维码')
    resources = [n.get('resource-id','') for n in ui().iter('node')]
    for suffix in [':id/permission_deny_button', ':id/permission_deny_and_dont_ask_again_button']:
        if any(r.endswith(suffix) for r in resources):
            tap(resource=suffix)
            break
    assert any('未获得摄像头权限' in n.get('text', '') for n in ui().iter('node'))
    call('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    assert queued() == before
    # Only the emulator is granted permission here; a personal phone must use
    # its own system prompt. This also handles an earlier "don't ask again".
    call('shell', 'pm', 'grant', 'cn.projectb.terminal', 'android.permission.CAMERA')
    tap(text='摄像头扫二维码')
    tree = ui()
    assert any('将电脑上的测试二维码对准画面' in n.get('text', '') for n in tree.iter('node'))
    assert not any('相机启动失败' in n.get('text', '') for n in tree.iter('node'))
    (args.output/'emulator-camera.png').write_bytes(subprocess.check_output(adb+['exec-out','screencap','-p']))
    call('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    assert queued() == before
    report = {'scope':'emulator camera UI; no recognition or WMS writes', 'permission_denial':'PASS',
              'permission_grant':'PASS (emulator ADB grant)', 'camera_preview':'PASS', 'cancel_keeps_queue':'PASS',
              'queued_before':before, 'queued_after':before}
    (args.output/'camera-ui.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False))


if __name__ == '__main__':
    main()
