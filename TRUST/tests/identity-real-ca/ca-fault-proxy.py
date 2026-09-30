"""Loopback TLS fault proxy for the isolated test CA only; never logs request bodies."""
import http.server
import json
import pathlib
import socket
import ssl
import time
import urllib.error
import urllib.request
import urllib.parse

ROOT = pathlib.Path('/srv/b-project-identity-test/TRUST/runtime/real-ca-faults')
UPSTREAM = 'https://127.0.0.1:39054'
CONTEXT = ssl.create_default_context(cafile=str(ROOT.parent / 'secrets/ca/org1-tls-root.pem'))


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        self.forward()

    def do_GET(self):
        self.forward()

    def forward(self):
        mode = (ROOT / 'mode').read_text().strip()
        if mode == 'outage':
            self.send_error(503, 'Injected isolated CA outage')
            return
        size = int(self.headers.get('Content-Length', '0'))
        if not 0 <= size <= 1048576:
            self.send_error(413)
            return
        body = self.rfile.read(size)
        headers = {k: v for k, v in self.headers.items()
                   if k.lower() not in ('host', 'connection', 'content-length')}
        req = urllib.request.Request(UPSTREAM + self.path, body if size else None, headers, method=self.command)
        try:
            response = urllib.request.urlopen(req, context=CONTEXT, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read()
            operation = urllib.parse.urlsplit(self.path).path.rstrip('/').rsplit('/', 1)[-1]
            with (ROOT / 'requests.jsonl').open('a') as log:
                log.write(json.dumps({'operation': operation, 'status': response.status, 'mode': mode}) + '\n')
            if operation == 'enroll' and 200 <= response.status < 300 and json.loads(data).get('success') is True:
                with (ROOT / 'issuances.jsonl').open('a') as log:
                    log.write(json.dumps({'mode': mode, 'upstreamStatus': response.status, 'time': time.time()}) + '\n')
                if mode in ('timeout', 'restart'):
                    # CA has committed issuance; withhold the response from the official client.
                    time.sleep(26)
                    self.close_connection = True
                    try:
                        self.connection.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                    return
            self.send_response(response.status)
            self.send_header('Content-Type', response.headers.get('Content-Type', 'application/json'))
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)


if __name__ == '__main__':
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 39154), Handler)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(str(ROOT / 'proxy.crt'), str(ROOT / 'proxy.key'))
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    server.serve_forever()
