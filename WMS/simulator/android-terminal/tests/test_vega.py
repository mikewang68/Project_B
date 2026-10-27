import math
from pathlib import Path
import socket
import struct
import tempfile
import threading
import unittest
from vega_client import capture, crc16, decode, FrameError, read_request, response
from tests.test_ee600 import FragmentedSocket


class VegaTests(unittest.TestCase):
    def reading(self, value=12.9, unit=45, status=14, **metadata):
        return decode(status.to_bytes(4, 'big'), struct.pack('>f', value), unit, **metadata)

    def test_no_implicit_distance_percent_or_tonnes(self):
        for metadata in [{}, {'pv_meaning': 'distance'}, {'empty_m': 35, 'full_m': 1}]:
            r = self.reading(**metadata)
            self.assertEqual(r['quality'], 'UNCERTAIN')
            self.assertIsNone(r['level_percent'])
            self.assertIsNone(r['mass_tonnes'])

    def test_calibration_units_endpoints_and_no_clipping(self):
        for unit, value in [(45, 12.9), (44, 12.9/.3048), (47, 12.9/.0254), (48, 1290), (49, 12900)]:
            r = self.reading(value, unit, pv_meaning='distance', empty_m=35, full_m=1)
            self.assertAlmostEqual(r['level_percent'], 65, places=4)
            self.assertEqual(r['level_quality'], 'UNCERTAIN')
        for distance, expected in [(35, 0), (1, 100), (36, None), (0, None)]:
            r = self.reading(distance, pv_meaning='distance', empty_m=35, full_m=1)
            self.assertEqual(r['level_percent'], expected)
        for unit in [43, 32, 99]:
            r = self.reading(unit=unit, pv_meaning='distance', empty_m=35, full_m=1)
            self.assertIsNone(r['level_percent'])

    def test_invalid_bit_and_nonfinite_block_business_values(self):
        for value, status in [(12.9, 15), (math.nan, 14), (math.inf, 14)]:
            r = self.reading(value=value, status=status, pv_meaning='distance', empty_m=35, full_m=1)
            self.assertEqual(r['quality'], 'BAD')
            self.assertIsNone(r['value'])
            self.assertIsNone(r['level_percent'])

    def test_metadata_and_register_boundaries(self):
        for kwargs in [{'empty_m': 35}, {'empty_m': 1, 'full_m': 35}, {'empty_m': math.nan, 'full_m': 1}, {'pv_meaning': 'level'}]:
            with self.assertRaises(ValueError):
                self.reading(**kwargs)
        for args in [(-1, 2, 4), (65535, 2, 4), (2000, 0, 4), (0, 126, 4), (2000, 4, 6)]:
            with self.assertRaises(ValueError):
                read_request(*args)

    def test_fragmented_crc_identity_length_and_truncated_evidence(self):
        query = read_request(2000, 4)
        payload = bytes.fromhex('f604080000000e414e6666')
        frame = payload + crc16(payload)
        self.assertEqual(response(FragmentedSocket(frame), query), (frame, None))
        for data in [frame[:-1]+bytes([frame[-1]^1]), b'\xf5'+frame[1:], frame[:6]]:
            with self.assertRaises(FrameError) as caught:
                response(FragmentedSocket(data), query)
            self.assertEqual(caught.exception.received, data)

    def test_independent_socket_and_unit_change_rejected(self):
        for changed in [False, True]:
            requests=[]
            with socket.socket() as server:
                server.bind(('127.0.0.1',0));server.listen(1);server.settimeout(3)
                def serve():
                    with server.accept()[0] as peer:
                        peer.settimeout(3)
                        for payload in [bytes.fromhex('0000002d'), bytes.fromhex('0000000e414e6666'), bytes.fromhex('0000002c' if changed else '0000002d')]:
                            query=b''
                            while len(query)<8:
                                chunk=peer.recv(8-len(query))
                                if not chunk:return
                                query+=chunk
                            requests.append(query)
                            raw=bytes([246,4,len(payload)])+payload
                            peer.sendall(raw+crc16(raw))
                worker=threading.Thread(target=serve);worker.start()
                with tempfile.TemporaryDirectory() as folder:
                    report=capture('127.0.0.1',server.getsockname()[1],Path(folder)/'capture','distance',35,1)
                worker.join(4)
                self.assertFalse(worker.is_alive())
                self.assertEqual([int.from_bytes(q[2:4],'big') for q in requests],[104,2000,104])
                self.assertEqual(report['result'],'ACQUISITION_FAILED' if changed else 'CAPTURED')
                self.assertEqual(report['readings']['quality'],'BAD' if changed else 'UNCERTAIN')
                if not changed:self.assertAlmostEqual(report['readings']['level_percent'],65,places=4)
