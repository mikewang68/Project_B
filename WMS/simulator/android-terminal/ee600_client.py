"""Read the separate EE600 OEM reference profile; no public-base or WMS writes."""
import argparse
import json
import math
from pathlib import Path
import socket
import struct
import time

STATION = 43


def crc16(data):
    crc = 0xffff
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ (0xa001 if crc & 1 else 0)
    return crc.to_bytes(2, 'little')


def read_request(address, words=2, function=4):
    if not 0 <= address <= 65535 or not 1 <= words <= 125 or address + words > 65536 or function not in [3, 4]:
        raise ValueError('invalid read-only register request')
    data = struct.pack('>BBHH', STATION, function, address, words)
    return data + crc16(data)


class FrameError(ValueError):
    def __init__(self, reason, received):
        super().__init__(reason)
        self.received = bytes(received)


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


def decode(raw, encoding, scale=1):
    if encoding == 'float32-cdab':
        if len(raw) != 4:
            raise ValueError('two complete registers required')
        value = struct.unpack('>f', raw[2:4] + raw[0:2])[0]
        if not math.isfinite(value):
            return None, 'BAD', 'nonfinite OEM measurement'
    elif encoding == 'int16':
        if len(raw) != 2:
            raise ValueError('one complete register required')
        number = struct.unpack('>h', raw)[0]
        if number == -32768:
            return None, 'BAD', 'OEM error sentinel 0x8000'
        value = number / scale
    else:
        raise ValueError('unsupported encoding')
    # Status address is unresolved; pressure reference and fan calibration are not commissioned.
    return value, 'UNCERTAIN', 'reference simulation; status/reference/calibration not commissioned'


def capture(host, port, output, read_flow=False):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    profile = [
        ('differential_pressure_pa', 0x4be, 2, 'float32-cdab', 'Pa', 1),
        ('differential_pressure_kpa', 0x4c0, 2, 'float32-cdab', 'kPa', 1),
        ('differential_pressure_int16_kpa', 0x100c, 1, 'int16', 'kPa', 1000)]
    if read_flow:
        profile.append(('configured_air_volume_flow', 0x41e, 2, 'float32-cdab', 'm³/h', 1))
    readings, failed = [], False
    try:
        with socket.create_connection((host, port), timeout=3) as sock:
            for name, address, words, encoding, unit, scale in profile:
                query = read_request(address, words)
                record = {'name': name, 'address_zero_based': address, 'encoding': encoding, 'unit': unit,
                          'request_hex': query.hex(), 'source_ts_ms': None,
                          'observed_ts_ms': int(time.time() * 1000)}
                try:
                    sock.sendall(query)
                    raw, exception = response(sock, query)
                    record['response_hex'] = raw.hex()
                    if exception is not None:
                        record.update(value=None, quality='UNCERTAIN' if exception == 2 else 'BAD',
                                      reason='Modbus exception ' + str(exception))
                    else:
                        value, quality, reason = decode(raw[3:-2], encoding, scale)
                        record.update(value=value, quality=quality, reason=reason)
                except (FrameError, OSError) as exc:
                    record.update(response_hex=getattr(exc, 'received', b'').hex(), value=None, quality='BAD', reason=str(exc))
                    failed = True
                readings.append(record)
                if failed:
                    break
    except OSError as exc:
        failed = True
        readings.append({'value': None, 'quality': 'BAD', 'reason': type(exc).__name__ + ': ' + str(exc)})
    report = {'schema': 'ee600.reference.v1', 'simulated': True, 'scope': 'OEM-reference-only',
              'result': 'ACQUISITION_FAILED' if failed else 'CAPTURED',
              'endpoint': {'host': host, 'port': port, 'station': STATION, 'transport': 'RTU byte tunnel'},
              'readings': readings, 'reserved_status_addresses_read': False,
              'point_mapping_110_updated': False, 'public_base': False, 'server_WMS_accepted': False}
    (output / 'ee600-readback.json').write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', default=19063, type=int)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--read-flow', action='store_true')
    args = parser.parse_args()
    report = capture(args.host, args.port, args.output, args.read_flow)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if report['result'] != 'CAPTURED':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
