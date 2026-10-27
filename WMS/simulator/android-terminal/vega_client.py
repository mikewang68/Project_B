"""Independent VEGAPULS 6X readback. Explicit PV meaning/calibration; no WMS writes."""
import argparse
import json
import math
from pathlib import Path
import socket
import struct
import time
from ee600_client import crc16, FrameError

STATION = 246
UNITS = {32: '°C', 33: '°F', 40: 'US gal', 41: 'L', 42: 'Imp gal', 43: 'm³',
         44: 'ft', 45: 'm', 46: 'barrel', 47: 'in', 48: 'cm', 49: 'mm',
         111: 'yd³', 112: 'ft³', 113: 'in³'}
METRES = {44: .3048, 45: 1, 47: .0254, 48: .01, 49: .001}


def read_request(address, words=2, function=4):
    if not 0 <= address <= 65535 or not 1 <= words <= 125 or address + words > 65536 or function not in (3, 4):
        raise ValueError('invalid read-only request')
    payload = struct.pack('>BBHH', STATION, function, address, words)
    return payload + crc16(payload)


def response(sock, query):
    raw = bytearray()
    def take(size):
        try:
            while size:
                part = sock.recv(size)
                if not part:
                    raise ConnectionError('incomplete RTU response')
                raw.extend(part)
                size -= len(part)
        except OSError as exc:
            raise FrameError(type(exc).__name__ + ': ' + str(exc), raw) from exc
    take(3)
    exceptional = bool(raw[1] & 0x80)
    if exceptional:
        take(2)
    else:
        if raw[2] > 250:
            raise FrameError('invalid RTU byte count', raw)
        take(raw[2] + 2)
    if raw[:2] != bytes([STATION, query[1] | (0x80 if exceptional else 0)]):
        raise FrameError('RTU station/function mismatch', raw)
    if crc16(raw[:-2]) != raw[-2:]:
        raise FrameError('RTU CRC mismatch', raw)
    if not exceptional and raw[2] != int.from_bytes(query[4:6], 'big') * 2:
        raise FrameError('RTU register length mismatch', raw)
    return bytes(raw), raw[2] if exceptional else None


def decode(status, pv, unit_code, pv_meaning='unspecified', empty_m=None, full_m=None):
    """ABCD float + uint32 status/unit (the latter word order is a simulation convention)."""
    if len(status) != 4 or len(pv) != 4:
        raise ValueError('complete status and PV required')
    if pv_meaning not in ('unspecified', 'distance'):
        raise ValueError('unsupported PV meaning')
    if (empty_m is None) != (full_m is None):
        raise ValueError('both empty and full distances required')
    if empty_m is not None and not (math.isfinite(empty_m) and math.isfinite(full_m) and 0 <= full_m < empty_m):
        raise ValueError('invalid calibration distances')
    bits = int.from_bytes(status, 'big')
    raw_value = struct.unpack('>f', pv)[0]
    result = {'status_bits': bits, 'unit_code': unit_code, 'unit': UNITS.get(unit_code),
              'value': raw_value if math.isfinite(raw_value) else None,
              'quality': 'UNCERTAIN', 'reason': 'reference simulation; PV mapping and DWORD order not field-verified',
              'pv_meaning': pv_meaning, 'level_percent': None, 'level_quality': 'UNCERTAIN',
              'level_reason': 'explicit distance meaning, length unit and empty/full calibration required',
              'source_ts_ms': None, 'mass_tonnes': None}
    if bits & 1 or not math.isfinite(raw_value):
        result.update(value=None, quality='BAD', reason='PV invalid bit0' if bits & 1 else 'nonfinite PV',
                      level_quality='BAD', level_reason='invalid PV; calculation blocked')
    elif unit_code not in UNITS:
        result.update(value=None, quality='BAD', reason='unknown unit code', level_quality='BAD', level_reason='unknown unit; calculation blocked')
    elif pv_meaning == 'distance' and unit_code in METRES and empty_m is not None:
        distance = raw_value * METRES[unit_code]
        if full_m <= distance <= empty_m:
            result.update(level_percent=100 * (empty_m - distance) / (empty_m - full_m),
                          level_reason='reference linear calibration; not commissioned',
                          calibration={'empty_m': empty_m, 'full_m': full_m})
        else:
            result.update(level_quality='BAD', level_reason='distance outside calibration interval; no clamping')
    return result


def capture(host, port, output, pv_meaning='unspecified', empty_m=None, full_m=None):
    # Validate operator metadata before connecting or creating an output directory.
    decode(bytes(4), struct.pack('>f', 0), 45, pv_meaning, empty_m, full_m)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    frames = []
    report = {'schema': 'vega6x.reference.v1', 'simulated': True, 'scope': 'OEM-reference-only',
              'manual': '1015559-EN-250117', 'result': 'ACQUISITION_FAILED',
              'endpoint': {'host': host, 'port': port, 'station': STATION, 'transport': 'RTU byte tunnel'},
              'integer_dword_order': 'ABCD simulation convention; confirm with physical device',
              'readings': None, 'frames': frames, 'point_mapping_110_updated': False,
              'public_base': False, 'server_WMS_accepted': False}
    try:
        with socket.create_connection((host, port), timeout=3) as sock:
            payloads = []
            # Status and PV are one read/snapshot. Unit before/after must agree.
            for address, words in [(104, 2), (2000, 4), (104, 2)]:
                query = read_request(address, words)
                frame = {'pdu_address': address, 'request_hex': query.hex(),
                         'observed_ts_ms': int(time.time() * 1000)}
                frames.append(frame)
                sock.sendall(query)
                try:
                    raw, exception = response(sock, query)
                except FrameError as exc:
                    frame['response_hex'] = exc.received.hex()
                    raise
                frame['response_hex'] = raw.hex()
                if exception is not None:
                    raise ValueError('Modbus exception ' + str(exception))
                payloads.append(raw[3:-2])
            if payloads[0] != payloads[2]:
                raise ValueError('unit changed during acquisition')
            report['readings'] = decode(payloads[1][:4], payloads[1][4:], int.from_bytes(payloads[0], 'big'), pv_meaning, empty_m, full_m)
            report['result'] = 'CAPTURED'
    except (OSError, ValueError) as exc:
        report['readings'] = {'value': None, 'quality': 'BAD', 'level_percent': None,
                              'level_quality': 'BAD', 'reason': type(exc).__name__ + ': ' + str(exc)}
    (output / 'vega-readback.json').write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=19064)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--pv-meaning', choices=['unspecified', 'distance'], default='unspecified')
    parser.add_argument('--empty-m', type=float)
    parser.add_argument('--full-m', type=float)
    args = parser.parse_args()
    report = capture(args.host, args.port, args.output, args.pv_meaning, args.empty_m, args.full_m)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if report['result'] != 'CAPTURED':
        raise SystemExit(1)
