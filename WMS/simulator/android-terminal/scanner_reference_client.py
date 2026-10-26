"""Isolated ACK/NAK reference probe: no persistent scan queue, public base or WMS."""
import argparse
import json
from pathlib import Path
import socket
import time

CASES = {'ack': (1, [6]), 'nak-then-ack': (2, [21, 6]),
         'nak-limit': (3, [21, 21, 21]), 'timeout': (1, []),
         'unexpected-timeout': (1, [0]), 'disconnect': (1, [])}


def capture(host, port, output, scenario='ack', expected_tag='SIM-HANDSHAKE-001', connected=None):
    if scenario not in CASES:
        raise ValueError('unknown scenario')
    if not 1 <= len(expected_tag) <= 96 or any(not 32 <= ord(c) <= 126 for c in expected_tag):
        raise ValueError('1-96 printable ASCII required')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    report = {'schema': 'ds2208.handshake.reference.v1', 'scenario': scenario,
              'scope': 'isolated-reference-only', 'simulated': True, 'result': 'IN_PROGRESS',
              'frames': [], 'replies_hex': [], 'attempt_count_interpretation': '3 total sends including first; physical confirmation pending',
              'public_base': False, 'server_WMS_accepted': False, 'persistent_queue_written': False}
    expected = expected_tag.encode('ascii') + b'\r\n'
    count, replies = CASES[scenario]
    try:
        with socket.create_connection((host, port), timeout=3) as sock:
            sock.settimeout(15)  # Time for operator to click Send; not the device ACK timeout.
            if connected is not None:
                connected()
            for index in range(count):
                raw = bytearray()
                frame = {'received_hex': '', 'observed_ts_ms': int(time.time() * 1000)}
                report['frames'].append(frame)
                try:
                    while len(raw) < len(expected):
                        part = sock.recv(len(expected) - len(raw))
                        if not part:
                            raise ConnectionError('closed before complete reference frame')
                        raw.extend(part)
                finally:
                    frame['received_hex'] = raw.hex()
                if raw != expected:
                    raise ValueError('unexpected reference tag/frame')
                sock.settimeout(4)
                if index < len(replies):
                    reply = bytes([replies[index]])
                    sock.sendall(reply)
                    report['replies_hex'].append(reply.hex())
            start = time.monotonic()
            if scenario == 'disconnect':
                sock.shutdown(socket.SHUT_RDWR)
                report['peer_disconnect_triggered'] = True
            else:
                extra = sock.recv(256)
                if extra:
                    report['unexpected_extra_hex'] = extra.hex()
                    raise ValueError('extra frame after expected terminal action')
                report['peer_closed_connection'] = True
                report['seconds_from_final_frame_to_close'] = time.monotonic() - start
                if scenario in ['timeout', 'unexpected-timeout'] and not 1.5 <= report['seconds_from_final_frame_to_close'] <= 3.5:
                    raise ValueError('timeout outside reference tolerance')
            report['result'] = 'OBSERVED'
    except (OSError, ValueError) as exc:
        report.update(result='ACQUISITION_FAILED', reason=type(exc).__name__ + ': ' + str(exc))
    (output / 'handshake-readback.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return report


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--host', default='127.0.0.1')
    p.add_argument('--port', type=int, default=19065)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--scenario', choices=list(CASES), default='ack')
    p.add_argument('--expected-tag', default='SIM-HANDSHAKE-001')
    a = p.parse_args()
    report = capture(a.host, a.port, a.output, a.scenario, a.expected_tag,
                     connected=lambda: print('已连接19065，请在终端原厂握手参考页发送一次参考标签。', flush=True))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if report['result'] != 'OBSERVED':
        raise SystemExit(1)
