import json
from pathlib import Path
import socket
import tempfile
import threading
import unittest
from scanner_reference_client import capture


class ReferenceScannerTests(unittest.TestCase):
    def serve(self, scenario, payload=b'SIM-HANDSHAKE-001\r\n', extra=b''):
        replies=[]
        with socket.socket() as listener:
            listener.bind(('127.0.0.1',0));listener.listen(1);listener.settimeout(3)
            def remote():
                with listener.accept()[0] as peer:
                    peer.settimeout(3)
                    count=2 if scenario=='nak-then-ack' else 3 if scenario=='nak-limit' else 1
                    for index in range(count):
                        try:
                            peer.sendall(payload[:3]);peer.sendall(payload[3:])
                            if payload!=b'SIM-HANDSHAKE-001\r\n':return
                            if scenario in ['ack','nak-then-ack','nak-limit']:
                                replies.append(peer.recv(1).hex())
                        except (ConnectionError,OSError):return
                    if extra:peer.sendall(extra)
            worker=threading.Thread(target=remote);worker.start()
            with tempfile.TemporaryDirectory() as temp:
                output=Path(temp)/'case'
                result=capture('127.0.0.1',listener.getsockname()[1],output,scenario)
                self.assertEqual(json.loads((output/'handshake-readback.json').read_text(encoding='utf-8')),result)
            worker.join(4);self.assertFalse(worker.is_alive())
        return result,replies

    def test_ack_and_fragmented_frame_over_independent_socket(self):
        r,replies=self.serve('ack')
        self.assertEqual((r['result'],replies),('OBSERVED',['06']))
        self.assertFalse(r['persistent_queue_written'])
        self.assertFalse(r['server_WMS_accepted'])

    def test_nak_retry_then_ack_retains_identical_frame(self):
        r,replies=self.serve('nak-then-ack')
        self.assertEqual((r['result'],replies),('OBSERVED',['15','06']))
        self.assertEqual(r['frames'][0]['received_hex'],r['frames'][1]['received_hex'])

    def test_nak_limit_requires_close_after_three_sends(self):
        r,replies=self.serve('nak-limit')
        self.assertEqual((r['result'],replies),('OBSERVED',['15']*3))
        r,_=self.serve('nak-limit',extra=b'SIM-HANDSHAKE-001\r\n')
        self.assertEqual(r['result'],'ACQUISITION_FAILED')

    def test_incomplete_and_wrong_label_preserve_received_bytes(self):
        for raw in [b'SIM-',b'SIM-HANDSHAKE-002\r\n']:
            r,_=self.serve('ack',payload=raw)
            self.assertEqual(r['result'],'ACQUISITION_FAILED')
            self.assertEqual(r['frames'][0]['received_hex'],raw.hex())

    def test_early_close_is_not_claimed_as_valid_device_timeout(self):
        r,_=self.serve('timeout')
        self.assertEqual(r['result'],'ACQUISITION_FAILED')
        self.assertIn('timeout outside',r['reason'])

    def test_invalid_metadata_is_rejected_before_any_connection(self):
        for tag in ['', 'bad\nlabel', '中', 'x'*97]:
            with self.assertRaises(ValueError):capture('127.0.0.1',1,'unused',expected_tag=tag)
        with self.assertRaises(ValueError):capture('127.0.0.1',1,'unused',scenario='retry_forever')
