import math
import unittest
from pathlib import Path
import socket
import tempfile
import threading
from ee600_client import capture, crc16, decode, FrameError, read_request, response


class FragmentedSocket:
    def __init__(self, data):
        self.data = bytearray(data)
    def recv(self, size):
        value = bytes(self.data[:min(size, 1)])
        del self.data[:len(value)]
        return value


class Ee600Tests(unittest.TestCase):
    def test_capture_sends_requests_to_an_independent_socket_server(self):
        requests=[]
        with socket.socket() as server:
            server.bind(('127.0.0.1',0));server.listen(1);server.settimeout(3)
            def serve():
                with server.accept()[0] as peer:
                    peer.settimeout(3)
                    for answer in [bytes.fromhex('2b0404fba543f861f3'),
                                   bytes.fromhex('2b040400003f00'),bytes.fromhex('2b040201f2')]:
                        query=b''
                        while len(query)<8:
                            chunk=peer.recv(8-len(query))
                            if not chunk:return
                            query+=chunk
                        requests.append(query)
                        peer.sendall(answer if len(answer)==9 else answer+crc16(answer))
            worker=threading.Thread(target=serve);worker.start()
            with tempfile.TemporaryDirectory() as folder:
                report=capture('127.0.0.1',server.getsockname()[1],Path(folder)/'capture')
            worker.join(4)
            self.assertFalse(worker.is_alive())
            self.assertEqual(report['result'],'CAPTURED')
            self.assertEqual(len(requests),3)
            self.assertEqual(requests[0].hex(),'2b0404be00021715')
            self.assertEqual([q[2:4].hex() for q in requests],['04be','04c0','100c'])

    def test_manufacturer_golden_frame_and_split_response(self):
        query = read_request(0x4be)
        self.assertEqual(query.hex(), '2b0404be00021715')
        frame = bytes.fromhex('2b0404fba543f861f3')
        raw, error = response(FragmentedSocket(frame), query)
        self.assertEqual((raw, error), (frame, None))
        value, quality, _ = decode(raw[3:-2], 'float32-cdab')
        self.assertAlmostEqual(value, 497.9659729003906)
        self.assertEqual(quality, 'UNCERTAIN')

    def test_error_sentinels_never_become_zero_or_good(self):
        for raw in ['00007fc0', '00007f80', '0000ff80']:
            value, quality, _ = decode(bytes.fromhex(raw), 'float32-cdab')
            self.assertIsNone(value)
            self.assertEqual(quality, 'BAD')
        self.assertEqual(decode(bytes.fromhex('8000'), 'int16', 1000)[:2], (None, 'BAD'))
        self.assertEqual(decode(bytes.fromhex('1388'), 'int16', 1000)[:2], (5.0, 'UNCERTAIN'))

    def test_bad_crc_identity_and_truncation_keep_received_bytes(self):
        query = read_request(0x4be)
        golden = bytes.fromhex('2b0404fba543f861f3')
        for data in [golden[:-1] + b'\x00', b'\x01' + golden[1:], golden[:5]]:
            with self.assertRaises(FrameError) as caught:
                response(FragmentedSocket(data), query)
            self.assertEqual(caught.exception.received, data)

    def test_reserved_or_unconfigured_exception_is_retained(self):
        payload = bytes.fromhex('2b8402')
        raw, error = response(FragmentedSocket(payload + crc16(payload)), read_request(0x41e))
        self.assertEqual(error, 2)
        self.assertEqual(raw, payload + crc16(payload))

    def test_invalid_read_parameters_and_incomplete_float_rejected(self):
        for args in [(-1, 2, 4), (65535, 2, 4), (0, 0, 4), (0, 126, 4), (0, 1, 6)]:
            with self.assertRaises(ValueError):
                read_request(*args)
        with self.assertRaises(ValueError):
            decode(b'\x00\x00', 'float32-cdab')
